package cc.kites.mineclaw.workspace;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** One immutable Schema-2 tools.yml entry, including disabled and invalid diagnostics. */
public record ToolDefinition(
        int index,
        String handler,
        JsonObject payload,
        boolean declaredEnabled,
        Status status,
        Optional<String> diagnostic
) {
    public ToolDefinition {
        if (index < 1) {
            throw new IllegalArgumentException("index must be one-based");
        }
        handler = Objects.requireNonNull(handler, "handler");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        status = Objects.requireNonNull(status, "status");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        if (status == Status.INVALID && diagnostic.isEmpty()) {
            throw new IllegalArgumentException("invalid tools require a diagnostic");
        }
        if (status != Status.INVALID) {
            if (Handler.fromWireName(handler).isEmpty() || payload.isEmpty()) {
                throw new IllegalArgumentException("valid tools require a registered handler and payload");
            }
        }
    }

    @Override
    public JsonObject payload() {
        return payload.deepCopy();
    }

    public boolean available() {
        return status == Status.ENABLED;
    }

    /** Resolves the exact built-in handler named by the schema field. */
    public Optional<Handler> registeredHandler() {
        return Handler.fromWireName(handler);
    }

    public String payloadType() {
        return payload.has("type") && payload.get("type").isJsonPrimitive()
                ? payload.get("type").getAsString() : "";
    }

    /** Function-call name for locally dispatched Tool types. */
    public String modelFunctionName() {
        if (!payload.has("function") || !payload.get("function").isJsonObject()) {
            return "";
        }
        JsonObject function = payload.getAsJsonObject("function");
        return function.has("name") && function.get("name").isJsonPrimitive()
                ? function.get("name").getAsString() : "";
    }

    /** Declared argument Schema for a locally dispatched Function Tool. */
    public JsonObject parameters() {
        if (!payload.has("function") || !payload.get("function").isJsonObject()) {
            return new JsonObject();
        }
        JsonObject function = payload.getAsJsonObject("function");
        return function.has("parameters") && function.get("parameters").isJsonObject()
                ? function.getAsJsonObject("parameters").deepCopy() : new JsonObject();
    }

    /** Exact model API Tool declaration. */
    public JsonObject toChatCompletionsTool() {
        if (!available()) {
            throw new IllegalStateException("Tool " + printableHandler() + " is not enabled");
        }
        return payload.deepCopy();
    }

    public String printableHandler() {
        return handler.isBlank() ? "entry #" + index : handler;
    }

    ToolDefinition withDiagnostic(String contextualDiagnostic) {
        if (status != Status.INVALID) {
            return this;
        }
        return new ToolDefinition(index, handler, payload, false,
                Status.INVALID, Optional.of(contextualDiagnostic));
    }

    ToolDefinition duplicateInvalid(String diagnostic) {
        return new ToolDefinition(index, handler, payload, false,
                Status.INVALID, Optional.of(diagnostic));
    }

    static ToolDefinition tool(int index, String handler, JsonObject payload,
                               boolean declaredEnabled, Status status, String diagnostic) {
        return new ToolDefinition(index, handler, payload, declaredEnabled, status,
                Optional.ofNullable(diagnostic));
    }

    static ToolDefinition invalid(int index, String handler, String diagnostic) {
        return new ToolDefinition(index, handler, new JsonObject(), false,
                Status.INVALID, Optional.of(diagnostic));
    }

    public enum Status {
        ENABLED,
        DISABLED,
        INVALID
    }

    public enum Handler {
        PLAYER_SNAPSHOT("player_snapshot"),
        ITEM_INSPECT("item_inspect"),
        BLOCK_INSPECT("block_inspect"),
        ONLINE_PLAYERS("online_players"),
        LIST("list"),
        READ("read"),
        GREP("grep"),
        RUN_COMMAND("run_command"),
        CALL_FUNCTION("call_function");

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
            return Arrays.stream(values()).filter(handler -> handler.wireName.equals(value)).findFirst();
        }
    }
}
