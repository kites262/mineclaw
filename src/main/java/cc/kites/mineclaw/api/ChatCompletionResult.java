package cc.kites.mineclaw.api;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/** The normalized final state of a streamed or ordinary model API response. */
public record ChatCompletionResult(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        ApiUsage usage,
        String interleavedValue,
        List<JsonObject> responseOutputItems
) {
    public ChatCompletionResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        interleavedValue = interleavedValue == null ? "" : interleavedValue;
        responseOutputItems = responseOutputItems == null ? List.of() : responseOutputItems.stream()
                .map(item -> Objects.requireNonNull(item, "responseOutputItem").deepCopy())
                .toList();
    }

    public ChatCompletionResult(String content, List<ToolCall> toolCalls,
                                String finishReason, ApiUsage usage) {
        this(content, toolCalls, finishReason, usage, "", List.of());
    }

    public ChatCompletionResult(String content, List<ToolCall> toolCalls,
                                String finishReason, ApiUsage usage,
                                String interleavedValue) {
        this(content, toolCalls, finishReason, usage, interleavedValue, List.of());
    }

    @Override
    public List<JsonObject> responseOutputItems() {
        return responseOutputItems.stream().map(JsonObject::deepCopy).toList();
    }
}
