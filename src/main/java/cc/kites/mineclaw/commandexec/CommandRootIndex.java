package cc.kites.mineclaw.commandexec;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Locale;
import java.util.Objects;

/** Reload-aware view of Paper's Brigadier root used to classify a false dispatch result. */
public final class CommandRootIndex {
    private volatile CommandDispatcher<CommandSourceStack> dispatcher;

    /** Publishes the live dispatcher supplied by Paper's COMMANDS lifecycle event. */
    public void publish(CommandDispatcher<CommandSourceStack> current) {
        dispatcher = Objects.requireNonNull(current, "current");
    }

    /**
     * Checks root existence without evaluating sender-specific {@code requires} predicates.
     * A present but inaccessible root is therefore distinguishable from a missing command.
     */
    Resolution resolve(String command) {
        CommandDispatcher<CommandSourceStack> current = dispatcher;
        if (current == null) {
            return Resolution.UNKNOWN;
        }
        String label = commandLabel(command);
        if (label.isEmpty()) {
            return Resolution.MISSING;
        }
        return current.getRoot().getChild(label) == null ? Resolution.MISSING : Resolution.FOUND;
    }

    static String commandLabel(String command) {
        Objects.requireNonNull(command, "command");
        String value = command.stripLeading();
        if (value.startsWith("/")) {
            value = value.substring(1).stripLeading();
        }
        int boundary = 0;
        while (boundary < value.length()) {
            int codePoint = value.codePointAt(boundary);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                break;
            }
            boundary += Character.charCount(codePoint);
        }
        return value.substring(0, boundary).toLowerCase(Locale.ROOT);
    }

    enum Resolution {
        FOUND,
        MISSING,
        UNKNOWN
    }
}
