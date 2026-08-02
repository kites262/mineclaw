package cc.kites.mineclaw.commandexec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** A validated {@code run_command} request with a canonical command used for dispatch and matching. */
public record CommandRequest(String command, String intent, Optional<String> player) {
    public static final Limits DEFAULT_LIMITS = new Limits(512, 1_024, 64);

    public CommandRequest {
        command = Objects.requireNonNull(command, "command");
        intent = Objects.requireNonNull(intent, "intent");
        player = Objects.requireNonNull(player, "player");
    }

    /** Parses the three required JSON members. An explicit JSON null selects the console. */
    public static CommandRequest parse(JsonObject arguments) {
        return parse(arguments, DEFAULT_LIMITS);
    }

    public static CommandRequest parse(JsonObject arguments, Limits limits) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(limits, "limits");
        requireMember(arguments, "command");
        requireMember(arguments, "intent");
        requireMember(arguments, "player");

        String command = requiredString(arguments.get("command"), "command");
        String intent = requiredString(arguments.get("intent"), "intent");
        JsonElement rawPlayer = arguments.get("player");
        Optional<String> player = rawPlayer.isJsonNull()
                ? Optional.empty()
                : Optional.of(requiredString(rawPlayer, "player"));

        command = normalizeCommand(command, limits.maxCommandCodePoints());
        intent = normalizeText(intent, "intent", limits.maxIntentCodePoints(), true);
        player = player.map(value -> normalizePlayer(value, limits.maxPlayerCodePoints()));
        return new CommandRequest(command, intent, player);
    }

    /** Lower-case full command used with {@link java.util.regex.Matcher#matches()}. */
    public String normalizedCommand() {
        return command.toLowerCase(Locale.ROOT);
    }

    public boolean console() {
        return player.isEmpty();
    }

    private static String normalizeCommand(String raw, int maximum) {
        String value = normalizeText(raw, "command", maximum, true);
        if (value.startsWith("/")) {
            value = collapseWhitespace(value.substring(1));
        }
        if (value.isBlank()) {
            throw invalid("command must not be blank");
        }
        return value;
    }

    private static String normalizePlayer(String raw, int maximum) {
        String value = normalizeText(raw, "player", maximum, false);
        if (value.codePoints().anyMatch(CommandRequest::space)) {
            throw invalid("player must be a UUID or exact player name without whitespace");
        }
        return value;
    }

    private static String normalizeText(String raw, String field, int maximum, boolean collapse) {
        if (raw.codePointCount(0, raw.length()) > maximum) {
            throw invalid(field + " is longer than " + maximum + " code points");
        }
        if (raw.codePoints().anyMatch(CommandRequest::control)) {
            throw invalid(field + " contains a line break or control character");
        }
        String value = collapse ? collapseWhitespace(raw) : raw.strip();
        if (value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value;
    }

    private static String collapseWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean betweenWords = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (space(codePoint)) {
                betweenWords = result.length() > 0;
            } else {
                if (betweenWords) {
                    result.append(' ');
                    betweenWords = false;
                }
                result.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean control(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static String requiredString(JsonElement value, String field) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw invalid(field + " must be a string");
        }
        return primitive.getAsString();
    }

    private static void requireMember(JsonObject arguments, String field) {
        if (!arguments.has(field)) {
            throw invalid("missing required field: " + field);
        }
    }

    private static InvalidCommandRequestException invalid(String message) {
        return new InvalidCommandRequestException(message);
    }

    public record Limits(int maxCommandCodePoints, int maxIntentCodePoints, int maxPlayerCodePoints) {
        public Limits {
            if (maxCommandCodePoints < 1 || maxIntentCodePoints < 1 || maxPlayerCodePoints < 1) {
                throw new IllegalArgumentException("command request limits must be positive");
            }
        }
    }

    public static final class InvalidCommandRequestException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public InvalidCommandRequestException(String message) {
            super(message);
        }
    }
}
