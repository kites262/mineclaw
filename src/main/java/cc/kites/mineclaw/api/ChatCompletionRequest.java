package cc.kites.mineclaw.api;

import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A credential-free request description. The API key is deliberately supplied separately to the client call so
 * generated record methods cannot reveal it.
 */
public record ChatCompletionRequest(
        URI endpoint,
        String modelReference,
        String model,
        String systemPrompt,
        List<ApiMessage> messages,
        List<JsonObject> tools,
        Duration timeout,
        int maxRetries,
        Duration retryBackoff,
        int maxOutputTokens,
        JsonObject extraBody,
        Optional<String> interleavedField,
        Optional<String> promptCacheKey,
        boolean requestDiagnostics,
        boolean includeMessageNames,
        boolean includePlayerContentPrefix,
        Protocol protocol
) {
    public ChatCompletionRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(modelReference, "modelReference");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        Objects.requireNonNull(extraBody, "extraBody");
        Objects.requireNonNull(interleavedField, "interleavedField");
        Objects.requireNonNull(promptCacheKey, "promptCacheKey");
        Objects.requireNonNull(protocol, "protocol");
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
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must not be negative");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must not be negative");
        }
        promptCacheKey.ifPresent(ChatCompletionRequest::validatePromptCacheKey);
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
        extraBody = extraBody.deepCopy();
    }

    public ChatCompletionRequest(URI endpoint, String modelReference, String model,
                                 String systemPrompt, List<ApiMessage> messages,
                                 List<JsonObject> tools, Duration timeout, int maxRetries,
                                 Duration retryBackoff, int maxOutputTokens,
                                 JsonObject extraBody, Optional<String> interleavedField,
                                 Optional<String> promptCacheKey, boolean requestDiagnostics,
                                 boolean includeMessageNames, boolean includePlayerContentPrefix) {
        this(endpoint, modelReference, model, systemPrompt, messages, tools, timeout,
                maxRetries, retryBackoff, maxOutputTokens, extraBody, interleavedField,
                promptCacheKey, requestDiagnostics, includeMessageNames,
                includePlayerContentPrefix, Protocol.CHAT_COMPLETIONS);
    }

    public ChatCompletionRequest(URI endpoint, String modelReference, String model,
                                 String systemPrompt, List<ApiMessage> messages,
                                 List<JsonObject> tools, Duration timeout, int maxRetries,
                                 Duration retryBackoff, int maxOutputTokens,
                                 JsonObject extraBody, Optional<String> interleavedField,
                                 Optional<String> promptCacheKey, boolean requestDiagnostics,
                                 boolean includeMessageNames) {
        this(endpoint, modelReference, model, systemPrompt, messages, tools, timeout,
                maxRetries, retryBackoff, maxOutputTokens, extraBody, interleavedField,
                promptCacheKey, requestDiagnostics, includeMessageNames, true,
                Protocol.CHAT_COMPLETIONS);
    }

    public ChatCompletionRequest(URI endpoint, String modelReference, String model,
                                 String systemPrompt, List<ApiMessage> messages,
                                 List<JsonObject> tools, Duration timeout, int maxRetries,
                                 Duration retryBackoff, int maxOutputTokens,
                                 JsonObject extraBody, Optional<String> interleavedField,
                                 Optional<String> promptCacheKey, boolean requestDiagnostics) {
        this(endpoint, modelReference, model, systemPrompt, messages, tools, timeout,
                maxRetries, retryBackoff, maxOutputTokens, extraBody, interleavedField,
                promptCacheKey, requestDiagnostics, true, true, Protocol.CHAT_COMPLETIONS);
    }

    public ChatCompletionRequest(URI endpoint, String modelReference, String model,
                                 String systemPrompt, List<ApiMessage> messages,
                                 List<JsonObject> tools, Duration timeout, int maxRetries,
                                 Duration retryBackoff, int maxOutputTokens,
                                 JsonObject extraBody, Optional<String> interleavedField,
                                 Optional<String> promptCacheKey) {
        this(endpoint, modelReference, model, systemPrompt, messages, tools, timeout,
                maxRetries, retryBackoff, maxOutputTokens, extraBody, interleavedField,
                promptCacheKey, false, true, true, Protocol.CHAT_COMPLETIONS);
    }

    public ChatCompletionRequest(URI endpoint, String modelReference, String model,
                                 String systemPrompt, List<ApiMessage> messages,
                                 List<JsonObject> tools, Duration timeout, int maxRetries,
                                 Duration retryBackoff, int maxOutputTokens,
                                 JsonObject extraBody, Optional<String> interleavedField) {
        this(endpoint, modelReference, model, systemPrompt, messages, tools, timeout,
                maxRetries, retryBackoff, maxOutputTokens, extraBody, interleavedField,
                Optional.empty(), false, true, true, Protocol.CHAT_COMPLETIONS);
    }

    public ChatCompletionRequest(URI endpoint, String model, String systemPrompt,
                                 List<ApiMessage> messages, List<JsonObject> tools,
                                 Duration timeout, int maxRetries, Duration retryBackoff) {
        this(endpoint, model, model, systemPrompt, messages, tools, timeout, maxRetries, retryBackoff,
                0, new JsonObject(), Optional.empty(), Optional.empty(), false, true, true,
                Protocol.CHAT_COMPLETIONS);
    }

    @Override
    public JsonObject extraBody() {
        return extraBody.deepCopy();
    }

    private static void validatePromptCacheKey(String value) {
        String prefix = "mineclaw:";
        if (!value.startsWith(prefix)) {
            throw new IllegalArgumentException("promptCacheKey must use the mineclaw UUID namespace");
        }
        String uuid = value.substring(prefix.length());
        try {
            if (!java.util.UUID.fromString(uuid).toString().equals(uuid)) {
                throw new IllegalArgumentException("promptCacheKey must contain a canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("promptCacheKey must contain a canonical UUID", exception);
        }
    }

    /** Wire shape selected by the Provider's configured API type. */
    public enum Protocol {
        CHAT_COMPLETIONS,
        RESPONSES
    }
}
