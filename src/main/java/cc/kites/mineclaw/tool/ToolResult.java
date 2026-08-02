package cc.kites.mineclaw.tool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Objects;

/** A structured result and its harness-level status. */
public record ToolResult(String status, JsonObject output) {
    private static final Gson GSON = new Gson();

    public ToolResult {
        status = Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
    }

    public String json() {
        return GSON.toJson(output);
    }

    public static ToolResult simple(String status, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("message", message);
        return new ToolResult(status, output);
    }
}
