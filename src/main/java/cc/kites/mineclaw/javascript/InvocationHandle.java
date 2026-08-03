package cc.kites.mineclaw.javascript;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Public handle for observing or cancelling one JavaScript FunctionInvocation. */
public final class InvocationHandle {
    private final String invocationId;
    private final CompletableFuture<ScriptResult> result;
    private final Runnable cancellation;

    InvocationHandle(String invocationId, CompletableFuture<ScriptResult> result, Runnable cancellation) {
        this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
        this.result = Objects.requireNonNull(result, "result").copy();
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public String invocationId() {
        return invocationId;
    }

    public CompletableFuture<ScriptResult> result() {
        return result;
    }

    public void cancel() {
        cancellation.run();
    }
}
