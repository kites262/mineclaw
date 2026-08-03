package cc.kites.mineclaw.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Strict all-or-nothing whitelist.yml Schema-1 loader. */
public final class CommandWhitelistLoader {
    public static final String FILE_NAME = "whitelist.yml";

    public CommandWhitelist load(Path dataRoot, Path path) throws ConfigException {
        return parse(StrictYaml.load(dataRoot, path, FILE_NAME));
    }

    public CommandWhitelist parse(String source) throws ConfigException {
        return parse(StrictYaml.parse(source, FILE_NAME));
    }

    private static CommandWhitelist parse(JsonObject root) throws ConfigException {
        exact(root, Set.of("schema", "enabled", "player", "console"));
        integer(root.get("schema"), "$.schema", 1, 1);
        boolean enabled = bool(root.get("enabled"), "$.enabled");
        return new CommandWhitelist(enabled, patterns(root.get("player"), "$.player"),
                patterns(root.get("console"), "$.console"));
    }

    private static List<Pattern> patterns(JsonElement value, String path) throws ConfigException {
        if (value == null || !value.isJsonArray()) {
            throw invalid(path + " must be an array");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > 256) {
            throw invalid(path + " must not contain more than 256 entries");
        }
        HashSet<String> seen = new HashSet<>();
        ArrayList<Pattern> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String itemPath = path + '[' + index + ']';
            if (!(array.get(index) instanceof JsonPrimitive primitive) || !primitive.isString()) {
                throw invalid(itemPath + " must be a string");
            }
            String source = primitive.getAsString();
            int length = source.codePointCount(0, source.length());
            if (length < 1 || length > 512 || source.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid(itemPath + " must contain 1-512 code points without controls");
            }
            if (!seen.add(source)) {
                throw invalid(itemPath + " duplicates an earlier regular expression");
            }
            try {
                result.add(Pattern.compile(source));
            } catch (PatternSyntaxException exception) {
                throw invalid(itemPath + " is not a valid Java regular expression");
            }
        }
        return List.copyOf(result);
    }

    private static void exact(JsonObject object, Set<String> fields) throws ConfigException {
        for (String field : object.keySet()) {
            if (!fields.contains(field)) {
                throw invalid("$." + field + " is not supported");
            }
        }
        for (String field : fields) {
            if (!object.has(field)) {
                throw invalid("$." + field + " is required");
            }
        }
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
            long result = new java.math.BigDecimal(primitive.getAsString()).longValueExact();
            if (result < minimum || result > maximum) {
                throw invalid(path + " is outside the supported range");
            }
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid(path + " must be an integer");
        }
    }

    private static ConfigException invalid(String message) {
        return new ConfigException(message);
    }
}
