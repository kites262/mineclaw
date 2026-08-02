package cc.kites.mineclaw.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates tool arguments against the JSON Schema subset accepted by tools.yml. */
final class ToolArgumentValidator {
    private static final int MAX_DEPTH = 64;
    private static final Pattern SIMPLE_PROPERTY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private ToolArgumentValidator() { }

    static Optional<Violation> validate(JsonElement value, JsonObject schema) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(schema, "schema");
        return validate(value, schema, "$", 0);
    }

    private static Optional<Violation> validate(JsonElement value, JsonObject schema, String path, int depth) {
        if (depth > MAX_DEPTH) {
            return violation(path, "tool schema exceeds the supported nesting depth");
        }

        Optional<Violation> typeViolation = validateType(value, schema.get("type"), path);
        if (typeViolation.isPresent()) {
            return typeViolation;
        }
        Optional<Violation> numericViolation = validateNumericBounds(value, schema, path);
        if (numericViolation.isPresent()) {
            return numericViolation;
        }
        if (value.isJsonObject()) {
            return validateObject(value.getAsJsonObject(), schema, path, depth);
        }
        if (value.isJsonArray()) {
            return validateArray(value.getAsJsonArray(), schema, path, depth);
        }
        return Optional.empty();
    }

    private static Optional<Violation> validateNumericBounds(
            JsonElement value, JsonObject schema, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        final BigDecimal number;
        try {
            number = new BigDecimal(value.getAsString());
        } catch (NumberFormatException exception) {
            return violation(path, "number is not finite");
        }
        JsonElement minimum = schema.get("minimum");
        if (minimum != null && number.compareTo(decimal(minimum)) < 0) {
            return violation(path, "must be greater than or equal to " + minimum);
        }
        JsonElement maximum = schema.get("maximum");
        if (maximum != null && number.compareTo(decimal(maximum)) > 0) {
            return violation(path, "must be less than or equal to " + maximum);
        }
        return Optional.empty();
    }

    private static BigDecimal decimal(JsonElement value) {
        return new BigDecimal(value.getAsString());
    }

    private static Optional<Violation> validateType(JsonElement value, JsonElement keyword, String path) {
        if (keyword == null) {
            return Optional.empty();
        }
        List<String> expected = new ArrayList<>();
        if (keyword.isJsonPrimitive() && keyword.getAsJsonPrimitive().isString()) {
            expected.add(keyword.getAsString());
        } else if (keyword.isJsonArray()) {
            JsonArray types = keyword.getAsJsonArray();
            if (types.isEmpty()) {
                return violation(path, "tool schema type array must not be empty");
            }
            for (JsonElement type : types) {
                if (!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
                    return violation(path, "tool schema type entries must be strings");
                }
                expected.add(type.getAsString());
            }
        } else {
            return violation(path, "tool schema type must be a string or an array of strings");
        }

        for (String type : expected) {
            if (!isSupportedType(type)) {
                return violation(path, "tool schema uses unsupported type: " + type);
            }
        }
        if (expected.stream().anyMatch(type -> matchesType(value, type))) {
            return Optional.empty();
        }
        String description = expected.size() == 1
                ? expected.getFirst() : "one of [" + String.join(", ", expected) + "]";
        return violation(path, "expected " + description + " but found " + actualType(value));
    }

    private static Optional<Violation> validateObject(JsonObject value, JsonObject schema,
                                                       String path, int depth) {
        JsonObject properties = null;
        JsonElement propertiesKeyword = schema.get("properties");
        if (propertiesKeyword != null) {
            if (!propertiesKeyword.isJsonObject()) {
                return violation(path, "tool schema properties must be an object");
            }
            properties = propertiesKeyword.getAsJsonObject();
        }

        JsonElement requiredKeyword = schema.get("required");
        if (requiredKeyword != null) {
            if (!requiredKeyword.isJsonArray()) {
                return violation(path, "tool schema required must be an array of strings");
            }
            for (JsonElement required : requiredKeyword.getAsJsonArray()) {
                if (!required.isJsonPrimitive() || !required.getAsJsonPrimitive().isString()) {
                    return violation(path, "tool schema required entries must be strings");
                }
                String name = required.getAsString();
                if (!value.has(name)) {
                    return violation(propertyPath(path, name), "required property is missing");
                }
            }
        }

        JsonElement additional = schema.get("additionalProperties");
        if (additional != null && !(isBoolean(additional) || additional.isJsonObject())) {
            return violation(path, "tool schema additionalProperties must be a boolean or object");
        }

        for (var entry : value.entrySet()) {
            String childPath = propertyPath(path, entry.getKey());
            JsonElement propertySchema = properties == null ? null : properties.get(entry.getKey());
            if (propertySchema != null) {
                if (!propertySchema.isJsonObject()) {
                    return violation(childPath, "tool property schema must be an object");
                }
                Optional<Violation> nested = validate(entry.getValue(), propertySchema.getAsJsonObject(),
                        childPath, depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
                continue;
            }
            if (additional == null || isBoolean(additional) && additional.getAsBoolean()) {
                continue;
            }
            if (isBoolean(additional)) {
                return violation(childPath, "additional property is not allowed");
            }
            Optional<Violation> nested = validate(entry.getValue(), additional.getAsJsonObject(),
                    childPath, depth + 1);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private static Optional<Violation> validateArray(JsonArray value, JsonObject schema,
                                                      String path, int depth) {
        JsonElement items = schema.get("items");
        if (items == null) {
            return Optional.empty();
        }
        if (!items.isJsonObject()) {
            return violation(path, "tool schema items must be an object");
        }
        for (int index = 0; index < value.size(); index++) {
            Optional<Violation> nested = validate(value.get(index), items.getAsJsonObject(),
                    path + "[" + index + "]", depth + 1);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private static boolean matchesType(JsonElement value, String type) {
        return switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && isInteger(value.getAsJsonPrimitive());
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "boolean" -> isBoolean(value);
            case "null" -> value.isJsonNull();
            default -> false;
        };
    }

    private static boolean isSupportedType(String type) {
        return switch (type) {
            case "object", "array", "string", "integer", "number", "boolean", "null" -> true;
            default -> false;
        };
    }

    private static boolean isInteger(JsonPrimitive value) {
        try {
            return new BigDecimal(value.getAsString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isBoolean(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static String actualType(JsonElement value) {
        if (value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonObject()) {
            return "object";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return "boolean";
        }
        if (primitive.isNumber()) {
            return isInteger(primitive) ? "integer" : "number";
        }
        return "string";
    }

    private static String propertyPath(String parent, String property) {
        if (SIMPLE_PROPERTY.matcher(property).matches()) {
            return parent + "." + property;
        }
        return parent + "[" + new JsonPrimitive(property) + "]";
    }

    private static Optional<Violation> violation(String path, String message) {
        return Optional.of(new Violation(path, message));
    }

    record Violation(String path, String message) {
        Violation {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(message, "message");
        }
    }
}
