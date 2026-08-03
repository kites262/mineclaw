package cc.kites.mineclaw.turn;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Single-slot, deduplicating queue for a maintenance operation that must not overlap a Turn. */
final class ManualCompactionQueue<T> {
    private CompletableFuture<T> completion;
    private boolean running;
    private long generation;

    synchronized Submission<T> submit(boolean turnActive) {
        if (completion != null && !completion.isDone()) {
            return new Submission<>(Admission.ALREADY_PENDING, completion, Optional.empty());
        }
        generation++;
        completion = new CompletableFuture<>();
        if (turnActive) {
            return new Submission<>(Admission.QUEUED, completion, Optional.empty());
        }
        running = true;
        return new Submission<>(Admission.STARTED, completion,
                Optional.of(new Work(generation)));
    }

    synchronized Optional<Work> startIfIdle(boolean turnActive) {
        if (turnActive || running || completion == null || completion.isDone()) {
            return Optional.empty();
        }
        running = true;
        return Optional.of(new Work(generation));
    }

    synchronized boolean finish(Work work, T result) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(result, "result");
        if (!running || completion == null || completion.isDone()
                || work.generation() != generation) {
            return false;
        }
        CompletableFuture<T> target = completion;
        completion = null;
        running = false;
        target.complete(result);
        return true;
    }

    synchronized boolean cancel(T result) {
        Objects.requireNonNull(result, "result");
        generation++;
        running = false;
        if (completion == null || completion.isDone()) {
            completion = null;
            return false;
        }
        CompletableFuture<T> target = completion;
        completion = null;
        target.complete(result);
        return true;
    }

    synchronized boolean blocksTurns() {
        return running;
    }

    enum Admission {
        STARTED,
        QUEUED,
        ALREADY_PENDING
    }

    record Work(long generation) { }

    record Submission<T>(Admission admission, CompletableFuture<T> completion,
                         Optional<Work> work) {
        Submission {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(completion, "completion");
            Objects.requireNonNull(work, "work");
        }
    }
}
