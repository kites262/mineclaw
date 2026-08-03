package cc.kites.mineclaw.javascript;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Completion and exact cancellation hook for a host operation owned by one invocation. */
public final class OperationHandle {
    private final CompletionStage<OperationResult> completion;
    private final Runnable cancellation;

    public OperationHandle(CompletionStage<OperationResult> completion, Runnable cancellation) {
        this.completion = Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(cancellation, "cancellation");
        AtomicBoolean called = new AtomicBoolean();
        this.cancellation = () -> {
            if (called.compareAndSet(false, true)) {
                cancellation.run();
            }
        };
    }

    public CompletionStage<OperationResult> completion() {
        return completion;
    }

    public void cancel() {
        cancellation.run();
    }

    public static OperationHandle completed(OperationResult result) {
        return new OperationHandle(CompletableFuture.completedFuture(
                Objects.requireNonNull(result, "result")), () -> { });
    }
}
