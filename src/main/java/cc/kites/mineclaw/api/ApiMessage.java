package cc.kites.mineclaw.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A message sent to Chat Completions, including assistant tool calls and tool results. */
public record ApiMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                         Map<String, String> providerFields, String name) {
    public ApiMessage {
        Objects.requireNonNull(role, "role");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        providerFields = providerFields == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(providerFields));
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public ApiMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                      Map<String, String> providerFields) {
        this(role, content, toolCalls, toolCallId, providerFields, null);
    }

    public ApiMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, Map.of(), null);
    }

    public static ApiMessage user(String content) {
        return new ApiMessage("user", Objects.requireNonNull(content, "content"), List.of(), null,
                Map.of(), null);
    }

    /** A player-authored user message whose name distinguishes participants in the public Session. */
    public static ApiMessage user(String name, String content) {
        return new ApiMessage("user", Objects.requireNonNull(content, "content"), List.of(), null,
                Map.of(), Objects.requireNonNull(name, "name"));
    }

    /** Content sent to models, with a portable identity marker for APIs that ignore {@code name}. */
    public String modelContent() {
        return modelContent(true);
    }

    /** Content sent to models, optionally carrying the portable player identity marker. */
    public String modelContent(boolean includePlayerPrefix) {
        if (!includePlayerPrefix || !role.equals("user") || name == null || content == null) {
            return content;
        }
        return "<player>" + escapePlayerName(name) + "</player>\n" + content;
    }

    private static String escapePlayerName(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
