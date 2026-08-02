package cc.kites.mineclaw.api;

/** A normalized OpenAI function tool call. */
public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        arguments = arguments == null ? "" : arguments;
    }
}
