package cc.kites.mineclaw.javascript;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Set;

/** Trusted FunctionInvocation identity plus schema-validated, but still untrusted, arguments. */
public record InvocationRequest(
        String invocationId,
        String playerName,
        JsonObject arguments,
        Set<String> capabilities
) {
    public InvocationRequest {
        invocationId = requireNonBlank(invocationId, "invocationId");
        playerName = requireNonBlank(playerName, "playerName");
        arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    @Override
    public JsonObject arguments() {
        return arguments.deepCopy();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
