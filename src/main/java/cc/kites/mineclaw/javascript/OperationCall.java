package cc.kites.mineclaw.javascript;

import com.google.gson.JsonObject;

import java.util.Objects;

/** Validated, capability-authorized operation passed to the Mineclaw host adapter. */
public record OperationCall(
        String invocationId,
        String functionName,
        String scriptHash,
        int sequence,
        String action,
        JsonObject input
) {
    public OperationCall {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        functionName = Objects.requireNonNull(functionName, "functionName");
        if (functionName.isBlank()) {
            throw new IllegalArgumentException("functionName must not be blank");
        }
        scriptHash = Objects.requireNonNull(scriptHash, "scriptHash");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        action = Objects.requireNonNull(action, "action");
        input = Objects.requireNonNull(input, "input").deepCopy();
    }

    @Override
    public JsonObject input() {
        return input.deepCopy();
    }
}
