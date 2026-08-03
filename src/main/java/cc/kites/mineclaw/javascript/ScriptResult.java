package cc.kites.mineclaw.javascript;

import com.google.gson.JsonObject;

import java.util.Objects;

/** Validated final JavaScript Function result or a runtime-generated terminal state. */
public record ScriptResult(String status, JsonObject output) {
    public ScriptResult {
        status = Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output").deepCopy();
    }

    @Override
    public JsonObject output() {
        return output.deepCopy();
    }
}
