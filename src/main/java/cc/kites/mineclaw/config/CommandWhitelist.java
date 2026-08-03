package cc.kites.mineclaw.config;

import cc.kites.mineclaw.commandexec.CommandRules;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable whitelist.yml snapshot for model-facing native {@code run_command} dispatch. */
public record CommandWhitelist(boolean enabled, List<Pattern> player, List<Pattern> console) {
    public static final int SCHEMA = 1;

    public CommandWhitelist {
        player = List.copyOf(Objects.requireNonNull(player, "player"));
        console = List.copyOf(Objects.requireNonNull(console, "console"));
    }

    public CommandRules rules() {
        return new CommandRules(enabled, player, console);
    }
}
