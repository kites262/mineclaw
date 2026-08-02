package cc.kites.mineclaw.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Strict schema-1 config.yml parser backed by Bukkit's {@link YamlConfiguration}. */
public final class ConfigLoader {
    private final Function<String, String> processEnvironment;

    public ConfigLoader() {
        this(System::getenv);
    }

    public ConfigLoader(Function<String, String> processEnvironment) {
        this.processEnvironment = Objects.requireNonNull(processEnvironment, "processEnvironment");
    }

    public MineclawConfig load(Path path) throws ConfigException {
        Objects.requireNonNull(path, "path");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (InvalidConfigurationException exception) {
            // SnakeYAML diagnostics may embed the offending source line, including api.api_key.
            throw new ConfigException(path.getFileName() + " contains invalid YAML");
        } catch (IOException exception) {
            throw new ConfigException("Cannot read " + path.getFileName(), exception);
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path dotEnv = parent == null ? Path.of(".env").toAbsolutePath().normalize() : parent.resolve(".env");
        return parse(yaml, new ReferenceResolver(processEnvironment, DotEnvLoader.load(dotEnv)));
    }

    public MineclawConfig parse(String yamlText) throws ConfigException {
        Objects.requireNonNull(yamlText, "yamlText");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        } catch (InvalidConfigurationException exception) {
            // Do not retain a cause whose message may include the secret-bearing source line.
            throw new ConfigException("config.yml contains invalid YAML");
        }
        return parse(yaml);
    }

    public MineclawConfig parse(ConfigurationSection yaml) throws ConfigException {
        return parse(yaml, ReferenceResolver.literalOnly());
    }

