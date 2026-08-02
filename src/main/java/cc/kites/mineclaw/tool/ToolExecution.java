package cc.kites.mineclaw.tool;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Immediate tool state plus an optional approval continuation. */
public record ToolExecution(ToolResult immediate, CompletableFuture<ToolResult> continuation) {
    public ToolExecution {
        immediate = Objects.requireNonNull(immediate, "immediate");
    }

    public static ToolExecution completed(ToolResult result) {
        return new ToolExecution(result, null);
    }

    public static ToolExecution pending(ToolResult pending, CompletableFuture<ToolResult> continuation) {
        return new ToolExecution(pending, Objects.requireNonNull(continuation, "continuation"));
    }

    public boolean pending() {
        return continuation != null;
    }
}
