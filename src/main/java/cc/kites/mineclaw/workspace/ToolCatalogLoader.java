package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.config.MineclawConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Strict, incompatible Schema-2 tools.yml loader with entry-level fault isolation. */
public final class ToolCatalogLoader {
    public static final String TOOLS_FILE_NAME = "tools.yml";
    public static final int SCHEMA_VERSION = 2;

    private static final int MAX_FILE_CHARS = 1_048_576;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_YAML_JSON_DEPTH = 64;
    private static final int MAX_PAYLOAD_DEPTH = 32;
    private static final int MAX_PAYLOAD_MEMBERS = 4_096;
    private static final int MAX_PAYLOAD_CHARS = 65_536;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 512;
    private static final int MAX_SCHEMA_DEPTH = 16;

    private static final Pattern TOOL_HANDLER = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Set<String> ROOT_FIELDS = Set.of("schema", "tools");
    private static final Set<String> ENTRY_FIELDS = Set.of("handler", "enabled", "payload");
    private static final Set<String> FUNCTION_PAYLOAD_FIELDS = Set.of("type", "function");
    private static final Set<String> FUNCTION_DECLARATION_FIELDS = Set.of(
            "name", "description", "parameters");
    private static final Set<String> SCHEMA_FIELDS = Set.of(
            "type", "description", "properties", "required", "additionalProperties", "items",
            "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "enum");
    private static final Set<String> JSON_TYPES = Set.of(
            "object", "array", "string", "number", "integer", "boolean", "null");

    private final Gson gson;
    private final Consumer<String> warningSink;

    public ToolCatalogLoader() {
        this(new Gson(), ignored -> { });
    }

    public ToolCatalogLoader(Consumer<String> warningSink) {
        this(new Gson(), warningSink);
    }

    public ToolCatalogLoader(Gson gson, Consumer<String> warningSink) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /** Reads the fixed tools.yml entry relative to the trusted plugin data root. */
    public ToolCatalog load(Path dataRoot, Path path, MineclawConfig.Tools settings) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(path, "path");
        String contents = new WorkspacePathSecurity(dataRoot).readFixedUtf8(path, TOOLS_FILE_NAME);
        return parse(contents, settings, TOOLS_FILE_NAME);
    }

    public ToolCatalog parse(String contents, MineclawConfig.Tools settings) {
        return parse(contents, settings, TOOLS_FILE_NAME);
    }

    private ToolCatalog parse(String contents, MineclawConfig.Tools settings, String sourceName) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(settings, "settings");
        if (contents.codePointCount(0, contents.length()) > MAX_FILE_CHARS) {
            return rootInvalid(sourceName, "invalid_root at $: file exceeds maximum character count");
        }

        final Object rawRoot;
        try {
            Node document = new Yaml(new SafeConstructor(yamlOptions())).compose(new StringReader(contents));
            Optional<String> graphError = validateYamlGraph(document, 0);
            if (graphError.isPresent()) {
                return rootInvalid(sourceName, "invalid_root at $: " + graphError.orElseThrow());
            }
            rawRoot = new Yaml(new SafeConstructor(yamlOptions())).load(contents);
        } catch (YAMLException | IllegalStateException exception) {
            return rootInvalid(sourceName, "invalid_root at $: invalid YAML");
        }
        Optional<String> jsonError = validateJsonValue(rawRoot, "$", 0, new IdentityHashMap<>());
        if (jsonError.isPresent()) {
            return rootInvalid(sourceName, "invalid_root at $: " + jsonError.orElseThrow());
        }
        if (!(rawRoot instanceof Map<?, ?>)) {
            return rootInvalid(sourceName,
                    "invalid_root at $: expected Schema 2 mapping with schema and tools");
        }
        JsonElement converted;
        try {
            converted = gson.toJsonTree(rawRoot);
        } catch (RuntimeException exception) {
            return rootInvalid(sourceName, "invalid_root at $: cannot convert YAML to JSON");
        }
        if (!converted.isJsonObject()) {
            return rootInvalid(sourceName, "invalid_root at $: root must be a mapping");
        }
        JsonObject root = converted.getAsJsonObject();
        Optional<String> rootFields = exactFields(root, ROOT_FIELDS, ROOT_FIELDS, "$", true);
        if (rootFields.isPresent()) {
            return rootInvalid(sourceName, rootFields.orElseThrow());
        }
        if (!isExactInteger(root.get("schema"), SCHEMA_VERSION)) {
            return rootInvalid(sourceName,
                    "unsupported_schema at $.schema: only tools.yml schema 2 is supported");
        }
        if (!root.get("tools").isJsonArray()) {
            return rootInvalid(sourceName, "invalid_field_type at $.tools: must be an array");
        }
        JsonArray entries = root.getAsJsonArray("tools");
        if (entries.size() > MAX_ENTRIES) {
            return rootInvalid(sourceName, "invalid_root at $.tools: too many Tool entries");
        }

