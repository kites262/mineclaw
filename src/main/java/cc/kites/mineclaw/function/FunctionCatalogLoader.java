package cc.kites.mineclaw.function;

import cc.kites.mineclaw.javascript.PreparedScript;
import cc.kites.mineclaw.javascript.ScriptDiagnostic;
import cc.kites.mineclaw.javascript.SourceValidation;
import cc.kites.mineclaw.schema.CompiledSchema;
import cc.kites.mineclaw.schema.SchemaCompilationException;
import cc.kites.mineclaw.schema.SchemaCompiler;
import cc.kites.mineclaw.schema.SchemaLimits;
import cc.kites.mineclaw.workspace.ToolCatalog;
import cc.kites.mineclaw.workspace.ToolDefinition;
import cc.kites.mineclaw.workspace.WorkspacePathSecurity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Strict, safe functions.yml loader with entry isolation and immutable catalog generations. */
public final class FunctionCatalogLoader {
    public static final String FUNCTIONS_FILE_NAME = "functions.yml";
    public static final int SCHEMA_VERSION = 1;
    public static final int API_VERSION = 1;

    private static final Set<String> ROOT_FIELDS = Set.of("schema", "api_version", "functions");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "name", "description", "enabled", "capabilities", "parameters", "on_call");
    private static final Set<String> BASE_CAPABILITIES = Set.of(
            "approval.request", "command.dispatch.console", "command.dispatch.player");
    private static final String NATIVE_CALL_PREFIX = "native_tool.call.";
    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_NAME_CHARS = 96;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 512;
    private static final int MAX_SAFE_DIAGNOSTIC_CHARS = 512;
    private static final int MAX_YAML_DEPTH = 64;

    private final Consumer<String> warningSink;
    private final FunctionSourcePreparer sourcePreparer;
    private final Set<String> defaultNativeToolNames;
    private volatile Limits limits;
    private final AtomicLong generation = new AtomicLong();

    public FunctionCatalogLoader() {
        this(ignored -> { }, FunctionSourcePreparer.unavailable(), Set.of(), Limits.defaults());
    }

    public FunctionCatalogLoader(Consumer<String> warningSink, FunctionSourcePreparer sourcePreparer) {
        this(warningSink, sourcePreparer, Set.of(), Limits.defaults());
    }

    public FunctionCatalogLoader(
            Consumer<String> warningSink,
            FunctionSourcePreparer sourcePreparer,
            Set<String> nativeToolNames,
            Limits limits
    ) {
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.sourcePreparer = Objects.requireNonNull(sourcePreparer, "sourcePreparer");
        this.defaultNativeToolNames = immutableNativeToolNames(nativeToolNames);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Limits limits() {
        return limits;
    }

    /** Atomically switches limits between catalog loads; in-progress loads retain one complete snapshot. */
    public synchronized void reconfigure(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Allocates a generation-scoped empty snapshot after an external read failure. */
    public synchronized FunctionCatalog emptySnapshot(String diagnostic) {
        return FunctionCatalog.empty(nextGeneration(), diagnostic);
    }

    /** Missing functions.yml is an intentionally empty catalog; unsafe paths still fail closed. */
    public synchronized FunctionCatalog load(Path dataRoot, Path path) throws IOException {
        return load(dataRoot, path, defaultNativeToolNames);
    }

    /** Loads against the exact native Tool names registered in this immutable ToolCatalog snapshot. */
    public synchronized FunctionCatalog load(
            Path dataRoot,
            Path path,
            Set<String> nativeToolNames
    ) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(path, "path");
        Set<String> nativeSnapshot = immutableNativeToolNames(nativeToolNames);
        Path root = dataRoot.toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.equals(root.resolve(FUNCTIONS_FILE_NAME))) {
            throw new IOException("unsafe functions.yml path");
        }
        if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return FunctionCatalog.empty(nextGeneration(), null);
        }
        Path safePath = new WorkspacePathSecurity(root)
                .requireFixedReadable(candidate, FUNCTIONS_FILE_NAME);
        final String contents;
        try {
            contents = readStrictUtf8(safePath);
        } catch (CharacterCodingException exception) {
            return rootInvalid("functions.yml is not valid UTF-8");
        } catch (FileLimitException exception) {
            return rootInvalid("functions.yml exceeds functions.max_file_chars");
        }
        return parse(contents, nativeSnapshot);
    }

    public synchronized FunctionCatalog parse(String contents) {
        return parse(contents, defaultNativeToolNames);
    }

    /** Parses against a caller-supplied per-snapshot native Tool-name allowlist. */
    public synchronized FunctionCatalog parse(String contents, Set<String> nativeToolNames) {
        Objects.requireNonNull(contents, "contents");
        Set<String> nativeSnapshot = immutableNativeToolNames(nativeToolNames);
        if (hasUnpairedSurrogate(contents)) {
            return rootInvalid("functions.yml is not valid UTF-8 text");
        }
        if (codePoints(contents) > limits.maxFileChars()) {
            return rootInvalid("functions.yml exceeds functions.max_file_chars");
        }

        final Object rawRoot;
        try {
            LoaderOptions options = yamlOptions(contents);
            Yaml composer = new Yaml(new SafeConstructor(options));
            Node document = composer.compose(new java.io.StringReader(contents));
            Optional<String> graphError = validateYamlGraph(document, new IdentityHashMap<>(), 0);
            if (graphError.isPresent()) {
                return rootInvalid(graphError.orElseThrow());
            }
            rawRoot = new Yaml(new SafeConstructor(yamlOptions(contents))).load(contents);
        } catch (RuntimeException exception) {
            return rootInvalid("functions.yml is invalid YAML");
        }
        if (!(rawRoot instanceof Map<?, ?> rootMap)) {
            return rootInvalid("functions.yml root must be a mapping");
        }
        if (!stringKeys(rootMap).equals(ROOT_FIELDS)) {
            return rootInvalid("functions.yml root must contain only schema, api_version, and functions");
        }
        if (!isIntegerOne(rootMap.get("schema"))) {
            return rootInvalid("functions.yml schema must be integer 1");
        }
        if (!isIntegerOne(rootMap.get("api_version"))) {
            return rootInvalid("functions.yml api_version must be integer 1");
        }
        if (!(rootMap.get("functions") instanceof List<?> entries)) {
            return rootInvalid("functions.yml functions must be an array");
        }
        if (entries.size() > limits.maxEntries()) {
            return rootInvalid("functions.yml exceeds functions.max_entries");
        }

        ArrayList<FunctionDefinition> definitions = new ArrayList<>(entries.size());
        for (int zeroBased = 0; zeroBased < entries.size(); zeroBased++) {
            definitions.add(parseEntry(entries.get(zeroBased), zeroBased + 1, nativeSnapshot));
        }
        markAllDuplicatesInvalid(definitions);

        ArrayList<String> diagnostics = new ArrayList<>();
        for (FunctionDefinition definition : definitions) {
            if (definition.status() == FunctionDefinition.Status.INVALID) {
                String message = "functions.yml entry #" + definition.index() + ": "
                        + definition.diagnostic().orElseThrow();
                diagnostics.add(message);
                warn(message);
            }
        }
        return new FunctionCatalog(nextGeneration(), definitions, diagnostics);
    }

    private FunctionDefinition parseEntry(Object raw, int index, Set<String> nativeToolNames) {
        String earlyName = "";
        String earlyDescription = "";
        boolean earlyEnabled = false;
        if (raw instanceof Map<?, ?> map) {
            earlyName = map.get("name") instanceof String value ? value : "";
            earlyDescription = map.get("description") instanceof String value ? value : "";
            earlyEnabled = map.get("enabled") instanceof Boolean value && value;
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return invalid(index, earlyName, earlyDescription, earlyEnabled, "entry must be a mapping");
        }
        Optional<String> jsonError = validateJsonTree(raw, "$[" + (index - 1) + ']', 0,
                new IdentityHashMap<>());
        if (jsonError.isPresent()) {
            return invalid(index, earlyName, earlyDescription, earlyEnabled, jsonError.orElseThrow());
        }
        if (!stringKeys(rawMap).equals(ENTRY_FIELDS)) {
            return invalid(index, earlyName, earlyDescription, earlyEnabled,
                    "entry must contain only name, description, enabled, capabilities, parameters, and on_call");
        }
        JsonObject object;
        try {
            object = toJson(rawMap).getAsJsonObject();
        } catch (RuntimeException exception) {
            return invalid(index, earlyName, earlyDescription, earlyEnabled,
                    "entry cannot be converted to JSON");
        }

        JsonElement rawName = object.get("name");
        if (!isString(rawName)) {
            return invalid(index, "", earlyDescription, earlyEnabled, "name must be a string");
        }
        String name = rawName.getAsString();
        if (codePoints(name) < 1 || codePoints(name) > MAX_NAME_CHARS
                || !FUNCTION_NAME.matcher(name).matches()) {
            return invalid(index, name, earlyDescription, earlyEnabled,
                    "name must match " + FUNCTION_NAME.pattern() + " and contain 1-96 ASCII characters");
        }

        JsonElement rawDescription = object.get("description");
        if (!isString(rawDescription)) {
            return invalid(index, name, "", earlyEnabled, "description must be a string");
        }
        String description = rawDescription.getAsString();
        int descriptionChars = codePoints(description);
        if (description.isBlank()
                || descriptionChars > Math.min(MAX_DESCRIPTION_CODE_POINTS, limits.maxDescriptionChars())) {
            return invalid(index, name, description, earlyEnabled,
                    "description must be non-blank and not exceed functions.max_description_chars");
        }

        JsonElement rawEnabled = object.get("enabled");
        if (rawEnabled == null || !rawEnabled.isJsonPrimitive()
                || !rawEnabled.getAsJsonPrimitive().isBoolean()) {
            return invalid(index, name, description, false, "enabled must be a boolean");
        }
        boolean enabled = rawEnabled.getAsBoolean();

        JsonElement rawParameters = object.get("parameters");
        if (rawParameters == null || !rawParameters.isJsonObject()) {
            return invalid(index, name, description, enabled, "parameters must be a Schema object");
        }
        final CompiledSchema compiled;
        try {
            compiled = SchemaCompiler.compile(rawParameters.getAsJsonObject(), limits.schemaLimits());
        } catch (SchemaCompilationException exception) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    "invalid parameters Schema: " + safeDiagnostic(exception.getMessage()),
                    Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                    API_VERSION);
        }

        CapabilityResult capabilityResult = parseCapabilities(object.get("capabilities"), nativeToolNames);
        if (capabilityResult.diagnostic != null) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    capabilityResult.diagnostic, Optional.of(compiled), List.of(), Optional.empty(),
                    Optional.empty(), API_VERSION);
        }

        JsonElement rawSource = object.get("on_call");
        if (!isString(rawSource)) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    "on_call must be a string", Optional.of(compiled), capabilityResult.capabilities,
                    Optional.empty(), Optional.empty(), API_VERSION);
        }
        String source = rawSource.getAsString();
        if (source.isBlank()) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    "on_call must not be blank", Optional.of(compiled), capabilityResult.capabilities,
                    Optional.empty(), Optional.empty(), API_VERSION);
        }
        String hash = sha256(source);
        if (codePoints(source) > limits.maxSourceChars()) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    "on_call exceeds javascript.max_source_chars", Optional.of(compiled),
                    capabilityResult.capabilities, Optional.empty(), Optional.of(hash), API_VERSION);
        }

        final SourceValidation validation;
        try {
            validation = Objects.requireNonNull(sourcePreparer.prepare(name, API_VERSION, source),
                    "source preparation result");
        } catch (RuntimeException | LinkageError exception) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    "javascript source preparation failed", Optional.of(compiled),
                    capabilityResult.capabilities, Optional.empty(), Optional.of(hash), API_VERSION);
        }
        if (!validation.valid()) {
            ScriptDiagnostic diagnostic = validation.diagnostic().orElseThrow();
            return FunctionDefinition.invalid(index, name, description, enabled,
                    safeDiagnostic(diagnostic.code() + ": " + diagnostic.message()),
                    Optional.of(compiled), capabilityResult.capabilities, Optional.empty(),
                    Optional.of(hash), API_VERSION);
        }
        PreparedScript prepared = validation.script().orElseThrow();
        if (!prepared.functionName().equals(name) || prepared.apiVersion() != API_VERSION
                || !prepared.scriptHash().equals(hash)) {
            return FunctionDefinition.invalid(index, name, description, enabled,
                    "prepared JavaScript identity does not match functions.yml", Optional.of(compiled),
                    capabilityResult.capabilities, Optional.empty(), Optional.of(hash), API_VERSION);
        }
        return FunctionDefinition.valid(index, name, description, enabled, compiled,
                capabilityResult.capabilities, prepared, hash, API_VERSION);
    }

    private static CapabilityResult parseCapabilities(JsonElement raw, Set<String> nativeToolNames) {
        if (raw == null || !raw.isJsonArray()) {
            return CapabilityResult.invalid("capabilities must be an array of strings");
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!isString(element)) {
                return CapabilityResult.invalid("capabilities must be an array of strings");
            }
            String capability = element.getAsString();
            if (!parsed.add(capability)) {
                return CapabilityResult.invalid("duplicate capability " + capability);
            }
            if (BASE_CAPABILITIES.contains(capability)) {
                continue;
            }
            if (capability.startsWith(NATIVE_CALL_PREFIX)) {
                String nativeName = capability.substring(NATIVE_CALL_PREFIX.length());
                if (!nativeName.equals("call_function") && nativeToolNames.contains(nativeName)) {
                    continue;
                }
            }
            return CapabilityResult.invalid("unknown capability " + capability);
        }
        return CapabilityResult.valid(List.copyOf(parsed));
    }

    /**
     * Returns exact declared native Tool names eligible for Function capabilities. Disabled but valid
     * entries remain registered; invalid entries and every alias of call_function are excluded.
     */
    public static Set<String> nativeCapabilityAllowlist(ToolCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return catalog.definitions().stream()
                .filter(definition -> definition.status() != ToolDefinition.Status.INVALID)
                .filter(definition -> definition.registeredHandler().orElse(null)
                        != ToolDefinition.Handler.CALL_FUNCTION)
                .map(ToolDefinition::handler)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> immutableNativeToolNames(Set<String> nativeToolNames) {
        Set<String> snapshot = Set.copyOf(Objects.requireNonNull(nativeToolNames, "nativeToolNames"));
        snapshot.forEach(name -> Objects.requireNonNull(name, "native tool name"));
        return snapshot;
    }

    private static void markAllDuplicatesInvalid(ArrayList<FunctionDefinition> definitions) {
        HashMap<String, Integer> counts = new HashMap<>();
        definitions.stream()
                .map(FunctionDefinition::name)
                .filter(name -> !name.isBlank() && FUNCTION_NAME.matcher(name).matches())
                .forEach(name -> counts.merge(name, 1, Integer::sum));
        for (int index = 0; index < definitions.size(); index++) {
            FunctionDefinition definition = definitions.get(index);
            if (counts.getOrDefault(definition.name(), 0) > 1) {
                definitions.set(index, definition.duplicateInvalid(
                        "duplicate function name " + definition.name()));
            }
        }
    }

    private FunctionDefinition invalid(
            int index,
            String name,
            String description,
            boolean enabled,
            String diagnostic
    ) {
        return FunctionDefinition.invalid(index, name, description, enabled, diagnostic,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), API_VERSION);
    }

    private FunctionCatalog rootInvalid(String diagnostic) {
        String safe = safeDiagnostic(diagnostic);
        warn(safe);
        return FunctionCatalog.empty(nextGeneration(), safe);
    }

    private long nextGeneration() {
        long value = generation.incrementAndGet();
        if (value < 1L) {
            throw new IllegalStateException("FunctionCatalog generation exhausted");
        }
        return value;
    }

    private String readStrictUtf8(Path path) throws IOException, FileLimitException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             Reader reader = Channels.newReader(channel, decoder, -1)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4 * 1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
                if (result.length() > (long) limits.maxFileChars() * 2L + 1L) {
                    throw new FileLimitException();
                }
            }
            if (codePoints(result) > limits.maxFileChars()) {
                throw new FileLimitException();
            }
            return result.toString();
        }
    }

    private static LoaderOptions yamlOptions(String contents) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_YAML_DEPTH);
        options.setCodePointLimit(Math.max(1_024, codePoints(contents) + 1));
        return options;
    }

    private static Optional<String> validateYamlGraph(
            Node node,
            IdentityHashMap<Node, Boolean> seen,
            int depth
    ) {
        if (node == null) {
            return Optional.empty();
        }
        if (depth > MAX_YAML_DEPTH) {
            return Optional.of("functions.yml exceeds maximum YAML depth");
        }
        if (node.getAnchor() != null) {
            return Optional.of("functions.yml anchors and aliases are not allowed");
        }
        if (seen.put(node, Boolean.TRUE) != null) {
            return Optional.of("functions.yml anchors and aliases are not allowed");
        }
        if (node.getTag().isCustomGlobal()) {
            return Optional.of("functions.yml custom tags are not allowed");
        }
        if (node instanceof MappingNode mapping) {
            if (mapping.isMerged()) {
                return Optional.of("functions.yml merge keys are not allowed");
            }
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode().getTag().equals(Tag.MERGE)
                        || tuple.getKeyNode() instanceof ScalarNode scalar
                        && scalar.getValue().equals("<<")) {
                    return Optional.of("functions.yml merge keys are not allowed");
                }
                Optional<String> keyError = validateYamlGraph(tuple.getKeyNode(), seen, depth + 1);
                if (keyError.isPresent()) {
                    return keyError;
                }
                Optional<String> valueError = validateYamlGraph(tuple.getValueNode(), seen, depth + 1);
                if (valueError.isPresent()) {
                    return valueError;
                }
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node member : sequence.getValue()) {
                Optional<String> error = validateYamlGraph(member, seen, depth + 1);
                if (error.isPresent()) {
                    return error;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateJsonTree(
            Object value,
            String path,
            int depth,
            IdentityHashMap<Object, Boolean> ancestors
    ) {
        if (depth > MAX_YAML_DEPTH) {
            return Optional.of(path + " exceeds maximum JSON depth");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return Optional.empty();
        }
        if (value instanceof Float floatValue && Float.isFinite(floatValue)
                || value instanceof Double doubleValue && Double.isFinite(doubleValue)) {
            return Optional.empty();
        }
        if (!(value instanceof Map<?, ?> || value instanceof List<?>)) {
            return Optional.of(path + " contains a non-JSON YAML value");
        }
        if (ancestors.put(value, Boolean.TRUE) != null) {
            return Optional.of(path + " contains a shared or recursive YAML value");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        return Optional.of(path + " contains a non-string mapping key");
                    }
                    Optional<String> nested = validateJsonTree(entry.getValue(), path + '.' + key,
                            depth + 1, ancestors);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
            } else {
                List<?> list = (List<?>) value;
                for (int index = 0; index < list.size(); index++) {
                    Optional<String> nested = validateJsonTree(list.get(index), path + '[' + index + ']',
                            depth + 1, ancestors);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
            }
            return Optional.empty();
        } finally {
            ancestors.remove(value);
        }
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof List<?> list) {
            JsonArray result = new JsonArray(list.size());
            list.forEach(member -> result.add(toJson(member)));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject result = new JsonObject();
            map.forEach((key, member) -> result.add((String) key, toJson(member)));
            return result;
        }
        throw new IllegalArgumentException("non-JSON YAML value");
    }

    private static Set<String> stringKeys(Map<?, ?> map) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String text)) {
                return Set.of();
            }
            result.add(text);
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean isIntegerOne(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger)) {
            return false;
        }
        try {
            return new BigInteger(value.toString()).equals(BigInteger.ONE);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeDiagnostic(String value) {
        String singleLine = Objects.requireNonNullElse(value, "validation failed")
                .replaceAll("[\\p{Cntrl}&&[^\\t]]+", " ")
                .replace('\t', ' ')
                .trim();
        if (singleLine.isEmpty()) {
            return "validation failed";
        }
        int points = codePoints(singleLine);
        if (points <= MAX_SAFE_DIAGNOSTIC_CHARS) {
            return singleLine;
        }
        int end = singleLine.offsetByCodePoints(0, MAX_SAFE_DIAGNOSTIC_CHARS);
        return singleLine.substring(0, end);
    }

    private void warn(String diagnostic) {
        try {
            warningSink.accept(diagnostic);
        } catch (RuntimeException ignored) {
            // Operator diagnostics must not make catalog loading fail.
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static int codePoints(CharSequence value) {
        if (value instanceof String text) {
            return codePoints(text);
        }
        return Character.codePointCount(value, 0, value.length());
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private record CapabilityResult(List<String> capabilities, String diagnostic) {
        static CapabilityResult valid(List<String> capabilities) {
            return new CapabilityResult(capabilities, null);
        }

        static CapabilityResult invalid(String diagnostic) {
            return new CapabilityResult(List.of(), diagnostic);
        }
    }

    /** PRD-2 Function loading and invocation-validation limits, independent from plugin config types. */
    public record Limits(
            int maxFileChars,
            int maxEntries,
            int maxDescriptionChars,
            int maxArgumentChars,
            int maxArgumentDepth,
            int maxArgumentMembers,
            int maxValidationViolations,
            int maxSourceChars
    ) {
        public Limits {
            if (maxFileChars < 1 || maxEntries < 1 || maxDescriptionChars < 1
                    || maxArgumentChars < 1 || maxArgumentDepth < 1 || maxArgumentMembers < 1
                    || maxValidationViolations < 1 || maxSourceChars < 1) {
                throw new IllegalArgumentException("function limits must be positive");
            }
        }

        public SchemaLimits schemaLimits() {
            return new SchemaLimits(maxArgumentChars, maxArgumentDepth, maxArgumentMembers,
                    maxValidationViolations);
        }

        public static Limits defaults() {
            return new Limits(1_048_576, 256, 512, 32_768, 16, 2_048, 8, 65_536);
        }
    }

    private static final class FileLimitException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
