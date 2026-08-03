package cc.kites.mineclaw.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable strict Schema compiled once at catalog load and reused for every invocation. */
public final class CompiledSchema {
    private final Node root;
    private final SchemaLimits limits;

    CompiledSchema(Node root, SchemaLimits limits) {
        this.root = Objects.requireNonNull(root, "root");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public SchemaLimits limits() {
        return limits;
    }

    /** Validates without coercion, default insertion, truncation, clamping, or mutation. */
    public ValidationResult validate(JsonElement value) {
        Objects.requireNonNull(value, "value");
        ValidationContext context = new ValidationContext(limits);
        inspectBudget(value, "$", 0, context, new IdentityHashMap<>());
        if (!context.violations.isEmpty()) {
            return new ValidationResult(context.violations);
        }
        root.validate(value, "$", context);
        return new ValidationResult(context.violations);
    }

    private static void inspectBudget(
            JsonElement value,
            String path,
            int depth,
            ValidationContext context,
            IdentityHashMap<JsonElement, Boolean> ancestors
    ) {
        if (context.full()) {
            return;
        }
        if (depth > context.limits.maxDepth()) {
            context.add(path, "maxDepth", "maximum argument depth exceeded");
            return;
        }
        if (value == null) {
            context.add(path, "type", "value must be JSON");
            return;
        }
        if (value.isJsonNull()) {
            context.addChars(4, path);
            return;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) {
                context.addChars(codePoints(primitive.getAsString()) + 2L, path);
            } else {
                context.addChars(codePoints(primitive.getAsString()), path);
            }
            return;
        }
        if (ancestors.put(value, Boolean.TRUE) != null) {
            context.add(path, "type", "cyclic JSON values are not supported");
            return;
        }
        try {
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                context.addChars(2, path);
                for (int index = 0; index < array.size() && !context.full(); index++) {
                    if (!context.addMember(path)) {
                        return;
                    }
                    inspectBudget(array.get(index), path + '[' + index + ']', depth + 1, context, ancestors);
                }
                return;
            }
            JsonObject object = value.getAsJsonObject();
            context.addChars(2, path);
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (context.full() || !context.addMember(path)) {
                    return;
                }
                String childPath = path + '.' + entry.getKey();
                context.addChars(codePoints(entry.getKey()) + 2L, childPath);
                inspectBudget(entry.getValue(), childPath, depth + 1, context, ancestors);
            }
        } finally {
            ancestors.remove(value);
        }
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    static final class Node {
        final ValueType type;
        final Map<String, Node> properties;
        final Set<String> required;
        final Node items;
        final BigDecimal minimum;
        final BigDecimal maximum;
        final BigInteger minLength;
        final BigInteger maxLength;
        final BigInteger minItems;
        final BigInteger maxItems;
        final List<JsonElement> enumeration;

        Node(
                ValueType type,
                Map<String, Node> properties,
                Set<String> required,
                Node items,
                BigDecimal minimum,
                BigDecimal maximum,
                BigInteger minLength,
                BigInteger maxLength,
                BigInteger minItems,
                BigInteger maxItems,
                List<JsonElement> enumeration
        ) {
            this.type = Objects.requireNonNull(type, "type");
            this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            this.required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
            this.items = items;
            this.minimum = minimum;
            this.maximum = maximum;
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.enumeration = enumeration.stream().map(JsonElement::deepCopy).toList();
        }

        void validate(JsonElement value, String path, ValidationContext context) {
            if (context.full()) {
                return;
            }
            if (!type.matches(value)) {
                context.add(path, "type", "must be of type " + type.wireName);
                return;
            }
            if (!enumeration.isEmpty()
                    && enumeration.stream().noneMatch(candidate -> scalarEquals(candidate, value))) {
                context.add(path, "enum", "must be one of the declared enum values");
                if (context.full()) {
                    return;
                }
            }
            switch (type) {
                case OBJECT -> validateObject(value.getAsJsonObject(), path, context);
                case ARRAY -> validateArray(value.getAsJsonArray(), path, context);
                case STRING -> validateString(value.getAsString(), path, context);
                case INTEGER, NUMBER -> validateNumber(decimal(value), path, context);
                case BOOLEAN, NULL -> { }
            }
        }

        private void validateObject(JsonObject object, String path, ValidationContext context) {
            for (String name : required) {
                if (!object.has(name)) {
                    context.add(path + '.' + name, "required", "required property is missing");
                    if (context.full()) {
                        return;
                    }
                }
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                Node property = properties.get(entry.getKey());
                String childPath = path + '.' + entry.getKey();
                if (property == null) {
                    context.add(childPath, "additionalProperties", "property is not allowed");
                } else {
                    property.validate(entry.getValue(), childPath, context);
                }
                if (context.full()) {
                    return;
                }
            }
        }

        private void validateArray(JsonArray array, String path, ValidationContext context) {
            BigInteger length = BigInteger.valueOf(array.size());
            if (minItems != null && length.compareTo(minItems) < 0) {
                context.add(path, "minItems", "must contain at least " + minItems + " items");
            }
            if (maxItems != null && length.compareTo(maxItems) > 0) {
                context.add(path, "maxItems", "must contain at most " + maxItems + " items");
            }
            if (items == null) {
                return;
            }
            for (int index = 0; index < array.size() && !context.full(); index++) {
                items.validate(array.get(index), path + '[' + index + ']', context);
            }
        }

        private void validateString(String value, String path, ValidationContext context) {
            BigInteger length = BigInteger.valueOf(codePoints(value));
            if (minLength != null && length.compareTo(minLength) < 0) {
                context.add(path, "minLength", "must contain at least " + minLength + " code points");
            }
            if (maxLength != null && length.compareTo(maxLength) > 0) {
                context.add(path, "maxLength", "must contain at most " + maxLength + " code points");
            }
        }

        private void validateNumber(BigDecimal value, String path, ValidationContext context) {
            if (minimum != null && value.compareTo(minimum) < 0) {
                context.add(path, "minimum", "must be greater than or equal to " + display(minimum));
            }
            if (maximum != null && value.compareTo(maximum) > 0) {
                context.add(path, "maximum", "must be less than or equal to " + display(maximum));
            }
        }
    }

    enum ValueType {
        OBJECT("object"), ARRAY("array"), STRING("string"), INTEGER("integer"),
        NUMBER("number"), BOOLEAN("boolean"), NULL("null");

        final String wireName;

        ValueType(String wireName) {
            this.wireName = wireName;
        }

        static ValueType fromWireName(String value) {
            for (ValueType candidate : values()) {
                if (candidate.wireName.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unsupported Schema type");
        }

        boolean matches(JsonElement value) {
            return switch (this) {
                case OBJECT -> value.isJsonObject();
                case ARRAY -> value.isJsonArray();
                case STRING -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                case BOOLEAN -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case NULL -> value.isJsonNull();
                case NUMBER -> finiteNumber(value);
                case INTEGER -> finiteNumber(value) && decimal(value).stripTrailingZeros().scale() <= 0;
            };
        }
    }

    private static final class ValidationContext {
        private final SchemaLimits limits;
        private final ArrayList<SchemaViolation> violations = new ArrayList<>();
        private long chars;
        private int members;
        private boolean characterLimitReported;
        private boolean memberLimitReported;

        private ValidationContext(SchemaLimits limits) {
            this.limits = limits;
        }

        boolean full() {
            return violations.size() >= limits.maxViolations();
        }

        void add(String path, String keyword, String message) {
            if (!full()) {
                violations.add(new SchemaViolation(path, keyword, message));
            }
        }

        void addChars(long amount, String path) {
            chars = Math.min(Long.MAX_VALUE, chars + amount);
            if (chars > limits.maxChars() && !characterLimitReported) {
                characterLimitReported = true;
                add(path, "maxChars", "maximum argument character count exceeded");
            }
        }

        boolean addMember(String path) {
            if (members < Integer.MAX_VALUE) {
                members++;
            }
            if (members > limits.maxMembers()) {
                if (!memberLimitReported) {
                    memberLimitReported = true;
                    add(path, "maxMembers", "maximum argument member count exceeded");
                }
                return false;
            }
            return true;
        }
    }

    static boolean finiteNumber(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            decimal(value);
            double asDouble = value.getAsDouble();
            return Double.isFinite(asDouble) || decimal(value).abs().compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) > 0;
        } catch (NumberFormatException | ArithmeticException exception) {
            return false;
        }
    }

    static BigDecimal decimal(JsonElement value) {
        return new BigDecimal(value.getAsString());
    }

    static boolean scalarEquals(JsonElement left, JsonElement right) {
        if (left.isJsonNull() || right.isJsonNull()) {
            return left.isJsonNull() && right.isJsonNull();
        }
        if (!left.isJsonPrimitive() || !right.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive first = left.getAsJsonPrimitive();
        JsonPrimitive second = right.getAsJsonPrimitive();
        if (first.isNumber() || second.isNumber()) {
            return first.isNumber() && second.isNumber()
                    && finiteNumber(left) && finiteNumber(right)
                    && decimal(left).compareTo(decimal(right)) == 0;
        }
        if (first.isBoolean() || second.isBoolean()) {
            return first.isBoolean() && second.isBoolean()
                    && first.getAsBoolean() == second.getAsBoolean();
        }
        return first.isString() && second.isString()
                && first.getAsString().equals(second.getAsString());
    }

    static String display(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }
}
