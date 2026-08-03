package cc.kites.mineclaw.javascript;

import com.google.gson.JsonObject;

import java.util.Objects;

/** A normal business result from one bundled operation. */
public record OperationResult(String status, JsonObject output) {
    public OperationResult {
        status = Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output").deepCopy();
    }

    @Override
    public JsonObject output() {
        return output.deepCopy();
    }
}
