package cc.kites.mineclaw.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict all-or-nothing providers.yml Schema-1 loader. */
public final class ProviderCatalogLoader {
    public static final String FILE_NAME = "providers.yml";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern HEADER_VARIABLE = Pattern.compile("\\$\\{([^{}]*)}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Set<String> RUNTIME_HEADER_VARIABLES = Set.of("session_id", "user_agent");
    private static final Set<String> MANAGED_HEADERS = Set.of("accept", "authorization", "connection",
            "content-length", "content-type", "expect", "host", "proxy-authorization",
            "transfer-encoding", "upgrade");
    private static final int MAX_EXTRA_HEADERS = 32;
    private static final int MAX_HEADER_VALUE_CODE_POINTS = 8_192;
    private static final int MAX_HEADER_VALUES_CODE_POINTS = 32_768;
    private static final Set<String> RESERVED = Set.of("model", "messages", "tools", "tool_choice",
            "stream", "stream_options", "max_tokens", "max_completion_tokens", "prompt_cache_key",
            "input", "instructions", "max_output_tokens", "store", "background", "previous_response_id",
            "conversation", "include");
    private final Function<String, String> processEnvironment;

    public ProviderCatalogLoader() {
        this(System::getenv);
    }

    public ProviderCatalogLoader(Function<String, String> processEnvironment) {
        this.processEnvironment = Objects.requireNonNull(processEnvironment, "processEnvironment");
    }

    public ProviderCatalog load(Path dataRoot, Path path) throws ConfigException {
        MineclawConfig.SecretEnvironment dotenv = DotEnvLoader.load(dataRoot.resolve(".env"));
        return parse(StrictYaml.load(dataRoot, path, FILE_NAME), dotenv);
    }

    public ProviderCatalog parse(String source, Map<String, String> dotenv) throws ConfigException {
        return parse(StrictYaml.parse(source, FILE_NAME), MineclawConfig.SecretEnvironment.of(dotenv));
    }

    private ProviderCatalog parse(JsonObject root, MineclawConfig.SecretEnvironment dotenv)
            throws ConfigException {
        exact(root, Set.of("schema", "default", "providers", "models"), "$", true);
        integer(root.get("schema"), "$.schema", 1, 1);
        String defaultModel = string(root.get("default"), "$.default");
        JsonObject providerEntries = object(root.get("providers"), "$.providers", false);
        JsonObject modelEntries = object(root.get("models"), "$.models", false);
        if (providerEntries.isEmpty() || modelEntries.isEmpty()) {
            throw invalid("$.providers and $.models must not be empty");
        }

        LinkedHashMap<String, ProviderCatalog.Provider> providers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : providerEntries.entrySet()) {
            String id = entry.getKey();
            if (!ID.matcher(id).matches()) {
                throw invalid("$.providers." + id + " has an invalid Provider ID");
            }
            providers.put(id, provider(id, object(entry.getValue(), "$.providers." + id, true), dotenv));
        }

        LinkedHashMap<String, ProviderCatalog.Model> models = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : modelEntries.entrySet()) {
            Reference reference = reference(entry.getKey(), "$.models." + entry.getKey());
            ProviderCatalog.Provider provider = providers.get(reference.providerId());
            if (provider == null) {
                throw invalid("$.models." + entry.getKey() + " references an undeclared Provider");
            }
            models.put(entry.getKey(), model(reference,
                    object(entry.getValue(), "$.models." + entry.getKey(), true), provider));
        }
        Reference defaultReference = reference(defaultModel, "$.default");
        if (!models.containsKey(defaultModel) || !providers.containsKey(defaultReference.providerId())) {
            throw invalid("$.default must exactly match a declared model reference");
        }
        return new ProviderCatalog(defaultModel, providers, models);
    }

    private ProviderCatalog.Provider provider(String id, JsonObject entry,
                                                MineclawConfig.SecretEnvironment dotenv)
            throws ConfigException {
        String path = "$.providers." + id;
        exact(entry, Set.of("api", "transport", "tools"), path, true);
        JsonObject api = object(entry.get("api"), path + ".api", true);
        exact(api, Set.of("type", "base_url", "api_key", "extra_headers"), path + ".api", false);
        for (String required : Set.of("type", "base_url", "api_key")) {
            if (!api.has(required)) {
                throw invalid(path + ".api." + required + " is required");
            }
        }
        String typeName = string(api.get("type"), path + ".api.type");
        ProviderCatalog.ApiType type = ProviderCatalog.ApiType.fromWireName(typeName)
                .orElseThrow(() -> invalid(path + ".api.type is unsupported"));
        URI baseUrl = baseUrl(string(api.get("base_url"), path + ".api.base_url"),
                path + ".api.base_url");
        String apiKey = credential(string(api.get("api_key"), path + ".api.api_key"),
                path + ".api.api_key", dotenv);
        Map<String, String> extraHeaders = api.has("extra_headers")
                ? extraHeaders(object(api.get("extra_headers"), path + ".api.extra_headers", true),
                path + ".api.extra_headers", dotenv)
                : Map.of();

        JsonObject transport = object(entry.get("transport"), path + ".transport", true);
        exact(transport, Set.of("timeout_ms", "retry"), path + ".transport", true);
        long timeout = integer(transport.get("timeout_ms"), path + ".transport.timeout_ms",
                1_000, 600_000);
        JsonObject retry = object(transport.get("retry"), path + ".transport.retry", true);
        exact(retry, Set.of("max_retries", "backoff_ms"), path + ".transport.retry", true);
        int retries = (int) integer(retry.get("max_retries"), path + ".transport.retry.max_retries", 0, 10);
        long backoff = integer(retry.get("backoff_ms"), path + ".transport.retry.backoff_ms", 0, 60_000);

        JsonArray tools = array(entry.get("tools"), path + ".tools");
        HashSet<String> ids = new HashSet<>();
        java.util.ArrayList<ProviderCatalog.ProviderTool> parsedTools = new java.util.ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            String toolPath = path + ".tools[" + index + ']';
            JsonObject tool = object(tools.get(index), toolPath, true);
            exact(tool, Set.of("id", "payload"), toolPath, true);
            String toolId = string(tool.get("id"), toolPath + ".id");
            if (!ID.matcher(toolId).matches() || !ids.add(toolId)) {
                throw invalid(toolPath + ".id must be unique and match " + ID.pattern());
            }
            JsonObject payload = object(tool.get("payload"), toolPath + ".payload", false);
            parsedTools.add(new ProviderCatalog.ProviderTool(toolId, payload));
        }
        return new ProviderCatalog.Provider(id, new ProviderCatalog.Api(type, baseUrl, apiKey, extraHeaders),
                new ProviderCatalog.Transport(Duration.ofMillis(timeout), retries, Duration.ofMillis(backoff)),
                parsedTools);
    }

    private Map<String, String> extraHeaders(JsonObject configured, String path,
                                              MineclawConfig.SecretEnvironment dotenv)
            throws ConfigException {
        if (configured.size() > MAX_EXTRA_HEADERS) {
            throw invalid(path + " exceeds " + MAX_EXTRA_HEADERS + " entries");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        HashSet<String> normalizedNames = new HashSet<>();
        int totalValueCodePoints = 0;
        for (Map.Entry<String, JsonElement> entry : configured.entrySet()) {
            String name = entry.getKey();
            String headerPath = path + '.' + name;
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches()) {
                throw invalid(headerPath + " has an invalid HTTP header name");
            }
            if (!normalizedNames.add(normalized)) {
                throw invalid(headerPath + " duplicates an HTTP header name ignoring case");
            }
            if (MANAGED_HEADERS.contains(normalized)) {
                throw invalid(headerPath + " is managed by Mineclaw and cannot be overridden");
            }
            String value = expandHeaderVariables(string(entry.getValue(), headerPath), headerPath, dotenv);
            int valueCodePoints = projectedHeaderValueCodePoints(value);
            totalValueCodePoints += valueCodePoints;
            if (value.isBlank() || valueCodePoints > MAX_HEADER_VALUE_CODE_POINTS
                    || totalValueCodePoints > MAX_HEADER_VALUES_CODE_POINTS) {
                throw invalid(headerPath + " is blank or exceeds the configured header value limit");
            }
            if (value.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid(headerPath + " contains an invalid HTTP header character");
            }
            result.put(name, value);
        }
        return result;
    }

    private static int projectedHeaderValueCodePoints(String template) {
        String maximumRuntimeValue = "x".repeat(256);
        String projected = template.replace("${session_id}", maximumRuntimeValue)
                .replace("${user_agent}", maximumRuntimeValue);
        return projected.codePointCount(0, projected.length());
    }

    private String expandHeaderVariables(String configured, String path,
                                         MineclawConfig.SecretEnvironment dotenv)
            throws ConfigException {
        Matcher matcher = HEADER_VARIABLE.matcher(configured);
        StringBuilder result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            String literal = configured.substring(end, matcher.start());
            if (literal.contains("${")) {
                throw invalid(path + " contains an invalid variable reference");
            }
            result.append(literal);
            String name = matcher.group(1);
            if (!ENV_NAME.matcher(name).matches()) {
                throw invalid(path + " contains an invalid variable reference");
            }
            if (RUNTIME_HEADER_VARIABLES.contains(name)) {
                result.append(matcher.group());
            } else {
                String environmentValue = environmentValue(name, path, dotenv);
                if (environmentValue.contains("${")) {
                    throw invalid(path + " environment variable " + name
                            + " contains a nested variable reference");
                }
                result.append(environmentValue);
            }
            end = matcher.end();
        }
        String tail = configured.substring(end);
        if (tail.contains("${")) {
            throw invalid(path + " contains an invalid variable reference");
        }
        result.append(tail);
        return result.toString();
    }

    private String environmentValue(String name, String path, MineclawConfig.SecretEnvironment dotenv)
            throws ConfigException {
        String value;
        try {
            value = processEnvironment.apply(name);
        } catch (RuntimeException exception) {
            throw invalid(path + " could not access the process environment");
        }
        if (value == null) {
            value = dotenv.get(name);
        }
        if (value == null || value.isBlank()) {
            throw invalid(path + " references an absent or empty environment variable " + name);
        }
        return value.trim();
    }

    private static ProviderCatalog.Model model(Reference reference, JsonObject entry,
                                                 ProviderCatalog.Provider provider)
            throws ConfigException {
        String path = "$.models." + reference.full();
        exact(entry, Set.of("limits", "interleaved", "request"), path, false);
        JsonObject limits = object(entry.get("limits"), path + ".limits", true);
        exact(limits, Set.of("context_window_tokens", "max_output_tokens", "compact_trigger_tokens"),
                path + ".limits", false);
        for (String required : Set.of("context_window_tokens", "max_output_tokens")) {
            if (!limits.has(required)) {
                throw invalid(path + ".limits." + required + " is required");
            }
        }
        int context = (int) integer(limits.get("context_window_tokens"),
                path + ".limits.context_window_tokens", 1_024, 10_000_000);
        int output = (int) integer(limits.get("max_output_tokens"),
                path + ".limits.max_output_tokens", 1, context);
        java.util.OptionalInt compactTrigger = java.util.OptionalInt.empty();
        if (limits.has("compact_trigger_tokens")) {
            long maximum = (long) context - output;
            if (maximum < 1L) {
                throw invalid(path + ".limits.compact_trigger_tokens requires input budget space");
            }
            compactTrigger = java.util.OptionalInt.of((int) integer(
                    limits.get("compact_trigger_tokens"),
                    path + ".limits.compact_trigger_tokens", 1, maximum));
        }

        Optional<String> interleaved = Optional.empty();
        if (entry.has("interleaved")) {
            JsonObject value = object(entry.get("interleaved"), path + ".interleaved", true);
            exact(value, Set.of("field"), path + ".interleaved", true);
            String field = string(value.get("field"), path + ".interleaved.field");
            if (!field.equals("reasoning_content")
                    || provider.api().type() != ProviderCatalog.ApiType.OPENAI_CHAT_COMPLETIONS) {
                throw invalid(path + ".interleaved.field is unsupported for " + provider.api().type().wireName());
            }
            interleaved = Optional.of(field);
        }

        boolean promptCacheKey = false;
        JsonObject extraBody = new JsonObject();
        if (entry.has("request")) {
            JsonObject request = object(entry.get("request"), path + ".request", true);
            exact(request, Set.of("prompt_cache_key", "extra_body"), path + ".request", false);
            if (request.has("prompt_cache_key")) {
                promptCacheKey = bool(request.get("prompt_cache_key"),
                        path + ".request.prompt_cache_key");
            }
            if (request.has("extra_body")) {
                extraBody = object(request.get("extra_body"),
                        path + ".request.extra_body", true).deepCopy();
            }
            for (String reserved : RESERVED) {
                if (extraBody.has(reserved)) {
                    throw invalid(path + ".request.extra_body." + reserved + " is reserved by Mineclaw");
                }
            }
            validateBudget(extraBody, path + ".request.extra_body", 0, new int[]{0, 0});
            if (provider.id().equals("mimo") && extraBody.has("thinking")) {
                JsonObject thinking = object(extraBody.get("thinking"),
                        path + ".request.extra_body.thinking", true);
                exact(thinking, Set.of("type"), path + ".request.extra_body.thinking", true);
                String thinkingType = string(thinking.get("type"),
                        path + ".request.extra_body.thinking.type");
                if (!Set.of("enabled", "disabled").contains(thinkingType)) {
                    throw invalid(path + ".request.extra_body.thinking.type must be enabled or disabled");
                }
            }
        }
        return new ProviderCatalog.Model(reference.full(), reference.providerId(), reference.modelId(),
                new ProviderCatalog.Limits(context, output, compactTrigger), promptCacheKey,
                interleaved, extraBody);
    }

    private String credential(String configured, String path, MineclawConfig.SecretEnvironment dotenv)
            throws ConfigException {
        Matcher matcher = ENV.matcher(configured);
        if (!matcher.matches()) {
            if (configured.isBlank()) {
                throw invalid(path + " must not be blank");
            }
            return configured;
        }
        String name = matcher.group(1);
        return environmentValue(name, path, dotenv);
    }

    private static Reference reference(String value, String path) throws ConfigException {
        if (value.length() > 320) {
            throw invalid(path + " exceeds 320 characters");
        }
        int slash = value.indexOf('/');
        if (slash < 1 || slash == value.length() - 1) {
            throw invalid(path + " must be a complete provider/model reference");
        }
        String provider = value.substring(0, slash);
        String model = value.substring(slash + 1);
        int modelLength = model.codePointCount(0, model.length());
        if (!ID.matcher(provider).matches() || modelLength < 1 || modelLength > 256
                || model.startsWith("/") || model.endsWith("/") || model.indexOf('\\') >= 0
                || model.codePoints().anyMatch(valuePoint -> Character.isWhitespace(valuePoint)
                || Character.isISOControl(valuePoint))) {
            throw invalid(path + " is not a valid model reference");
        }
        return new Reference(value, provider, model);
    }

    private static URI baseUrl(String source, String path) throws ConfigException {
        String normalized = source;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || normalized.endsWith("/chat/completions") || normalized.endsWith("/responses")) {
                throw invalid(path
                        + " must be an HTTP(S) API base URL without /chat/completions or /responses");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalid(path + " must be a valid absolute HTTP(S) URI");
        }
    }

    private static void validateBudget(JsonElement value, String path, int depth, int[] counts)
            throws ConfigException {
        if (depth > 32 || ++counts[0] > 4_096) {
            throw invalid(path + " exceeds the configured JSON structure limit");
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            counts[1] += value.getAsString().codePointCount(0, value.getAsString().length());
            if (counts[1] > 65_536) {
                throw invalid(path + " exceeds the configured string limit");
            }
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) {
                validateBudget(child, path + "[]", depth + 1, counts);
            }
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> child : value.getAsJsonObject().entrySet()) {
                validateBudget(child.getValue(), path + '.' + child.getKey(), depth + 1, counts);
            }
        }
    }

    private static void exact(JsonObject object, Set<String> allowed, String path, boolean allRequired)
            throws ConfigException {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid(path + '.' + field + " is not supported");
            }
        }
        if (allRequired) {
            for (String field : allowed) {
                if (!object.has(field)) {
                    throw invalid(path + '.' + field + " is required");
                }
            }
        }
    }

    private static JsonObject object(JsonElement value, String path, boolean allowEmpty)
            throws ConfigException {
        if (value == null || !value.isJsonObject() || !allowEmpty && value.getAsJsonObject().isEmpty()) {
            throw invalid(path + " must be " + (allowEmpty ? "a mapping" : "a non-empty mapping"));
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String path) throws ConfigException {
        if (value == null || !value.isJsonArray()) {
            throw invalid(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonElement value, String path) throws ConfigException {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw invalid(path + " must be a string");
        }
        return primitive.getAsString();
    }

    private static boolean bool(JsonElement value, String path) throws ConfigException {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw invalid(path + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static long integer(JsonElement value, String path, long minimum, long maximum)
            throws ConfigException {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw invalid(path + " must be an integer");
        }
        try {
            long result = new BigDecimal(primitive.getAsString()).longValueExact();
            if (result < minimum || result > maximum) {
                throw invalid(path + " must be in " + minimum + ".." + maximum);
            }
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(path + " must be an integer");
        }
    }

    private static ConfigException invalid(String message) {
        return new ConfigException(message);
    }

    private record Reference(String full, String providerId, String modelId) { }
}
