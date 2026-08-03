package cc.kites.mineclaw.turn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManualCompactionQueueTest {
    @Test
    void queuesBehindATurnAndDeduplicatesAdditionalRequests() {
        ManualCompactionQueue<String> queue = new ManualCompactionQueue<>();

        var first = queue.submit(true);
        var duplicate = queue.submit(true);

        assertThat(first.admission()).isEqualTo(ManualCompactionQueue.Admission.QUEUED);
        assertThat(duplicate.admission()).isEqualTo(ManualCompactionQueue.Admission.ALREADY_PENDING);
        assertThat(duplicate.completion()).isSameAs(first.completion());
        assertThat(queue.blocksTurns()).isFalse();

        ManualCompactionQueue.Work work = queue.startIfIdle(false).orElseThrow();
        assertThat(queue.blocksTurns()).isTrue();
        assertThat(queue.finish(work, "success")).isTrue();
        assertThat(first.completion()).isCompletedWithValue("success");
        assertThat(queue.blocksTurns()).isFalse();
    }

    @Test
    void startsImmediatelyWhenIdleAndRejectsAStaleCompletionAfterCancellation() {
        ManualCompactionQueue<String> queue = new ManualCompactionQueue<>();
        var submission = queue.submit(false);
        ManualCompactionQueue.Work stale = submission.work().orElseThrow();

        assertThat(submission.admission()).isEqualTo(ManualCompactionQueue.Admission.STARTED);
        assertThat(queue.cancel("cancelled")).isTrue();
        assertThat(submission.completion()).isCompletedWithValue("cancelled");
        assertThat(queue.finish(stale, "late")).isFalse();

        var next = queue.submit(false);
        assertThat(next.work()).isPresent();
        assertThat(next.work().orElseThrow()).isNotEqualTo(stale);
    }
}
