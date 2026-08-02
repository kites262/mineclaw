package cc.kites.mineclaw.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Asynchronous OpenAI-compatible Chat Completions streaming client. */
public final class ChatCompletionsClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private final HttpClient httpClient;

    public ChatCompletionsClient() {
        // Never forward a bearer credential through an endpoint-controlled redirect.
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public ChatCompletionsClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Executes an initial request plus at most {@link ChatCompletionRequest#maxRetries()} retries. The API key is
     * only placed in the HTTP Authorization header and is never included in an exception or log message.
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
        return new RequestOperation(request, apiKey, observer, body).start();
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
                consumeResponse(response, deltaConsumer, subscription));
        return withTimeout(pipeline, responseFuture, subscription, request.timeout());
    }

    private static CompletableFuture<ChatCompletionResult> consumeResponse(
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> response,
            Consumer<String> deltaConsumer,
            AtomicReference<Flow.Subscription> subscription
    ) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            ChatResponseParser parser = new ChatResponseParser(deltaConsumer);
            BodySubscriber subscriber = new BodySubscriber(subscription, parser::accept);
            response.body().subscribe(subscriber);
            return subscriber.body().thenApply(ignored -> parser.finish());
        }

        ByteArrayOutputStream errorBody = new ByteArrayOutputStream();
        BodySubscriber subscriber = new BodySubscriber(subscription, bytes -> {
            ByteBuffer copy = bytes.slice();
            int remainingCapacity = Math.max(0, MAX_ERROR_BODY_BYTES - errorBody.size());
            byte[] chunk = new byte[Math.min(copy.remaining(), remainingCapacity)];
            copy.get(chunk);
            errorBody.writeBytes(chunk);
        });
        response.body().subscribe(subscriber);
        return subscriber.body().thenCompose(ignored -> CompletableFuture.failedFuture(
                ChatResponseParser.errorResponse(statusCode,
                        errorBody.toString(StandardCharsets.UTF_8))));
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
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());
        root.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);
        request.messages().stream().map(ChatCompletionsClient::messageJson).forEach(messages::add);
        root.add("messages", messages);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            request.tools().forEach(tool -> tools.add(tool.deepCopy()));
            root.add("tools", tools);
        }
        return GSON.toJson(root);
    }

    private static JsonObject messageJson(ApiMessage message) {
        JsonObject result = new JsonObject();
        result.addProperty("role", message.role());
        if (message.content() == null) {
            result.add("content", JsonNull.INSTANCE);
        } else {
            result.addProperty("content", message.content());
        }
        if (!message.toolCalls().isEmpty()) {
            JsonArray calls = new JsonArray();
            message.toolCalls().stream().map(ChatCompletionsClient::toolCallJson).forEach(calls::add);
            result.add("tool_calls", calls);
        }
        if (message.toolCallId() != null) {
            result.addProperty("tool_call_id", message.toolCallId());
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
        return statusCode == 408 || statusCode == 409 || statusCode == 425 || statusCode == 429
                || statusCode >= 500;
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
            return new ChatCompletionException("Chat Completions transport failed", -1, true, failure);
        }
        return new ChatCompletionException("Chat Completions request failed", -1, false, failure);
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
        try {
            return initial.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return Duration.ofNanos(Long.MAX_VALUE);
        }
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
        private final CompletableFuture<ChatCompletionResult> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<?>> current = new AtomicReference<>();

        private RequestOperation(ChatCompletionRequest request, String apiKey,
                                 StreamObserver observer, String body) {
            this.request = request;
            this.apiKey = apiKey;
            this.observer = observer;
            this.body = body;
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
                transport = sendAttempt(request, apiKey, delta -> {
                        emittedDelta.set(true);
                        observer.onDelta(delta);
                }, body);
            } catch (RuntimeException exception) {
                result.completeExceptionally(new ChatCompletionException(
                        "Chat Completions request could not be started", -1, false, exception));
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
                if (attemptNumber < request.maxRetries() && isRetryable(cause)) {
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
    }

    /** Receives one attempt's deltas and a reset before retrying a partially streamed attempt. */
    public interface StreamObserver {
        void onDelta(String delta);

        void onReset();
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
