package cc.kites.mineclaw.tool;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immediate tool state plus an optional asynchronous continuation and exact cancellation hook. */
public record ToolExecution(
        ToolResult immediate,
        CompletableFuture<ToolResult> continuation,
        Runnable cancellation
) {
    public ToolExecution {
        immediate = Objects.requireNonNull(immediate, "immediate");
        cancellation = once(Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Source-compatible constructor for integrations that do not own cancellable work. */
    public ToolExecution(ToolResult immediate, CompletableFuture<ToolResult> continuation) {
        this(immediate, continuation, () -> { });
    }

    public static ToolExecution completed(ToolResult result) {
        return new ToolExecution(result, null, () -> { });
    }

    public static ToolExecution pending(ToolResult pending, CompletableFuture<ToolResult> continuation) {
        return pending(pending, continuation, () -> { });
    }

    public static ToolExecution pending(
            ToolResult pending,
            CompletableFuture<ToolResult> continuation,
            Runnable cancellation
    ) {
        return new ToolExecution(pending, Objects.requireNonNull(continuation, "continuation"), cancellation);
    }

    public boolean pending() {
        return continuation != null;
    }

    /** Cancels the logical operation once and prevents a pending continuation from retaining callers. */
    public void cancel() {
        try {
            cancellation.run();
        } finally {
            if (continuation != null) {
                continuation.cancel(true);
            }
        }
    }

    private static Runnable once(Runnable action) {
        AtomicBoolean called = new AtomicBoolean();
        return () -> {
            if (called.compareAndSet(false, true)) {
                action.run();
            }
        };
    }
}
