package cc.kites.mineclaw.workspace;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** One tools.yml entry, including disabled and invalid entries needed by operator diagnostics. */
public record ToolDefinition(
        int index,
        String name,
        String handlerName,
        Optional<Handler> handler,
        String description,
        JsonObject parameters,
        boolean declaredEnabled,
        Status status,
        Optional<String> diagnostic
) {
    public ToolDefinition {
        if (index < 1) {
            throw new IllegalArgumentException("index must be one-based");
        }
        name = Objects.requireNonNull(name, "name");
        handlerName = Objects.requireNonNull(handlerName, "handlerName");
        handler = Objects.requireNonNull(handler, "handler");
        description = Objects.requireNonNull(description, "description");
        parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
        status = Objects.requireNonNull(status, "status");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        if (status == Status.INVALID && diagnostic.isEmpty()) {
            throw new IllegalArgumentException("invalid tools require a diagnostic");
        }
        if (status != Status.INVALID && handler.isEmpty()) {
            throw new IllegalArgumentException("valid tools require a supported handler");
        }
    }

    @Override
    public JsonObject parameters() {
        return parameters.deepCopy();
    }

    public boolean available() {
        return status == Status.ENABLED;
    }

    /** OpenAI Chat Completions function-tool representation. */
    public JsonObject toChatCompletionsTool() {
        if (!available()) {
            throw new IllegalStateException("Tool " + printableName() + " is not enabled");
        }
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters.deepCopy());

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    public String printableName() {
        return name.isBlank() ? "entry #" + index : name;
    }

    static ToolDefinition valid(
            int index,
            String name,
            Handler handler,
            String description,
            JsonObject parameters,
            boolean declaredEnabled,
            Status status,
            String diagnostic
    ) {
        return new ToolDefinition(index, name, handler.wireName(), Optional.of(handler), description, parameters,
                declaredEnabled, status, Optional.ofNullable(diagnostic));
    }

    static ToolDefinition invalid(int index, String name, String handlerName, String diagnostic) {
        return new ToolDefinition(index, name, handlerName, Handler.fromWireName(handlerName), "",
                new JsonObject(), false, Status.INVALID, Optional.of(diagnostic));
    }

    public enum Status {
        ENABLED,
        DISABLED,
        INVALID
    }

    public enum Handler {
        LOOK_BLOCK("look_block"),
        FEET_BLOCK("feet_block"),
        INVENTORY("inventory"),
        ONLINE_PLAYERS("online_players"),
        LIST("list"),
        READ("read"),
        GREP("grep"),
        RUN_COMMAND("run_command");

        private final String wireName;

        Handler(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<Handler> fromWireName(String value) {
            if (value == null) {
                return Optional.empty();
            }
            return Arrays.stream(values())
                    .filter(handler -> handler.wireName.equals(value))
                    .findFirst();
        }
    }
}
