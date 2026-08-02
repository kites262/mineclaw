package cc.kites.mineclaw.commandexec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDispatchResultTest {
    @Test
    void factoriesRepresentEveryDispatchOutcome() {
        assertThat(List.of(
                CommandDispatchResult.consoleDispatched("feedback").outcome(),
                CommandDispatchResult.playerDispatched().outcome(),
                CommandDispatchResult.playerOffline().outcome(),
                CommandDispatchResult.commandNotFound().outcome(),
                CommandDispatchResult.dispatchRejected().outcome(),
                CommandDispatchResult.executionException("failure").outcome(),
                CommandDispatchResult.resultUnknown("missing").outcome()
        )).containsExactly(CommandDispatchResult.Outcome.values());
    }

    @Test
    void playerDispatchCannotCarryCapturedFeedback() {
        assertThatThrownBy(() -> new CommandDispatchResult(
                CommandDispatchResult.Outcome.PLAYER_DISPATCHED, "not interceptable", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not expose");
    }

    @Test
    void feedbackAndDiagnosticDetailsAreBounded() {
        CommandDispatchResult result = new CommandDispatchResult(
                CommandDispatchResult.Outcome.EXECUTION_EXCEPTION,
                "f".repeat(CommandDispatchResult.MAX_FEEDBACK_CHARS + 100),
                "d".repeat(CommandDispatchResult.MAX_DETAIL_CHARS + 100));

        assertThat(result.feedback()).startsWith("f".repeat(100)).endsWith("...[truncated]");
        assertThat(result.detail()).startsWith("d".repeat(100)).endsWith("...[truncated]");
    }
}
