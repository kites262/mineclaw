package cc.kites.mineclaw.commandexec;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BukkitCommandRuntimeTest {
    @Test
    void retiredPlayerIsOfflineButLiveSchedulerFailureIsUnknown() {
        IllegalStateException retired = new IllegalStateException("entity retired");

        assertThat(BukkitCommandRuntime.playerScheduleFailure(false, retired).outcome())
                .isEqualTo(CommandDispatchResult.Outcome.PLAYER_OFFLINE);
        CommandDispatchResult stillOnline = BukkitCommandRuntime.playerScheduleFailure(true, retired);
        assertThat(stillOnline.outcome()).isEqualTo(CommandDispatchResult.Outcome.RESULT_UNKNOWN);
        assertThat(stillOnline.detail()).contains("entity retired");
    }

    @Test
    void retiredPlayerLookupBecomesOfflineButLiveLookupPreservesFailure() {
        IllegalStateException retired = new IllegalStateException("entity retired");

        assertThat(BukkitCommandRuntime.playerLookupFailure(false, retired)).isEmpty();
        assertThatThrownBy(() -> BukkitCommandRuntime.playerLookupFailure(true, retired))
                .isInstanceOf(CompletionException.class)
                .hasCause(retired);
    }

    @Test
    void synchronousFeedbackCollectorIsBoundedAndClosesBeforeLateMessages() {
        BukkitCommandRuntime.BoundedFeedback feedback = new BukkitCommandRuntime.BoundedFeedback();
        feedback.accept("x".repeat(CommandDispatchResult.MAX_FEEDBACK_CHARS + 500));

        String captured = feedback.closeAndGet();
        feedback.accept("late asynchronous output");

        assertThat(captured).hasSize(CommandDispatchResult.MAX_FEEDBACK_CHARS + 1);
        assertThat(feedback.closeAndGet()).isEqualTo(captured).doesNotContain("late asynchronous output");
        assertThat(CommandDispatchResult.consoleDispatched(captured).feedback()).endsWith("...[truncated]");
    }

    @Test
    void falseDispatchUsesBrigadierRootToSeparateMissingFromRejected() {
        assertThat(BukkitCommandRuntime.failedDispatch(CommandRootIndex.Resolution.FOUND, "denied").outcome())
                .isEqualTo(CommandDispatchResult.Outcome.DISPATCH_REJECTED);
        assertThat(BukkitCommandRuntime.failedDispatch(CommandRootIndex.Resolution.MISSING, "unknown").outcome())
                .isEqualTo(CommandDispatchResult.Outcome.COMMAND_NOT_FOUND);
        CommandDispatchResult unresolved = BukkitCommandRuntime.failedDispatch(
                CommandRootIndex.Resolution.UNKNOWN, "captured");
        assertThat(unresolved.outcome()).isEqualTo(CommandDispatchResult.Outcome.RESULT_UNKNOWN);
        assertThat(unresolved.feedback()).isEqualTo("captured");
    }
}
