package cc.kites.mineclaw.api;

import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A credential-free request description. The API key is deliberately supplied separately to the client call so
 * generated record methods cannot reveal it.
 */
public record ChatCompletionRequest(
        URI endpoint,
        String model,
        String systemPrompt,
        List<ApiMessage> messages,
        List<JsonObject> tools,
        Duration timeout,
        int maxRetries,
        Duration retryBackoff
) {
    public ChatCompletionRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        if (!endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must not be negative");
        }
        try {
            timeout.toNanos();
            retryBackoff.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timeout and retryBackoff must fit in nanoseconds", exception);
        }
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : tools.stream()
                .map(tool -> Objects.requireNonNull(tool, "tool").deepCopy())
                .toList();
    }
}
