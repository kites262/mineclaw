package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.javascript.ScriptResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Produces the only model-visible result envelope accepted for {@code call_function}. */
public final class CallFunctionResultFactory {
    private static final Set<String> FINAL_STATUSES = Set.of(
            "ok", "denied", "invalid", "recoverable_error", "terminal_error", "cancelled");
    private static final Set<String> SCRIPT_STATUSES = Set.of(
            "ok", "denied", "invalid", "recoverable_error", "terminal_error", "cancelled");
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9_]*");

    private CallFunctionResultFactory() {
    }

    public static ToolResult result(String status, String functionName, JsonObject output) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(output, "output");
        if (!FINAL_STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported call_function status " + status);
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("status", status);
        envelope.add("function", functionName == null ? JsonNull.INSTANCE : string(functionName));
        envelope.add("output", output.deepCopy());
        return new ToolResult(status, envelope);
    }

    public static ToolResult invalidCall(String functionName) {
        return failure("invalid", functionName, "invalid_call_arguments",
                "call_function 参数不符合声明");
    }

    public static ToolResult unavailable(String functionName) {
        return failure("invalid", functionName, "function_unavailable",
                "Function 不存在或当前不可用");
    }

    public static ToolResult invalidArguments(String functionName, JsonArray violations) {
        Objects.requireNonNull(violations, "violations");
        JsonObject output = failureOutput("invalid_arguments", "Function 参数不符合声明");
        output.add("violations", violations.deepCopy());
        return result("invalid", functionName, output);
    }

    public static ToolResult failure(String status, String functionName, String code, String message) {
        return result(status, functionName, failureOutput(code, message));
    }

    public static ToolResult fromScript(String functionName, ScriptResult scriptResult) {
        if (scriptResult == null || !SCRIPT_STATUSES.contains(scriptResult.status())) {
            return invalidScript(functionName);
        }
        JsonObject output = scriptResult.output();
        if (output.has("status") || output.has("function")) {
            return invalidScript(functionName);
        }
        if (!scriptResult.status().equals("ok")) {
            JsonElement code = output.get("error_code");
            JsonElement message = output.get("message");
            if (!text(code) || !ERROR_CODE.matcher(code.getAsString()).matches()
                    || !text(message) || message.getAsString().isBlank()) {
                return invalidScript(functionName);
            }
        } else if (text(output.get("error_code"))
                && output.get("error_code").getAsString().equals("none")) {
            return invalidScript(functionName);
        }
        return result(scriptResult.status(), functionName, output);
    }

    public static ToolResult invalidScript(String functionName) {
        return failure("invalid", functionName, "invalid_script_result",
                "Function 返回值不符合声明");
    }

    private static JsonObject failureOutput(String code, String message) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (!ERROR_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid error code");
        }
        JsonObject output = new JsonObject();
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        return output;
    }

    private static com.google.gson.JsonPrimitive string(String value) {
        return new com.google.gson.JsonPrimitive(Objects.requireNonNull(value, "functionName"));
    }

    private static boolean text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
