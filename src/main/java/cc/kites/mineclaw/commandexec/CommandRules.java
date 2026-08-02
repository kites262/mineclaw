package cc.kites.mineclaw.commandexec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable command execution switch and full-string whitelist patterns. */
public record CommandRules(
        boolean runEnabled,
        List<Pattern> playerWhitelist,
        List<Pattern> consoleWhitelist
) {
    public CommandRules {
        playerWhitelist = copy(playerWhitelist, "playerWhitelist");
        consoleWhitelist = copy(consoleWhitelist, "consoleWhitelist");
    }

    public boolean playerAllowed(CommandRequest request) {
        return matches(playerWhitelist, Objects.requireNonNull(request, "request").normalizedCommand());
    }

    public boolean consoleAllowed(CommandRequest request) {
        return matches(consoleWhitelist, Objects.requireNonNull(request, "request").normalizedCommand());
    }

    public boolean playerAllowed(String normalizedCommand) {
        return matches(playerWhitelist, normalizedCommand);
    }

    public boolean consoleAllowed(String normalizedCommand) {
        return matches(consoleWhitelist, normalizedCommand);
    }

    private static boolean matches(List<Pattern> patterns, String normalizedCommand) {
        Objects.requireNonNull(normalizedCommand, "normalizedCommand");
        String canonical = normalizedCommand.toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(pattern -> pattern.matcher(canonical).matches());
    }

    private static List<Pattern> copy(List<Pattern> source, String field) {
        Objects.requireNonNull(source, field);
        ArrayList<Pattern> result = new ArrayList<>(source.size());
        for (Pattern pattern : source) {
            result.add(Objects.requireNonNull(pattern, field + " entry"));
        }
        return List.copyOf(result);
    }
}
