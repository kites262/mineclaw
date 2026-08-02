package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.config.MineclawConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Per-request SnakeYAML + Gson tools.yml loader with entry-level fault isolation. */
public final class ToolCatalogLoader {
    public static final String TOOLS_FILE_NAME = "tools.yml";
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

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

    /** Reads the fixed tools.yml entry relative to an explicit trusted Workspace root. */
    public ToolCatalog load(Path workspaceRoot, Path path, MineclawConfig.Tools settings) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(settings, "settings");
        String contents = new WorkspacePathSecurity(workspaceRoot).readFixedUtf8(path, TOOLS_FILE_NAME);
        return parse(contents, settings, TOOLS_FILE_NAME);
    }

    public ToolCatalog parse(String contents, MineclawConfig.Tools settings) {
        return parse(contents, settings, "tools.yml");
    }

    private ToolCatalog parse(String contents, MineclawConfig.Tools settings, String sourceName) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(settings, "settings");

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(Math.max(1_024, contents.length() + 1));

        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(contents);
        } catch (YAMLException exception) {
            String diagnostic = sourceName + " is invalid YAML: " + exception.getMessage();
            warn(diagnostic);
            return ToolCatalog.empty(diagnostic);
        }
        if (!(root instanceof List<?> entries)) {
            String diagnostic = sourceName + " top level must be a YAML list";
            warn(diagnostic);
            return ToolCatalog.empty(diagnostic);
        }

        ArrayList<ToolDefinition> definitions = new ArrayList<>(entries.size());
        ArrayList<String> diagnostics = new ArrayList<>();
        Set<String> claimedNames = new HashSet<>();
        for (int zeroBasedIndex = 0; zeroBasedIndex < entries.size(); zeroBasedIndex++) {
            int index = zeroBasedIndex + 1;
            Object raw = entries.get(zeroBasedIndex);
            ToolDefinition definition = parseEntry(raw, index, claimedNames, settings);
            definitions.add(definition);
            if (definition.status() == ToolDefinition.Status.INVALID) {
                String message = definition.diagnostic().orElseThrow();
                String contextual = sourceName + " entry #" + index + ": " + message;
                diagnostics.add(contextual);
                warn(contextual);
            }
        }
        return new ToolCatalog(definitions, diagnostics);
    }

    private ToolDefinition parseEntry(
            Object raw,
            int index,
            Set<String> claimedNames,
            MineclawConfig.Tools settings
    ) {
        if (!(raw instanceof Map<?, ?>)) {
            return ToolDefinition.invalid(index, "", "", "entry must be a mapping");
        }

        JsonElement converted;
        try {
            converted = gson.toJsonTree(raw);
        } catch (RuntimeException exception) {
            return ToolDefinition.invalid(index, "", "", "entry cannot be converted to JSON: "
                    + exception.getMessage());
        }
        if (!converted.isJsonObject()) {
            return ToolDefinition.invalid(index, "", "", "entry must be a mapping");
        }
        JsonObject object = converted.getAsJsonObject();

        String name = optionalString(object, "name").orElse("");
        String rawHandler = optionalString(object, "handler").orElse("");
        if (name.isBlank()) {
            return ToolDefinition.invalid(index, name, rawHandler, "name must be a non-blank string");
        }
        if (!TOOL_NAME.matcher(name).matches()) {
            return ToolDefinition.invalid(index, name, rawHandler,
                    "name must match " + TOOL_NAME.pattern());
        }
        if (!claimedNames.add(name)) {
            return ToolDefinition.invalid(index, name, rawHandler, "duplicate tool name " + name);
        }

        if (!isString(object, "handler")) {
            return ToolDefinition.invalid(index, name, rawHandler, "handler must be a string");
        }
        Optional<ToolDefinition.Handler> handler = ToolDefinition.Handler.fromWireName(rawHandler);
        if (handler.isEmpty()) {
            return ToolDefinition.invalid(index, name, rawHandler, "unknown handler " + rawHandler);
        }

        if (!isString(object, "description")) {
            return ToolDefinition.invalid(index, name, rawHandler, "description must be a string");
        }
        String description = object.get("description").getAsString().trim();
        if (description.isEmpty()) {
            return ToolDefinition.invalid(index, name, rawHandler, "description must not be blank");
        }

        JsonElement parametersElement = object.get("parameters");
        if (parametersElement == null || !parametersElement.isJsonObject()) {
            return ToolDefinition.invalid(index, name, rawHandler, "parameters must be a JSON object");
        }
        Optional<String> schemaError = validateSchema(parametersElement.getAsJsonObject(), "parameters", 0);
        if (schemaError.isPresent()) {
            return ToolDefinition.invalid(index, name, rawHandler, schemaError.orElseThrow());
        }

        boolean declaredEnabled = true;
        if (object.has("enabled")) {
            JsonElement enabled = object.get("enabled");
            if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
                return ToolDefinition.invalid(index, name, rawHandler, "enabled must be a boolean");
            }
            declaredEnabled = enabled.getAsBoolean();
        }

        ToolDefinition.Status status;
        String diagnostic = null;
        if (!settings.enabled()) {
            status = ToolDefinition.Status.DISABLED;
            diagnostic = "disabled by tools.enabled";
        } else if (!declaredEnabled) {
            status = ToolDefinition.Status.DISABLED;
            diagnostic = "disabled by the tools.yml entry";
        } else if (settings.isDisabled(name)) {
            status = ToolDefinition.Status.DISABLED;
            diagnostic = "disabled by tools.disabled";
        } else {
            status = ToolDefinition.Status.ENABLED;
        }

        return ToolDefinition.valid(index, name, handler.orElseThrow(), description,
                parametersElement.getAsJsonObject(), declaredEnabled, status, diagnostic);
    }

    private static Optional<String> optionalString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString());
    }

    private static boolean isString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static Optional<String> validateSchema(JsonObject schema, String path, int depth) {
        if (depth > 16) {
            return Optional.of(path + " exceeds maximum schema nesting");
        }
        JsonElement type = schema.get("type");
        if (type == null) {
            return Optional.of(path + ".type is required");
        }
        if (!validTypes(type)) {
            return Optional.of(path + ".type contains an unsupported JSON Schema type");
        }
        if (depth == 0 && !(type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                && type.getAsString().equals("object"))) {
            return Optional.of(path + ".type must be object");
        }

        JsonElement properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isJsonObject()) {
                return Optional.of(path + ".properties must be an object");
            }
            for (Map.Entry<String, JsonElement> entry : properties.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    return Optional.of(path + ".properties." + entry.getKey() + " must be an object");
                }
                Optional<String> nested = validateSchema(entry.getValue().getAsJsonObject(),
                        path + ".properties." + entry.getKey(), depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        JsonElement required = schema.get("required");
        if (required != null) {
            if (!required.isJsonArray()) {
                return Optional.of(path + ".required must be an array of strings");
            }
            Set<String> names = new HashSet<>();
            for (JsonElement member : required.getAsJsonArray()) {
                if (!member.isJsonPrimitive() || !member.getAsJsonPrimitive().isString()
                        || !names.add(member.getAsString())) {
                    return Optional.of(path + ".required must contain unique strings");
                }
                if (properties == null || !properties.getAsJsonObject().has(member.getAsString())) {
                    return Optional.of(path + ".required references missing property " + member.getAsString());
                }
            }
        }
        JsonElement items = schema.get("items");
        if (items != null) {
            if (!items.isJsonObject()) {
                return Optional.of(path + ".items must be an object");
            }
            Optional<String> nested = validateSchema(items.getAsJsonObject(), path + ".items", depth + 1);
            if (nested.isPresent()) {
                return nested;
            }
        }
        JsonElement additional = schema.get("additionalProperties");
        if (additional != null) {
            if (!(additional.isJsonObject()
                    || additional.isJsonPrimitive() && additional.getAsJsonPrimitive().isBoolean())) {
                return Optional.of(path + ".additionalProperties must be a boolean or object");
            }
            if (additional.isJsonObject()) {
                Optional<String> nested = validateSchema(additional.getAsJsonObject(),
                        path + ".additionalProperties", depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        JsonElement minimum = schema.get("minimum");
        JsonElement maximum = schema.get("maximum");
        if (minimum != null && !isNumber(minimum)) {
            return Optional.of(path + ".minimum must be a number");
        }
        if (maximum != null && !isNumber(maximum)) {
            return Optional.of(path + ".maximum must be a number");
        }
        if (minimum != null && maximum != null
                && decimal(minimum).compareTo(decimal(maximum)) > 0) {
            return Optional.of(path + ".minimum must not exceed maximum");
        }
        return Optional.empty();
    }

    private static boolean isNumber(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            decimal(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static BigDecimal decimal(JsonElement value) {
        return new BigDecimal(value.getAsString());
    }

    private static boolean validTypes(JsonElement type) {
        Set<String> allowed = Set.of("object", "array", "string", "number", "integer", "boolean", "null");
        if (type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()) {
            return allowed.contains(type.getAsString());
        }
        if (!type.isJsonArray() || type.getAsJsonArray().isEmpty()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (JsonElement entry : type.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || !allowed.contains(entry.getAsString()) || !seen.add(entry.getAsString())) {
                return false;
            }
        }
        return true;
    }

    private void warn(String message) {
        warningSink.accept(message);
    }

}