        ArrayList<ToolDefinition> definitions = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            definitions.add(parseEntry(entries.get(index), index + 1, settings));
        }
        markAllDuplicatesInvalid(definitions);

        ArrayList<String> diagnostics = new ArrayList<>();
        for (int index = 0; index < definitions.size(); index++) {
            ToolDefinition definition = definitions.get(index);
            if (definition.status() != ToolDefinition.Status.INVALID) {
                continue;
            }
            String contextual = sourceName + " entry #" + definition.index()
                    + (definition.handler().isBlank() ? "" : " (" + definition.handler() + ")")
                    + ": " + definition.diagnostic().orElseThrow();
            definition = definition.withDiagnostic(contextual);
            definitions.set(index, definition);
            diagnostics.add(contextual);
            warn(contextual);
        }
        return new ToolCatalog(definitions, diagnostics);
    }

    private ToolDefinition parseEntry(JsonElement raw, int index, MineclawConfig.Tools settings) {
        if (!raw.isJsonObject()) {
            return invalid(index, "", "invalid_field_type", "entry", "must be a mapping");
        }
        JsonObject entry = raw.getAsJsonObject();
        String handlerName = stringValue(entry.get("handler")).orElse("");
        Optional<String> entryFields = exactFields(entry, ENTRY_FIELDS, ENTRY_FIELDS, "entry", true);
        if (entryFields.isPresent()) {
            return ToolDefinition.invalid(index, handlerName, entryFields.orElseThrow());
        }
        if (!isString(entry.get("handler")) || !TOOL_HANDLER.matcher(handlerName).matches()) {
            return invalid(index, handlerName, "invalid_handler", "handler",
                    "must match " + TOOL_HANDLER.pattern());
        }
        if (!isBoolean(entry.get("enabled"))) {
            return invalid(index, handlerName, "invalid_field_type", "enabled", "must be a boolean");
        }
        boolean declaredEnabled = entry.get("enabled").getAsBoolean();
        if (!entry.get("payload").isJsonObject() || entry.getAsJsonObject("payload").isEmpty()) {
            return invalid(index, handlerName, "invalid_field_type", "payload",
                    "must be a non-empty mapping");
        }
        JsonObject payload = entry.getAsJsonObject("payload");
        Optional<ToolDefinition.Handler> handler = ToolDefinition.Handler.fromWireName(handlerName);
        if (handler.isEmpty()) {
            return invalid(index, handlerName, "unknown_handler", "handler",
                    "must equal a registered Mineclaw handler");
        }
        Optional<String> budget = validatePayloadBudget(payload);
        if (budget.isPresent()) {
            return invalid(index, handlerName, "invalid_payload", "payload", budget.orElseThrow());
        }
        if (!isString(payload.get("type"))) {
            return invalid(index, handlerName, "invalid_field_type", "payload.type",
                    "must be a non-blank string");
        }
        Optional<String> payloadError = validateFunctionPayload(payload, handlerName);
        if (payloadError.isPresent()) {
            return ToolDefinition.invalid(index, handlerName, payloadError.orElseThrow());
        }
        if (handler.orElseThrow() == ToolDefinition.Handler.CALL_FUNCTION) {
            Optional<String> gatewayError = validateCallFunctionContract(payload);
            if (gatewayError.isPresent()) {
                return ToolDefinition.invalid(index, handlerName, gatewayError.orElseThrow());
            }
        }
        StatusResult status = status(handlerName, declaredEnabled, settings);
        return ToolDefinition.tool(index, handlerName, payload, declaredEnabled, status.status(),
                status.diagnostic());
    }

    private static Optional<String> validateFunctionPayload(JsonObject payload, String expectedName) {
        Optional<String> fields = exactFields(payload, FUNCTION_PAYLOAD_FIELDS,
                FUNCTION_PAYLOAD_FIELDS, "payload", true);
        if (fields.isPresent()) {
            return fields;
        }
        if (!stringValue(payload.get("type")).orElse("").equals("function")) {
            return Optional.of(diagnostic("payload_handler_mismatch", "payload.type",
                    "local Tool payload type must be function"));
        }
        if (!payload.get("function").isJsonObject()) {
            return Optional.of(diagnostic("invalid_field_type", "payload.function", "must be a mapping"));
        }
        JsonObject function = payload.getAsJsonObject("function");
        Optional<String> functionFields = exactFields(function, FUNCTION_DECLARATION_FIELDS,
                FUNCTION_DECLARATION_FIELDS, "payload.function", true);
        if (functionFields.isPresent()) {
            return functionFields;
        }
        if (!stringValue(function.get("name")).orElse("").equals(expectedName)) {
            return Optional.of(diagnostic("payload_handler_mismatch", "payload.function.name",
                    "must equal Tool handler " + expectedName));
        }
        String description = stringValue(function.get("description")).orElse("").trim();
        if (description.isEmpty() || description.codePointCount(0, description.length())
                > MAX_DESCRIPTION_CODE_POINTS) {
            return Optional.of(diagnostic("invalid_payload", "payload.function.description",
                    "must be non-blank and within the description limit"));
        }
        if (!function.get("parameters").isJsonObject()) {
            return Optional.of(diagnostic("invalid_field_type", "payload.function.parameters",
                    "must be a mapping"));
        }
        return validateSchema(function.getAsJsonObject("parameters"), "payload.function.parameters", 0)
                .map(message -> diagnostic("invalid_payload", "payload.function.parameters", message));
    }

    private static Optional<String> validateCallFunctionContract(JsonObject payload) {
        JsonObject parameters = payload.getAsJsonObject("function").getAsJsonObject("parameters");
        if (!isSingleType(parameters.get("type"), "object")
                || !parameters.has("properties") || !parameters.get("properties").isJsonObject()
                || !isBoolean(parameters.get("additionalProperties"))
                || parameters.get("additionalProperties").getAsBoolean()) {
            return Optional.of(diagnostic("invalid_payload", "payload.function.parameters",
                    "call_function requires a closed object Schema"));
        }
        JsonObject properties = parameters.getAsJsonObject("properties");
        if (!properties.keySet().equals(Set.of("function", "arguments"))) {
            return Optional.of(diagnostic("invalid_payload", "payload.function.parameters.properties",
                    "call_function requires exactly function and arguments"));
        }
        JsonObject function = properties.get("function").isJsonObject()
                ? properties.getAsJsonObject("function") : new JsonObject();
        JsonObject arguments = properties.get("arguments").isJsonObject()
                ? properties.getAsJsonObject("arguments") : new JsonObject();
        if (!isSingleType(function.get("type"), "string")
                || !isSingleType(arguments.get("type"), "object")
                || !isBoolean(arguments.get("additionalProperties"))
                || !arguments.get("additionalProperties").getAsBoolean()) {
            return Optional.of(diagnostic("invalid_payload", "payload.function.parameters.properties",
                    "call_function gateway property contract is fixed"));
        }
        if (!parameters.has("required") || !parameters.get("required").isJsonArray()) {
            return Optional.of(diagnostic("invalid_payload", "payload.function.parameters.required",
                    "must require function and arguments"));
        }
        Set<String> required = new HashSet<>();
        for (JsonElement value : parameters.getAsJsonArray("required")) {
            if (!isString(value)) {
                return Optional.of(diagnostic("invalid_payload", "payload.function.parameters.required",
                        "must require function and arguments"));
            }
            required.add(value.getAsString());
        }
        return required.equals(Set.of("function", "arguments"))
                && parameters.getAsJsonArray("required").size() == 2 ? Optional.empty()
                : Optional.of(diagnostic("invalid_payload", "payload.function.parameters.required",
                "must require exactly function and arguments"));
    }

    private static Optional<String> validateSchema(JsonObject schema, String path, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) {
            return Optional.of(path + " exceeds maximum Schema nesting");
        }
        for (String field : schema.keySet()) {
            if (!SCHEMA_FIELDS.contains(field)) {
                return Optional.of("unknown_field at " + path + '.' + field
                        + ": unsupported Schema keyword");
            }
        }
        JsonElement type = schema.get("type");
        Optional<List<String>> parsedTypes = parseTypes(type);
        if (parsedTypes.isEmpty()) {
            return Optional.of(path + ".type must contain supported JSON Schema types");
        }
        List<String> types = parsedTypes.orElseThrow();
        if (depth == 0 && !isSingleType(type, "object")) {
            return Optional.of(path + ".type must be object");
        }
        if (schema.has("description") && !isString(schema.get("description"))) {
            return Optional.of(path + ".description must be a string");
        }
        JsonElement properties = schema.get("properties");
        if ((properties != null || schema.has("required") || schema.has("additionalProperties"))
                && !types.contains("object")) {
            return Optional.of(path + " uses object keywords without object type");
        }
        if (properties != null) {
            if (!properties.isJsonObject()) {
                return Optional.of(path + ".properties must be an object");
            }
            for (Map.Entry<String, JsonElement> property : properties.getAsJsonObject().entrySet()) {
                if (!property.getValue().isJsonObject()) {
                    return Optional.of(path + ".properties." + property.getKey() + " must be an object");
                }
                Optional<String> nested = validateSchema(property.getValue().getAsJsonObject(),
                        path + ".properties." + property.getKey(), depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        if (schema.has("required")) {
            if (!schema.get("required").isJsonArray()) {
                return Optional.of(path + ".required must be an array of strings");
            }
            Set<String> names = new HashSet<>();
            for (JsonElement value : schema.getAsJsonArray("required")) {
                if (!isString(value) || !names.add(value.getAsString()) || properties == null
                        || !properties.getAsJsonObject().has(value.getAsString())) {
                    return Optional.of(path + ".required must contain unique declared property names");
                }
            }
        }
        if (schema.has("items")) {
            if (!types.contains("array")) {
                return Optional.of(path + ".items requires array type");
            }
            if (!schema.get("items").isJsonObject()) {
                return Optional.of(path + ".items must be an object");
            }
            Optional<String> nested = validateSchema(schema.getAsJsonObject("items"), path + ".items",
                    depth + 1);
            if (nested.isPresent()) {
                return nested;
            }
        }
        if (schema.has("additionalProperties") && !isBoolean(schema.get("additionalProperties"))) {
            return Optional.of(path + ".additionalProperties must be a boolean");
        }
        if ((schema.has("minLength") || schema.has("maxLength")) && !types.contains("string")) {
            return Optional.of(path + " uses string limits without string type");
        }
        if ((schema.has("minItems") || schema.has("maxItems")) && !types.contains("array")) {
            return Optional.of(path + " uses array limits without array type");
        }
        if ((schema.has("minimum") || schema.has("maximum"))
                && !(types.contains("integer") || types.contains("number"))) {
            return Optional.of(path + " uses numeric limits without numeric type");
        }
        for (String numeric : List.of("minimum", "maximum")) {
            if (schema.has(numeric) && !isFiniteNumber(schema.get(numeric))) {
                return Optional.of(path + '.' + numeric + " must be a finite number");
            }
        }
        for (String integer : List.of("minLength", "maxLength", "minItems", "maxItems")) {
            if (schema.has(integer) && !boundedInteger(schema.get(integer), 0, Integer.MAX_VALUE)) {
                return Optional.of(path + '.' + integer + " must be a non-negative integer");
            }
        }
        if (schema.has("minimum") && schema.has("maximum")
                && decimal(schema.get("minimum")).compareTo(decimal(schema.get("maximum"))) > 0) {
            return Optional.of(path + ".minimum must not exceed maximum");
        }
        for (String prefix : List.of("Length", "Items")) {
            String minimum = "min" + prefix;
            String maximum = "max" + prefix;
            if (schema.has(minimum) && schema.has(maximum)
                    && decimal(schema.get(minimum)).compareTo(decimal(schema.get(maximum))) > 0) {
                return Optional.of(path + '.' + minimum + " must not exceed " + maximum);
            }
        }
        if (schema.has("enum")) {
            JsonElement rawEnum = schema.get("enum");
            if (!rawEnum.isJsonArray() || rawEnum.getAsJsonArray().isEmpty()) {
                return Optional.of(path + ".enum must be a non-empty array");
            }
            ArrayList<JsonElement> seen = new ArrayList<>();
            for (JsonElement value : rawEnum.getAsJsonArray()) {
                if (!(value.isJsonNull() || value.isJsonPrimitive())
                        || !matchesAnyType(value, types)
                        || seen.stream().anyMatch(value::equals)) {
                    return Optional.of(path + ".enum must contain unique values matching its declared type");
                }
                seen.add(value);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesAnyType(JsonElement value, List<String> types) {
        return types.stream().anyMatch(type -> switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> isString(value);
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && isFiniteNumber(value);
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && isFiniteNumber(value) && decimal(value).stripTrailingZeros().scale() <= 0;
            case "boolean" -> isBoolean(value);
            case "null" -> value.isJsonNull();
            default -> false;
        });
    }

    private static Optional<List<String>> parseTypes(JsonElement type) {
        if (isString(type)) {
            return JSON_TYPES.contains(type.getAsString())
                    ? Optional.of(List.of(type.getAsString())) : Optional.empty();
        }
        if (type == null || !type.isJsonArray() || type.getAsJsonArray().isEmpty()) {
            return Optional.empty();
        }
        ArrayList<String> types = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement entry : type.getAsJsonArray()) {
            if (!isString(entry) || !JSON_TYPES.contains(entry.getAsString())
                    || !seen.add(entry.getAsString())) {
                return Optional.empty();
            }
            types.add(entry.getAsString());
        }
        return Optional.of(List.copyOf(types));
    }

    private static Optional<String> validatePayloadBudget(JsonObject payload) {
        if (payload.toString().codePointCount(0, payload.toString().length()) > MAX_PAYLOAD_CHARS) {
            return Optional.of("payload exceeds maximum character count");
        }
        Budget budget = new Budget();
        return inspectPayload(payload, 0, budget);
    }

    private static Optional<String> inspectPayload(JsonElement value, int depth, Budget budget) {
        if (depth > MAX_PAYLOAD_DEPTH) {
            return Optional.of("payload exceeds maximum nesting depth");
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (++budget.members > MAX_PAYLOAD_MEMBERS) {
                    return Optional.of("payload exceeds maximum member count");
                }
                Optional<String> nested = inspectPayload(entry.getValue(), depth + 1, budget);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                if (++budget.members > MAX_PAYLOAD_MEMBERS) {
                    return Optional.of("payload exceeds maximum member count");
                }
                Optional<String> nested = inspectPayload(entry, depth + 1, budget);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> exactFields(JsonObject object, Set<String> allowed, Set<String> required,
                                                String path, boolean stableCode) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                return Optional.of(diagnostic("unknown_field", path + '.' + field,
                        "field is not allowed"));
            }
        }
        for (String field : required) {
            if (!object.has(field)) {
                return Optional.of(diagnostic("missing_field", path + '.' + field,
                        "field is required"));
            }
        }
        return Optional.empty();
    }

    private static void markAllDuplicatesInvalid(ArrayList<ToolDefinition> definitions) {
        Map<String, Integer> counts = new HashMap<>();
        definitions.stream().map(ToolDefinition::handler)
                .filter(handler -> TOOL_HANDLER.matcher(handler).matches())
                .forEach(handler -> counts.merge(handler, 1, Integer::sum));
        for (int index = 0; index < definitions.size(); index++) {
            ToolDefinition definition = definitions.get(index);
            if (counts.getOrDefault(definition.handler(), 0) > 1) {
                definitions.set(index, definition.duplicateInvalid(
                        diagnostic("duplicate_handler", "handler",
                                "duplicate Tool handler " + definition.handler())));
            }
        }
    }

    private static StatusResult status(String handler, boolean declaredEnabled,
                                       MineclawConfig.Tools settings) {
        if (!settings.enabled()) {
            return new StatusResult(ToolDefinition.Status.DISABLED, "disabled by tools.enabled");
        }
        if (!declaredEnabled) {
            return new StatusResult(ToolDefinition.Status.DISABLED, "disabled by tools.yml entry");
        }
        if (settings.isDisabled(handler)) {
            return new StatusResult(ToolDefinition.Status.DISABLED, "disabled by tools.disabled");
        }
        return new StatusResult(ToolDefinition.Status.ENABLED, null);
    }

    private ToolCatalog rootInvalid(String sourceName, String diagnostic) {
        String contextual = sourceName + ": " + diagnostic;
        warn(contextual);
        return ToolCatalog.empty(contextual);
    }

    private static ToolDefinition invalid(int index, String handler,
                                          String code, String path, String message) {
        return ToolDefinition.invalid(index, handler, diagnostic(code, path, message));
    }

    private static String diagnostic(String code, String path, String message) {
        return code + " at " + path + ": " + message;
    }

    private static String safeName(String value) {
        return value.matches("[a-z0-9_.-]{1,64}") ? value : "<invalid>";
    }

    private static boolean isSingleType(JsonElement value, String expected) {
        return isString(value) && value.getAsString().equals(expected);
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static Optional<String> stringValue(JsonElement value) {
        return isString(value) ? Optional.of(value.getAsString()) : Optional.empty();
    }

    private static boolean isExactInteger(JsonElement value, int expected) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && isFiniteNumber(value) && decimal(value).stripTrailingZeros().scale() <= 0
                && decimal(value).compareTo(BigDecimal.valueOf(expected)) == 0;
    }

    private static boolean boundedInteger(JsonElement value, int minimum, int maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !isFiniteNumber(value)) {
            return false;
        }
        BigDecimal parsed = decimal(value);
        return parsed.stripTrailingZeros().scale() <= 0
                && parsed.compareTo(BigDecimal.valueOf(minimum)) >= 0
                && parsed.compareTo(BigDecimal.valueOf(maximum)) <= 0;
    }

    private static boolean isFiniteNumber(JsonElement value) {
        try {
            decimal(value);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static BigDecimal decimal(JsonElement value) {
        return new BigDecimal(value.getAsString());
    }

    private static Optional<String> validateJsonValue(Object value, String path, int depth,
                                                      IdentityHashMap<Object, Boolean> active) {
        if (depth > MAX_YAML_JSON_DEPTH) {
            return Optional.of("maximum JSON nesting exceeded");
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            try {
                new BigDecimal(number.toString());
                return Optional.empty();
            } catch (NumberFormatException exception) {
                return Optional.of("non-finite JSON number at " + path);
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (active.put(value, Boolean.TRUE) != null) {
                return Optional.of("cyclic YAML value at " + path);
            }
            try {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        return Optional.of("non-string object key at " + path);
                    }
                    Optional<String> nested = validateJsonValue(entry.getValue(), path + '.' + key,
                            depth + 1, active);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
                return Optional.empty();
            } finally {
                active.remove(value);
            }
        }
        if (value instanceof List<?> list) {
            if (active.put(value, Boolean.TRUE) != null) {
                return Optional.of("cyclic YAML value at " + path);
            }
            try {
                for (int index = 0; index < list.size(); index++) {
                    Optional<String> nested = validateJsonValue(list.get(index), path + '[' + index + ']',
                            depth + 1, active);
                    if (nested.isPresent()) {
                        return nested;
                    }
                }
                return Optional.empty();
            } finally {
                active.remove(value);
            }
        }
        return Optional.of("non-JSON YAML value at " + path);
    }

    private static LoaderOptions yamlOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_YAML_JSON_DEPTH);
        options.setCodePointLimit(MAX_FILE_CHARS + 1);
        return options;
    }

    private static Optional<String> validateYamlGraph(Node node, int depth) {
        if (node == null) {
            return Optional.empty();
        }
        if (depth > MAX_YAML_JSON_DEPTH) {
            return Optional.of("maximum YAML nesting exceeded");
        }
        if (node.getAnchor() != null) {
            return Optional.of("anchors and aliases are not allowed");
        }
        if (node.getTag().isCustomGlobal()) {
            return Optional.of("custom YAML tags are not allowed");
        }
        if (node instanceof MappingNode mapping) {
            if (mapping.isMerged()) {
                return Optional.of("merge keys are not allowed");
            }
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode().getTag().equals(Tag.MERGE)
                        || tuple.getKeyNode() instanceof ScalarNode scalar
                        && scalar.getValue().equals("<<")) {
                    return Optional.of("merge keys are not allowed");
                }
                Optional<String> key = validateYamlGraph(tuple.getKeyNode(), depth + 1);
                if (key.isPresent()) {
                    return key;
                }
                Optional<String> value = validateYamlGraph(tuple.getValueNode(), depth + 1);
                if (value.isPresent()) {
                    return value;
                }
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                Optional<String> error = validateYamlGraph(child, depth + 1);
                if (error.isPresent()) {
                    return error;
                }
            }
        }
        return Optional.empty();
    }

    private void warn(String message) {
        warningSink.accept(message);
    }

    private static final class Budget {
        private int members;
    }

    private record StatusResult(ToolDefinition.Status status, String diagnostic) { }
}
