package cc.kites.mineclaw.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Asynchronous OpenAI-compatible Chat Completions and Responses streaming client. */
public final class ChatCompletionsClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
    private static final int DEBUG_MESSAGE_CODE_POINTS = 100;
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final Consumer<String> debugLogger;

    public ChatCompletionsClient() {
        // Never forward a bearer credential through an endpoint-controlled redirect.
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), ignored -> { });
    }

    public ChatCompletionsClient(Consumer<String> debugLogger) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), debugLogger);
    }

    public ChatCompletionsClient(HttpClient httpClient) {
        this(httpClient, ignored -> { });
    }

    public ChatCompletionsClient(HttpClient httpClient, Consumer<String> debugLogger) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.debugLogger = Objects.requireNonNull(debugLogger, "debugLogger");
    }

    /**
     * Executes an initial request plus at most {@link ChatCompletionRequest#maxRetries()} retries. The API key is
     * only placed in the provider's authentication header; Mineclaw never copies it into an exception or log.
     * Provider error responses are retained verbatim and may independently echo sensitive upstream data.
     */
    public CompletableFuture<ChatCompletionResult> complete(
            ChatCompletionRequest request,
            String apiKey,
            StreamObserver observer
    ) {
        Objects.requireNonNull(request, "request");
        validateApiKey(apiKey);
        Objects.requireNonNull(observer, "observer");
        String body = requestBody(request);
        String debugBody = request.requestDiagnostics() ? debugRequestBody(body) : "";
        return new RequestOperation(request, apiKey, observer, body, debugBody).start();
    }

    private CompletableFuture<ChatCompletionResult> sendAttempt(
            ChatCompletionRequest request,
            String apiKey,
            Consumer<String> deltaConsumer,
            String body
    ) {
        HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint())
                .timeout(request.timeout())
                .header("Accept", "text/event-stream, application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> responseFuture = httpClient.sendAsync(
                httpRequest, HttpResponse.BodyHandlers.ofPublisher());
        CompletableFuture<ChatCompletionResult> pipeline = responseFuture.thenCompose(response ->
                consumeResponse(response, deltaConsumer, subscription, request));
        return withTimeout(pipeline, responseFuture, subscription, request.timeout());
    }

    private static CompletableFuture<ChatCompletionResult> consumeResponse(
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> response,
            Consumer<String> deltaConsumer,
            AtomicReference<Flow.Subscription> subscription,
            ChatCompletionRequest request
    ) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            if (request.protocol() == ChatCompletionRequest.Protocol.RESPONSES) {
                ResponsesResponseParser parser = new ResponsesResponseParser(deltaConsumer);
                BodySubscriber subscriber = new BodySubscriber(subscription, parser::accept);
                response.body().subscribe(subscriber);
                return subscriber.body().thenApply(ignored -> parser.finish());
            }
            ChatResponseParser parser = new ChatResponseParser(deltaConsumer, request.interleavedField());
            BodySubscriber subscriber = new BodySubscriber(subscription, parser::accept);
            response.body().subscribe(subscriber);
            return subscriber.body().thenApply(ignored -> parser.finish());
        }

        ByteArrayOutputStream errorBody = new ByteArrayOutputStream();
        AtomicBoolean errorBodyTruncated = new AtomicBoolean();
        BodySubscriber subscriber = new BodySubscriber(subscription, bytes -> {
            ByteBuffer copy = bytes.slice();
            int remainingCapacity = Math.max(0, MAX_ERROR_BODY_BYTES - errorBody.size());
            byte[] chunk = new byte[Math.min(copy.remaining(), remainingCapacity)];
            copy.get(chunk);
            errorBody.writeBytes(chunk);
            if (copy.hasRemaining()) {
                errorBodyTruncated.set(true);
            }
        });
        response.body().subscribe(subscriber);
        return subscriber.body().thenCompose(ignored -> CompletableFuture.failedFuture(
                ChatResponseParser.errorResponse(statusCode,
                        errorBody.toString(StandardCharsets.UTF_8), requestId(response),
                        errorBodyTruncated.get(), request.protocol() == ChatCompletionRequest.Protocol.RESPONSES
                                ? "Responses" : "Chat Completions")));
    }

    private static String requestId(HttpResponse<?> response) {
        for (String header : List.of("x-request-id", "request-id", "x-trace-id")) {
            var value = response.headers().firstValue(header);
            if (value.isPresent()) {
                return value.orElseThrow();
            }
        }
        return "";
    }

    private static CompletableFuture<ChatCompletionResult> withTimeout(
            CompletableFuture<ChatCompletionResult> pipeline,
            CompletableFuture<?> responseFuture,
            AtomicReference<Flow.Subscription> subscription,
            Duration timeout
    ) {
        CompletableFuture<ChatCompletionResult> guarded = new CompletableFuture<>();
        pipeline.whenComplete((result, failure) -> {
            if (failure == null) {
                guarded.complete(result);
            } else {
                guarded.completeExceptionally(failure);
            }
        });
        guarded.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        guarded.whenComplete((ignored, failure) -> {
            Throwable cause = failure == null ? null : unwrap(failure);
            if (guarded.isCancelled() || cause instanceof TimeoutException) {
                responseFuture.cancel(true);
                pipeline.cancel(true);
                Flow.Subscription active = subscription.get();
                if (active != null) {
                    active.cancel();
                }
            }
        });
        return guarded;
    }

    private static String requestBody(ChatCompletionRequest request) {
        return switch (request.protocol()) {
            case CHAT_COMPLETIONS -> chatCompletionsRequestBody(request);
            case RESPONSES -> responsesRequestBody(request);
        };
    }

    private static String chatCompletionsRequestBody(ChatCompletionRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());
        request.promptCacheKey().ifPresent(value -> root.addProperty("prompt_cache_key", value));
        root.addProperty("stream", true);
        if (request.maxOutputTokens() > 0) {
            root.addProperty("max_completion_tokens", request.maxOutputTokens());
        }
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);
        request.messages().stream()
                .map(message -> messageJson(message, request.includeMessageNames(),
                        request.includePlayerContentPrefix()))
                .forEach(messages::add);
        root.add("messages", messages);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            request.tools().forEach(tool -> tools.add(tool.deepCopy()));
            root.add("tools", tools);
        }
        request.extraBody().entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue().deepCopy()));
        return GSON.toJson(root);
    }

    private static String responsesRequestBody(ChatCompletionRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());
        request.promptCacheKey().ifPresent(value -> root.addProperty("prompt_cache_key", value));
        root.addProperty("stream", true);
        root.addProperty("store", false);
        if (request.maxOutputTokens() > 0) {
            root.addProperty("max_output_tokens", request.maxOutputTokens());
        }
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content");
        root.add("include", include);

        JsonArray input = new JsonArray();
        input.add(responseMessage("system", request.systemPrompt(), null));
        request.messages().forEach(message -> appendResponseItems(input, message,
                request.includeMessageNames(), request.includePlayerContentPrefix()));
        root.add("input", input);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            request.tools().forEach(tool -> tools.add(tool.deepCopy()));
            root.add("tools", tools);
        }
        request.extraBody().entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue().deepCopy()));
        return GSON.toJson(root);
    }

    private static void appendResponseItems(JsonArray input, ApiMessage message,
                                            boolean includeName, boolean includePlayerPrefix) {
        if (message.role().equals("assistant") && !message.responseItems().isEmpty()) {
            for (JsonObject outputItem : message.responseItems()) {
                JsonObject inputItem = responseInputItem(outputItem);
                if (inputItem != null) {
                    input.add(inputItem);
                }
            }
            return;
        }
        if (message.role().equals("tool")) {
            JsonObject output = new JsonObject();
            output.addProperty("type", "function_call_output");
            output.addProperty("call_id", message.toolCallId());
            output.addProperty("output", message.content());
            input.add(output);
            return;
        }
        if (message.content() != null) {
            // Responses EasyInputMessage has no name field. Preserve the configured player
            // attribution intent with Mineclaw's escaped content envelope instead.
            input.add(responseMessage(message.role(),
                    message.modelContent(includeName || includePlayerPrefix), null));
        }
        if (message.role().equals("assistant")) {
            message.toolCalls().stream().map(ChatCompletionsClient::responseFunctionCall).forEach(input::add);
        }
    }

    /** Converts stored output items into replay-safe Responses input items. */
    private static JsonObject responseInputItem(JsonObject outputItem) {
        JsonObject inputItem = outputItem.deepCopy();
        inputItem.remove("created_by");
        String type = string(inputItem, "type");
        if (type.equals("message")) {
            return responseInputMessage(inputItem);
        }
        if (type.equals("additional_tools") && !string(inputItem, "role").equals("developer")) {
            return null;
        }
        if (type.equals("computer_call_output") && string(inputItem, "status").equals("failed")) {
            return null;
        }
        if (type.equals("custom_tool_call_output")) {
            String status = string(inputItem, "status");
            if (!status.isEmpty() && !status.equals("completed")) {
                return null;
            }
            inputItem.remove("status");
        }
        if (type.equals("shell_call_output")) {
            JsonElement output = inputItem.get("output");
            if (output != null && output.isJsonArray()) {
                for (JsonElement chunk : output.getAsJsonArray()) {
                    if (chunk.isJsonObject()) {
                        chunk.getAsJsonObject().remove("created_by");
                    }
                }
            }
        }
        return inputItem;
    }

    /** Uses the portable EasyInputMessage shape accepted by strict and compatible endpoints. */
    private static JsonObject responseInputMessage(JsonObject outputMessage) {
        String content;
        JsonElement value = outputMessage.get("content");
        if (value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()) {
            content = value.getAsString();
        } else if (value != null && value.isJsonArray()) {
            StringBuilder visible = new StringBuilder();
            for (JsonElement element : value.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject part = element.getAsJsonObject();
                String partType = string(part, "type");
                if (partType.equals("output_text")) {
                    visible.append(string(part, "text"));
                } else if (partType.equals("refusal")) {
                    visible.append(string(part, "refusal"));
                }
            }
            content = visible.toString();
        } else {
            content = "";
        }
        if (content.isEmpty()) {
            return null;
        }
        JsonObject result = new JsonObject();
        String role = string(outputMessage, "role");
        result.addProperty("role", role.isEmpty() ? "assistant" : role);
        result.addProperty("content", content);
        return result;
    }

    private static JsonObject responseMessage(String role, String content, String name) {
        JsonObject result = new JsonObject();
        result.addProperty("role", role);
        if (content == null) {
            result.add("content", JsonNull.INSTANCE);
        } else {
            result.addProperty("content", content);
        }
        if (name != null) {
            result.addProperty("name", name);
        }
        return result;
    }

    private static JsonObject responseFunctionCall(ToolCall call) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "function_call");
        result.addProperty("call_id", call.id());
        result.addProperty("name", call.name());
        result.addProperty("arguments", call.arguments());
        return result;
    }

    static String debugRequestBody(String body) {
        JsonObject root = JsonParser.parseString(Objects.requireNonNull(body, "body")).getAsJsonObject();
        JsonArray messages = root.getAsJsonArray("messages");
        if (messages != null) {
            messages.forEach(ChatCompletionsClient::truncateDebugContextItem);
        }
        JsonArray input = root.getAsJsonArray("input");
        if (input != null) {
            input.forEach(ChatCompletionsClient::truncateDebugContextItem);
        }
        return GSON.toJson(root);
    }

    private static void truncateDebugContextItem(JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject item = element.getAsJsonObject();
        for (var entry : item.entrySet()) {
            String field = entry.getKey();
            JsonElement value = entry.getValue();
            if ((field.equals("content") || field.endsWith("_content")
                    || field.equals("output") && "function_call_output".equals(string(item, "type")))
                    && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                entry.setValue(new com.google.gson.JsonPrimitive(
                        truncateDebugMessage(value.getAsString())));
            } else if (field.equals("content") && value.isJsonArray()) {
                for (JsonElement part : value.getAsJsonArray()) {
                    if (part.isJsonObject()) {
                        truncateStringField(part.getAsJsonObject(), "text");
                        truncateStringField(part.getAsJsonObject(), "refusal");
                    }
                }
            }
        }
    }

    private static void truncateStringField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            object.addProperty(field, truncateDebugMessage(value.getAsString()));
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String truncateDebugMessage(String value) {
        if (value.codePointCount(0, value.length()) <= DEBUG_MESSAGE_CODE_POINTS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, DEBUG_MESSAGE_CODE_POINTS);
        return value.substring(0, end) + "...";
    }

    private static JsonObject messageJson(ApiMessage message, boolean includeName,
                                          boolean includePlayerPrefix) {
        JsonObject result = new JsonObject();
        result.addProperty("role", message.role());
        if (message.modelContent(includePlayerPrefix) == null) {
            result.add("content", JsonNull.INSTANCE);
        } else {
            result.addProperty("content", message.modelContent(includePlayerPrefix));
        }
        if (!message.toolCalls().isEmpty()) {
            JsonArray calls = new JsonArray();
            message.toolCalls().stream().map(ChatCompletionsClient::toolCallJson).forEach(calls::add);
            result.add("tool_calls", calls);
        }
        if (message.toolCallId() != null) {
            result.addProperty("tool_call_id", message.toolCallId());
        }
        message.providerFields().forEach(result::addProperty);
        if (includeName && message.name() != null) {
            result.addProperty("name", message.name());
        }
        return result;
    }

    private static JsonObject toolCallJson(ToolCall call) {
        JsonObject result = new JsonObject();
        result.addProperty("id", call.id());
        result.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", call.name());
        function.addProperty("arguments", call.arguments());
        result.add("function", function);
        return result;
    }

    static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static boolean isRetryable(Throwable failure) {
        if (failure instanceof ChatCompletionException exception) {
            return exception.retryable();
        }
        return failure instanceof IOException || failure instanceof TimeoutException;
    }

    private static Throwable normalizeFailure(Throwable failure) {
        if (failure instanceof ChatCompletionException || failure instanceof CancellationException) {
            return failure;
        }
        if (failure instanceof IOException || failure instanceof TimeoutException) {
            return new ChatCompletionException("Provider API transport failed", -1, true, failure);
        }
        return new ChatCompletionException("Provider API request failed", -1, false, failure);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Duration exponentialBackoff(Duration initial, int completedRetries) {
        if (initial.isZero()) {
            return initial;
        }
        long multiplier = completedRetries >= 62 ? Long.MAX_VALUE : 1L << completedRetries;
        Duration uncapped;
        try {
            uncapped = initial.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            uncapped = MAX_RETRY_BACKOFF;
        }
        Duration capped = uncapped.compareTo(MAX_RETRY_BACKOFF) > 0 ? MAX_RETRY_BACKOFF : uncapped;
        double jitter = ThreadLocalRandom.current().nextDouble(0.75d, 1.25d);
        long nanos = Math.max(1L, (long) (capped.toNanos() * jitter));
        return Duration.ofNanos(Math.min(nanos, MAX_RETRY_BACKOFF.toNanos()));
    }

    private static CompletableFuture<Void> delay(Duration duration) {
        if (duration.isZero()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> { },
                CompletableFuture.delayedExecutor(duration.toNanos(), TimeUnit.NANOSECONDS));
    }

    private static void validateApiKey(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (apiKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("apiKey contains an invalid header character");
        }
    }

    /** Owns the current transport/delay so cancelling the public future reaches the HTTP subscription. */
    private final class RequestOperation {
        private final ChatCompletionRequest request;
        private final String apiKey;
        private final StreamObserver observer;
        private final String body;
        private final String debugBody;
        private final CompletableFuture<ChatCompletionResult> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<?>> current = new AtomicReference<>();

        private RequestOperation(ChatCompletionRequest request, String apiKey,
                                 StreamObserver observer, String body, String debugBody) {
            this.request = request;
            this.apiKey = apiKey;
            this.observer = observer;
            this.body = body;
            this.debugBody = debugBody;
        }

        private CompletableFuture<ChatCompletionResult> start() {
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    CompletableFuture<?> active = current.get();
                    if (active != null) {
                        active.cancel(true);
                    }
                }
            });
            attempt(0);
            return result;
        }

        private void attempt(int attemptNumber) {
            if (result.isDone()) {
                return;
            }
            AtomicBoolean emittedDelta = new AtomicBoolean();
            CompletableFuture<ChatCompletionResult> transport;
            try {
                logDebugRequest(attemptNumber + 1);
                transport = sendAttempt(request, apiKey, delta -> {
                        emittedDelta.set(true);
                        observer.onDelta(delta);
                }, body);
            } catch (RuntimeException exception) {
                result.completeExceptionally(new ChatCompletionException(
                        "Provider API request could not be started", -1, false, exception));
                return;
            }
            current.set(transport);
            if (result.isCancelled()) {
                transport.cancel(true);
                return;
            }
            transport.whenComplete((value, failure) -> {
                if (result.isDone()) {
                    return;
                }
                if (failure == null) {
                    result.complete(value);
                    return;
                }
                Throwable cause = unwrap(failure);
                boolean willRetry = attemptNumber < request.maxRetries() && isRetryable(cause);
                try {
                    observer.onAttemptFailure(attemptNumber + 1, normalizeFailure(cause), willRetry);
                } catch (RuntimeException ignored) {
                    // Diagnostics must never change retry or completion semantics.
                }
                if (willRetry) {
                    if (emittedDelta.get()) {
                        try {
                            observer.onReset();
                        } catch (RuntimeException callbackFailure) {
                            result.completeExceptionally(normalizeFailure(callbackFailure));
                            return;
                        }
                    }
                    CompletableFuture<Void> backoff = delay(
                            exponentialBackoff(request.retryBackoff(), attemptNumber));
                    current.set(backoff);
                    if (result.isCancelled()) {
                        backoff.cancel(true);
                        return;
                    }
                    backoff.whenComplete((ignored, delayFailure) -> {
                        if (result.isDone()) {
                            return;
                        }
                        if (delayFailure == null) {
                            attempt(attemptNumber + 1);
                        } else {
                            result.completeExceptionally(normalizeFailure(unwrap(delayFailure)));
                        }
                    });
                } else {
                    result.completeExceptionally(normalizeFailure(cause));
                }
            });
        }

        private void logDebugRequest(int attemptNumber) {
            if (!request.requestDiagnostics()) {
                return;
            }
            try {
                debugLogger.accept("[Mineclaw debug] Provider API request protocol="
                        + request.protocol().name().toLowerCase(java.util.Locale.ROOT) + " attempt="
                        + attemptNumber + " model=" + request.modelReference() + " body=" + debugBody);
            } catch (RuntimeException ignored) {
                // Debug output must never alter transport behavior.
            }
        }
    }

    /** Receives one attempt's deltas and a reset before retrying a partially streamed attempt. */
    public interface StreamObserver {
        void onDelta(String delta);

        void onReset();

        default void onAttemptFailure(int attempt, Throwable failure, boolean willRetry) { }
    }

    private static final class BodySubscriber implements Flow.Subscriber<List<ByteBuffer>> {
        private final AtomicReference<Flow.Subscription> sharedSubscription;
        private final Consumer<ByteBuffer> chunkConsumer;
        private final CompletableFuture<Void> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BodySubscriber(AtomicReference<Flow.Subscription> sharedSubscription,
                               Consumer<ByteBuffer> chunkConsumer) {
            this.sharedSubscription = sharedSubscription;
            this.chunkConsumer = chunkConsumer;
        }

        private CompletableFuture<Void> body() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            sharedSubscription.set(value);
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            try {
                item.forEach(chunkConsumer);
                subscription.request(1);
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(null);
        }
    }
}
