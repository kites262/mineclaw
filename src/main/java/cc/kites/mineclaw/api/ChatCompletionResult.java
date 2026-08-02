package cc.kites.mineclaw.api;

import java.util.List;

/** The normalized final state of a streamed or ordinary Chat Completions response. */
public record ChatCompletionResult(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        ApiUsage usage
) {
    public ChatCompletionResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
