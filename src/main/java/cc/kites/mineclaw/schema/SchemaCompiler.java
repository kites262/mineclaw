package cc.kites.mineclaw.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict compiler for the frozen PRD-2 Schema profile. */
public final class SchemaCompiler {
    private static final Set<String> KEYWORDS = Set.of(
            "type", "description", "properties", "required", "additionalProperties",
            "items", "minimum", "maximum", "minLength", "maxLength", "minItems",
            "maxItems", "enum");
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SchemaCompiler() {
    }

    public static CompiledSchema compile(JsonObject schema) {
        return compile(schema, SchemaLimits.defaults());
    }

    public static CompiledSchema compile(JsonObject schema, SchemaLimits limits) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(limits, "limits");
        enforceDefinitionBudget(schema, limits);
        CompiledSchema.Node root = compileNode(schema, "$", 0, limits);
        if (root.type != CompiledSchema.ValueType.OBJECT) {
            throw failure("$", "type", "root Schema type must be object");
        }
        return new CompiledSchema(root, limits);
    }

    private static CompiledSchema.Node compileNode(
            JsonObject schema,
            String path,
            int depth,
            SchemaLimits limits
    ) {
        if (depth > limits.maxDepth()) {
            throw failure(path, "maxDepth", "maximum Schema depth exceeded");
        }
        for (String keyword : schema.keySet()) {
            if (!KEYWORDS.contains(keyword)) {
                throw failure(path + '.' + keyword, keyword, "unsupported Schema keyword");
            }
        }

        JsonElement rawType = schema.get("type");
        if (rawType == null || !rawType.isJsonPrimitive()
                || !rawType.getAsJsonPrimitive().isString()) {
            throw failure(path + ".type", "type", "type must be one supported string");
        }
        final CompiledSchema.ValueType type;
        try {
            type = CompiledSchema.ValueType.fromWireName(rawType.getAsString());
        } catch (IllegalArgumentException exception) {
            throw failure(path + ".type", "type", "unsupported Schema type " + rawType.getAsString());
        }

        JsonElement description = schema.get("description");
        if (description != null && (!description.isJsonPrimitive()
                || !description.getAsJsonPrimitive().isString())) {
            throw failure(path + ".description", "description", "description must be a string");
        }

        requireApplicable(schema, path, type, Set.of("properties", "required", "additionalProperties"),
                CompiledSchema.ValueType.OBJECT);
        requireApplicable(schema, path, type, Set.of("items", "minItems", "maxItems"),
                CompiledSchema.ValueType.ARRAY);
        requireApplicable(schema, path, type, Set.of("minLength", "maxLength"),
                CompiledSchema.ValueType.STRING);
        if (type != CompiledSchema.ValueType.INTEGER && type != CompiledSchema.ValueType.NUMBER) {
            for (String keyword : List.of("minimum", "maximum")) {
                if (schema.has(keyword)) {
                    throw failure(path + '.' + keyword, keyword,
                            keyword + " is only valid for integer or number");
                }
            }
        }

        LinkedHashMap<String, CompiledSchema.Node> properties = new LinkedHashMap<>();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        if (type == CompiledSchema.ValueType.OBJECT) {
            JsonElement additional = schema.get("additionalProperties");
            if (additional == null || !additional.isJsonPrimitive()
                    || !additional.getAsJsonPrimitive().isBoolean() || additional.getAsBoolean()) {
                throw failure(path + ".additionalProperties", "additionalProperties",
                        "additionalProperties must be explicitly false");
            }
            JsonElement rawProperties = schema.get("properties");
            if (rawProperties != null) {
                if (!rawProperties.isJsonObject()) {
                    throw failure(path + ".properties", "properties", "properties must be an object");
                }
                for (Map.Entry<String, JsonElement> property : rawProperties.getAsJsonObject().entrySet()) {
                    if (!PROPERTY_NAME.matcher(property.getKey()).matches()) {
                        throw failure(path + ".properties", "properties",
                                "property name must match " + PROPERTY_NAME.pattern());
                    }
                    if (!property.getValue().isJsonObject()) {
                        throw failure(path + ".properties." + property.getKey(), "properties",
                                "property Schema must be an object");
                    }
                    properties.put(property.getKey(), compileNode(property.getValue().getAsJsonObject(),
                            path + ".properties." + property.getKey(), depth + 1, limits));
                }
            }
            JsonElement rawRequired = schema.get("required");
            if (rawRequired != null) {
                if (!rawRequired.isJsonArray()) {
                    throw failure(path + ".required", "required", "required must be an array");
                }
                for (JsonElement entry : rawRequired.getAsJsonArray()) {
                    if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                        throw failure(path + ".required", "required",
                                "required must contain unique property names");
                    }
                    String name = entry.getAsString();
                    if (!required.add(name)) {
                        throw failure(path + ".required", "required",
                                "required must contain unique property names");
                    }
                    if (!properties.containsKey(name)) {
                        throw failure(path + ".required", "required",
                                "required references missing property " + name);
                    }
                }
            }
        }

        CompiledSchema.Node items = null;
        if (type == CompiledSchema.ValueType.ARRAY && schema.has("items")) {
            JsonElement rawItems = schema.get("items");
            if (!rawItems.isJsonObject()) {
                throw failure(path + ".items", "items", "items must be one Schema object");
            }
            items = compileNode(rawItems.getAsJsonObject(), path + ".items", depth + 1, limits);
        }

        BigDecimal minimum = decimalKeyword(schema, path, "minimum");
        BigDecimal maximum = decimalKeyword(schema, path, "maximum");
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw failure(path, "minimum", "minimum must not exceed maximum");
        }
        BigInteger minLength = integerKeyword(schema, path, "minLength");
        BigInteger maxLength = integerKeyword(schema, path, "maxLength");
        if (minLength != null && maxLength != null && minLength.compareTo(maxLength) > 0) {
            throw failure(path, "minLength", "minLength must not exceed maxLength");
        }
        BigInteger minItems = integerKeyword(schema, path, "minItems");
        BigInteger maxItems = integerKeyword(schema, path, "maxItems");
        if (minItems != null && maxItems != null && minItems.compareTo(maxItems) > 0) {
            throw failure(path, "minItems", "minItems must not exceed maxItems");
        }

        List<JsonElement> enumeration = compileEnum(schema.get("enum"), type, path);
        return new CompiledSchema.Node(type, properties, required, items, minimum, maximum,
                minLength, maxLength, minItems, maxItems, enumeration);
    }

    private static void requireApplicable(
            JsonObject schema,
            String path,
            CompiledSchema.ValueType actual,
            Set<String> keywords,
            CompiledSchema.ValueType expected
    ) {
        if (actual == expected) {
            return;
        }
        for (String keyword : keywords) {
            if (schema.has(keyword)) {
                throw failure(path + '.' + keyword, keyword,
                        keyword + " is only valid for " + expected.wireName);
            }
        }
    }

    private static BigDecimal decimalKeyword(JsonObject schema, String path, String keyword) {
        if (!schema.has(keyword)) {
            return null;
        }
        JsonElement value = schema.get(keyword);
        if (!CompiledSchema.finiteNumber(value)) {
            throw failure(path + '.' + keyword, keyword, keyword + " must be a finite JSON number");
        }
        return CompiledSchema.decimal(value);
    }

    private static BigInteger integerKeyword(JsonObject schema, String path, String keyword) {
        if (!schema.has(keyword)) {
            return null;
        }
        JsonElement value = schema.get(keyword);
        if (!CompiledSchema.finiteNumber(value)) {
            throw failure(path + '.' + keyword, keyword, keyword + " must be a non-negative JSON integer");
        }
        try {
            BigInteger parsed = CompiledSchema.decimal(value).toBigIntegerExact();
            if (parsed.signum() < 0) {
                throw new ArithmeticException("negative");
            }
            return parsed;
        } catch (ArithmeticException exception) {
            throw failure(path + '.' + keyword, keyword, keyword + " must be a non-negative JSON integer");
        }
    }

    private static List<JsonElement> compileEnum(
            JsonElement raw,
            CompiledSchema.ValueType type,
            String path
    ) {
        if (raw == null) {
            return List.of();
        }
        if (!raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw failure(path + ".enum", "enum", "enum must be a non-empty array");
        }
        ArrayList<JsonElement> values = new ArrayList<>();
        for (JsonElement value : raw.getAsJsonArray()) {
            if (!(value.isJsonNull() || value.isJsonPrimitive())
                    || value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && !CompiledSchema.finiteNumber(value)) {
                throw failure(path + ".enum", "enum", "enum must contain finite JSON scalar values");
            }
            if (!type.matches(value)) {
                throw failure(path + ".enum", "enum", "enum value does not match declared type");
            }
            if (values.stream().anyMatch(previous -> CompiledSchema.scalarEquals(previous, value))) {
                throw failure(path + ".enum", "enum", "enum values must be unique");
            }
            values.add(value.deepCopy());
        }
        return List.copyOf(values);
    }

    private static void enforceDefinitionBudget(JsonObject schema, SchemaLimits limits) {
        DefinitionBudget budget = new DefinitionBudget(limits);
        inspectDefinition(schema, "$", 0, budget);
    }

    private static void inspectDefinition(
            JsonElement value,
            String path,
            int depth,
            DefinitionBudget budget
    ) {
        if (depth > budget.limits.maxDepth() * 2 + 4) {
            throw failure(path, "maxDepth", "maximum Schema document depth exceeded");
        }
        if (value.isJsonNull()) {
            budget.addChars(4, path);
        } else if (value.isJsonPrimitive()) {
            budget.addChars(value.getAsString().codePointCount(0, value.getAsString().length()), path);
        } else if (value.isJsonArray()) {
            for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                budget.addMember(path);
                inspectDefinition(value.getAsJsonArray().get(index), path + '[' + index + ']',
                        depth + 1, budget);
            }
        } else {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                budget.addMember(path);
                budget.addChars(entry.getKey().codePointCount(0, entry.getKey().length()), path);
                inspectDefinition(entry.getValue(), path + '.' + entry.getKey(), depth + 1, budget);
            }
        }
    }

    private static SchemaCompilationException failure(String path, String keyword, String message) {
        return new SchemaCompilationException(path, keyword, message);
    }

    private static final class DefinitionBudget {
        private final SchemaLimits limits;
        private long chars;
        private int members;

        private DefinitionBudget(SchemaLimits limits) {
            this.limits = limits;
        }

        void addChars(int count, String path) {
            chars += count;
            if (chars > limits.maxChars()) {
                throw failure(path, "maxChars", "maximum Schema character count exceeded");
            }
        }

        void addMember(String path) {
            members++;
            if (members > limits.maxMembers()) {
                throw failure(path, "maxMembers", "maximum Schema member count exceeded");
            }
        }
    }
}