    private MineclawConfig parse(ConfigurationSection yaml, ReferenceResolver references) throws ConfigException {
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(references, "references");
        validateSectionShapes(yaml);
        MineclawConfig defaults = MineclawConfig.defaults();

        int schema = integer(yaml, "schema", defaults.schema());
        if (schema != MineclawConfig.SCHEMA) {
            throw invalid("schema", "must be " + MineclawConfig.SCHEMA);
        }

        MineclawConfig.Api apiDefaults = defaults.api();
        String baseUrlText = references.resolve(
                string(yaml, "api.base_url", apiDefaults.baseUrl().toString()), "api.base_url");
        URI baseUrl = uri(nonBlank(baseUrlText, "api.base_url"), "api.base_url");
        String configuredApiKey = string(yaml, "api.api_key", apiDefaults.apiKey()).trim();
        String apiKeyEnv = string(yaml, "api.api_key_env", apiDefaults.apiKeyEnv()).trim();
        if (!apiKeyEnv.isEmpty() && !apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw invalid("api.api_key_env", "must be a valid environment variable name or empty");
        }
        String apiKey = configuredApiKey.isEmpty()
                ? references.resolveLegacy(apiKeyEnv, "api.api_key_env")
                : references.resolve(configuredApiKey, "api.api_key");
        String model = model(references.resolve(
                string(yaml, "api.model", apiDefaults.model()), "api.model"));
        long timeout = boundedPositiveLong(
                yaml, "api.timeout", apiDefaults.timeoutMillis(), 86_400_000L);
        int maxRetries = boundedNonNegativeInt(
                yaml, "api.max_retries", apiDefaults.maxRetries(), 20);
        long retryBackoff = boundedPositiveLong(
                yaml, "api.retry_backoff_ms", apiDefaults.retryBackoffMillis(), 3_600_000L);
        MineclawConfig.Api api = new MineclawConfig.Api(
                baseUrl, apiKey, apiKeyEnv, model, timeout, maxRetries, retryBackoff);

        MineclawConfig.Context contextDefaults = defaults.context();
        MineclawConfig.Context context = new MineclawConfig.Context(
                positiveInt(yaml, "context.max_messages", contextDefaults.maxMessages()),
                positiveInt(yaml, "context.max_tokens", contextDefaults.maxTokens()));

        MineclawConfig.Chat chatDefaults = defaults.chat();
        String publicPrefix = string(yaml, "chat.public_prefix", chatDefaults.publicPrefix());
        String rawWakePattern = string(yaml, "chat.wake_pattern", "").trim();
        Optional<Pattern> wakePattern = rawWakePattern.isEmpty()
                ? Optional.empty()
                : Optional.of(pattern(rawWakePattern, "chat.wake_pattern"));
        if (publicPrefix.isBlank() && wakePattern.isEmpty()) {
            throw invalid("chat", "public_prefix and wake_pattern cannot both be empty");
        }
        MineclawConfig.Chat chat = new MineclawConfig.Chat(
                publicPrefix,
                wakePattern,
                positiveInt(yaml, "chat.reply_max_chars", chatDefaults.replyMaxChars()),
                positiveInt(yaml, "chat.actionbar_max_chars", chatDefaults.actionbarMaxChars()));

        MineclawConfig.Tools toolsDefaults = defaults.tools();
        MineclawConfig.Tools tools = new MineclawConfig.Tools(
                bool(yaml, "tools.enabled", toolsDefaults.enabled()),
                normalizedNames(strings(yaml, "tools.disabled", List.copyOf(toolsDefaults.disabled())),
                        "tools.disabled"));

        MineclawConfig.Commands commandDefaults = defaults.commands();
        MineclawConfig.Commands commands = new MineclawConfig.Commands(
                bool(yaml, "commands.run_enabled", commandDefaults.runEnabled()),
                patterns(strings(yaml, "commands.player_whitelist", patternSources(commandDefaults.playerWhitelist())),
                        "commands.player_whitelist"),
                patterns(strings(yaml, "commands.console_whitelist", patternSources(commandDefaults.consoleWhitelist())),
                        "commands.console_whitelist"));

        MineclawConfig.RateLimit rateDefaults = defaults.rateLimit();
        MineclawConfig.RateLimit rateLimit = new MineclawConfig.RateLimit(
                nonNegativeLong(yaml, "rate_limit.player_cooldown_ms", rateDefaults.playerCooldownMillis()),
                nonNegativeLong(yaml, "rate_limit.global_cooldown_ms", rateDefaults.globalCooldownMillis()));

        MineclawConfig.Workspace workspaceDefaults = defaults.workspace();
        MineclawConfig.Workspace workspace = new MineclawConfig.Workspace(
                bool(yaml, "workspace.seed_defaults", workspaceDefaults.seedDefaults()),
                new MineclawConfig.Workspace.MaxChars(boundedPositiveInt(
                        yaml, "workspace.max_chars.agents", workspaceDefaults.maxChars().agents(), 1_000_000)));

        MineclawConfig.FileTools fileDefaults = defaults.fileTools();
        MineclawConfig.FileTools fileTools = new MineclawConfig.FileTools(
                positiveInt(yaml, "file_tools.max_read_chars", fileDefaults.maxReadChars()),
                positiveInt(yaml, "file_tools.max_results", fileDefaults.maxResults()),
                nonNegativeInt(yaml, "file_tools.max_depth", fileDefaults.maxDepth()),
                positiveLong(yaml, "file_tools.timeout", fileDefaults.timeoutMillis()));

        MineclawConfig.Turn turnDefaults = defaults.turn();
        MineclawConfig.Turn turn = new MineclawConfig.Turn(
                positiveInt(yaml, "turn.max_tool_rounds", turnDefaults.maxToolRounds()),
                positiveInt(yaml, "turn.max_tool_calls", turnDefaults.maxToolCalls()));

        MineclawConfig.Identity identity = new MineclawConfig.Identity(
                string(yaml, "identity.name", defaults.identity().name()));

        MineclawConfig.Environment environmentDefaults = defaults.environment();
        MineclawConfig.Environment environment = new MineclawConfig.Environment(
                positiveInt(yaml, "environment.look_distance", environmentDefaults.lookDistance()),
                nonNegativeLong(yaml, "environment.tool_cooldown_ms", environmentDefaults.toolCooldownMillis()),
                new MineclawConfig.Environment.Inventory(
                        bool(yaml, "environment.inventory.include_equipment",
                                environmentDefaults.inventory().includeEquipment()),
                        positiveInt(yaml, "environment.inventory.max_slots",
                                environmentDefaults.inventory().maxSlots())));

        String rawLevel = nonBlank(string(yaml, "logging.level", defaults.logging().configuredName()),
                "logging.level");
        Level level;
        try {
            level = Level.parse(rawLevel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("logging.level", "unknown java.util.logging level " + rawLevel, exception);
        }

        return new MineclawConfig(schema, api, context, chat, tools, commands, rateLimit, workspace,
                fileTools, turn, identity, environment, new MineclawConfig.Logging(level));
    }

    private static URI uri(String raw, String path) throws ConfigException {
        URI value;
        try {
            value = new URI(raw);
        } catch (URISyntaxException exception) {
            throw invalid(path, "must be a valid absolute HTTP(S) URI");
        }
        String scheme = value.getScheme();
        if (!value.isAbsolute() || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null) {
            throw invalid(path, "must be an absolute HTTP(S) URI");
        }
        return value;
    }

    private static String model(String raw) throws ConfigException {
        String value = nonBlank(raw, "api.model");
        if (value.length() > 256) {
            throw invalid("api.model", "must not exceed 256 characters");
        }
        if (value.codePoints().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character))) {
            throw invalid("api.model", "must not contain whitespace or control characters");
        }
        return value;
    }

    private record ReferenceResolver(Function<String, String> processEnvironment,
                                     MineclawConfig.SecretEnvironment fileEnvironment) {
        private ReferenceResolver {
            Objects.requireNonNull(processEnvironment, "processEnvironment");
            Objects.requireNonNull(fileEnvironment, "fileEnvironment");
        }

        private static ReferenceResolver literalOnly() {
            return new ReferenceResolver(name -> null, MineclawConfig.SecretEnvironment.empty());
        }

        private String resolve(String configured, String path) throws ConfigException {
            String candidate = configured.trim();
            if (!MineclawConfig.SecretEnvironment.validName(candidate)) {
                return candidate;
            }
            String processValue = process(candidate, path);
            if (processValue != null) {
                return processValue.trim();
            }
            String fileValue = fileEnvironment.get(candidate);
            return fileValue == null ? candidate : fileValue.trim();
        }

        private String resolveLegacy(String variableName, String path) throws ConfigException {
            if (variableName.isEmpty()) {
                return "";
            }
            String processValue = process(variableName, path);
            if (processValue != null) {
                return processValue.trim();
            }
            String fileValue = fileEnvironment.get(variableName);
            return fileValue == null ? "" : fileValue.trim();
        }

        private String process(String variableName, String path) throws ConfigException {
            try {
                return processEnvironment.apply(variableName);
            } catch (RuntimeException exception) {
                throw invalid(path, "cannot access the process environment");
            }
        }
    }

    private static void validateSectionShapes(ConfigurationSection yaml) throws ConfigException {
        for (String path : List.of("api", "context", "chat", "tools", "commands", "rate_limit",
                "workspace", "workspace.max_chars", "file_tools", "turn", "identity", "environment",
                "environment.inventory", "logging")) {
            Object value = yaml.get(path);
            if (value != null && !(value instanceof ConfigurationSection)) {
                throw invalid(path, "must be a mapping");
            }
        }
    }

    private static Set<String> normalizedNames(List<String> values, String path) throws ConfigException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index).trim();
            if (value.isEmpty()) {
                throw invalid(path + '[' + index + ']', "must not be blank");
            }
            result.add(value);
        }
        return result;
    }

    private static List<Pattern> patterns(List<String> values, String path) throws ConfigException {
        ArrayList<Pattern> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(pattern(values.get(index), path + '[' + index + ']'));
        }
        return List.copyOf(result);
    }

    private static Pattern pattern(String value, String path) throws ConfigException {
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException exception) {
            throw invalid(path, "invalid regular expression: " + exception.getDescription(), exception);
        }
    }

    private static List<String> patternSources(List<Pattern> patterns) {
        return patterns.stream().map(Pattern::pattern).toList();
    }

    private static String nonBlank(String value, String path) throws ConfigException {
        String result = value.trim();
        if (result.isEmpty()) {
            throw invalid(path, "must not be blank");
        }
        return result;
    }

    private static boolean bool(ConfigurationSection yaml, String path, boolean defaultValue)
            throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalid(path, "must be a boolean");
    }

    private static String string(ConfigurationSection yaml, String path, String defaultValue)
            throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String string) {
            return string;
        }
        throw invalid(path, "must be a string");
    }

    private static List<String> strings(ConfigurationSection yaml, String path, List<String> defaultValue)
            throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return List.copyOf(defaultValue);
        }
        if (!(value instanceof List<?> list)) {
            throw invalid(path, "must be a list of strings");
        }
        ArrayList<String> result = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object entry = list.get(index);
            if (!(entry instanceof String string)) {
                throw invalid(path + '[' + index + ']', "must be a string");
            }
            result.add(string);
        }
        return List.copyOf(result);
    }

    private static int integer(ConfigurationSection yaml, String path, int defaultValue) throws ConfigException {
        long value = integral(yaml, path, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(path, "is outside the integer range");
        }
        return (int) value;
    }

    private static int positiveInt(ConfigurationSection yaml, String path, int defaultValue) throws ConfigException {
        int value = integer(yaml, path, defaultValue);
        if (value <= 0) {
            throw invalid(path, "must be greater than zero");
        }
        return value;
    }

    private static int nonNegativeInt(ConfigurationSection yaml, String path, int defaultValue)
            throws ConfigException {
        int value = integer(yaml, path, defaultValue);
        if (value < 0) {
            throw invalid(path, "must not be negative");
        }
        return value;
    }

    private static int boundedPositiveInt(
            ConfigurationSection yaml, String path, int defaultValue, int maximum) throws ConfigException {
        int value = positiveInt(yaml, path, defaultValue);
        if (value > maximum) {
            throw invalid(path, "must not exceed " + maximum);
        }
        return value;
    }

    private static int boundedNonNegativeInt(
            ConfigurationSection yaml, String path, int defaultValue, int maximum) throws ConfigException {
        int value = nonNegativeInt(yaml, path, defaultValue);
        if (value > maximum) {
            throw invalid(path, "must not exceed " + maximum);
        }
        return value;
    }

    private static long positiveLong(ConfigurationSection yaml, String path, long defaultValue)
            throws ConfigException {
        long value = integral(yaml, path, defaultValue);
        if (value <= 0) {
            throw invalid(path, "must be greater than zero");
        }
        return value;
    }

    private static long nonNegativeLong(ConfigurationSection yaml, String path, long defaultValue)
            throws ConfigException {
        long value = integral(yaml, path, defaultValue);
        if (value < 0) {
            throw invalid(path, "must not be negative");
        }
        return value;
    }

    private static long boundedPositiveLong(
            ConfigurationSection yaml, String path, long defaultValue, long maximum) throws ConfigException {
        long value = positiveLong(yaml, path, defaultValue);
        if (value > maximum) {
            throw invalid(path, "must not exceed " + maximum);
        }
        return value;
    }

    private static long integral(ConfigurationSection yaml, String path, long defaultValue) throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw invalid(path, "must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(path, "must be an integer in the 64-bit range", exception);
        }
    }

    private static ConfigException invalid(String path, String message) {
        return new ConfigException(path + ' ' + message);
    }

    private static ConfigException invalid(String path, String message, Throwable cause) {
        return new ConfigException(path + ' ' + message, cause);
    }
}
