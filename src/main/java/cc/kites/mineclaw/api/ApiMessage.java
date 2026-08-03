package cc.kites.mineclaw.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A message sent to Chat Completions, including assistant tool calls and tool results. */
public record ApiMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                         Map<String, String> providerFields) {
    public ApiMessage {
        Objects.requireNonNull(role, "role");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        providerFields = providerFields == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(providerFields));
    }

    public ApiMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, Map.of());
    }

    public static ApiMessage user(String content) {
        return new ApiMessage("user", Objects.requireNonNull(content, "content"), List.of(), null);
    }

    public static ApiMessage assistant(String content) {
        return new ApiMessage("assistant", Objects.requireNonNull(content, "content"), List.of(), null);
    }

    public static ApiMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ApiMessage("assistant", null, Objects.requireNonNull(toolCalls, "toolCalls"), null);
    }

    public static ApiMessage tool(String toolCallId, String content) {
        return new ApiMessage("tool", Objects.requireNonNull(content, "content"), List.of(),
                Objects.requireNonNull(toolCallId, "toolCallId"));
    }
}
