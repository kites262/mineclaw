package cc.kites.mineclaw.commandexec;

import java.util.Objects;

/**
 * Outcome observed at the Bukkit command-dispatch boundary.
 *
 * <p>A player dispatch can only confirm that Bukkit accepted the command. It deliberately carries
 * no command feedback and does not claim that the command's effect succeeded. Console dispatch may
 * include feedback emitted synchronously to its temporary sender, but that feedback still does not
 * prove that the command's effect succeeded.</p>
 */
public record CommandDispatchResult(Outcome outcome, String feedback, String detail) {
    /** Soft cap so long list output cannot explode tool context. */
    public static final int MAX_FEEDBACK_CHARS = 8_000;
    /** Exception and diagnostic details need much less room than command output. */
    public static final int MAX_DETAIL_CHARS = 1_000;

    public CommandDispatchResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        feedback = truncate(feedback, MAX_FEEDBACK_CHARS, "\n...[truncated]");
        detail = truncate(detail, MAX_DETAIL_CHARS, "...[truncated]");
        if (outcome == Outcome.PLAYER_DISPATCHED && !feedback.isEmpty()) {
            throw new IllegalArgumentException("player dispatch must not expose command feedback");
        }
    }

    public static CommandDispatchResult consoleDispatched(String feedback) {
        return new CommandDispatchResult(Outcome.CONSOLE_DISPATCHED, feedback, "");
    }

    public static CommandDispatchResult playerDispatched() {
        return new CommandDispatchResult(Outcome.PLAYER_DISPATCHED, "", "");
    }

    public static CommandDispatchResult playerOffline() {
        return new CommandDispatchResult(Outcome.PLAYER_OFFLINE, "", "");
    }

    public static CommandDispatchResult commandNotFound() {
        return commandNotFound("");
    }

    public static CommandDispatchResult commandNotFound(String feedback) {
        return new CommandDispatchResult(Outcome.COMMAND_NOT_FOUND, feedback, "");
    }

    public static CommandDispatchResult dispatchRejected() {
        return dispatchRejected("");
    }

    public static CommandDispatchResult dispatchRejected(String feedback) {
        return new CommandDispatchResult(Outcome.DISPATCH_REJECTED, feedback, "");
    }

    public static CommandDispatchResult executionException(String detail) {
        return executionException(detail, "");
    }

    public static CommandDispatchResult executionException(String detail, String feedback) {
        return new CommandDispatchResult(Outcome.EXECUTION_EXCEPTION, feedback, detail);
    }

    public static CommandDispatchResult resultUnknown(String detail) {
        return resultUnknown(detail, "");
    }

    public static CommandDispatchResult resultUnknown(String detail, String feedback) {
        return new CommandDispatchResult(Outcome.RESULT_UNKNOWN, feedback, detail);
    }

    public enum Outcome {
        /** Console dispatch returned true; synchronous feedback is captured but effects remain unknown. */
        CONSOLE_DISPATCHED,
        /** Player.performCommand accepted the dispatch; the actual execution result is unknown. */
        PLAYER_DISPATCHED,
        PLAYER_OFFLINE,
        COMMAND_NOT_FOUND,
        DISPATCH_REJECTED,
        EXECUTION_EXCEPTION,
        RESULT_UNKNOWN
    }

    private static String truncate(String value, int maximum, String suffix) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum) + suffix;
    }
}
