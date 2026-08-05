package cc.kites.mineclaw.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionsClientTest {
    private static final ChatCompletionsClient.StreamObserver IGNORE_STREAM =
            new ChatCompletionsClient.StreamObserver() {
                @Override
                public void onDelta(String delta) {
                }

                @Override
                public void onReset() {
                }
            };

    private HttpServer server;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void sendsCredentialSeparatelyAndAcceptsOrdinaryJson() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"完成","tool_calls":[]},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                    """);
        });

        JsonObject tool = JsonParser.parseString("""
                {"type":"function","function":{"name":"look","description":"look",
                 "parameters":{"type":"object","properties":{}}}}
                """).getAsJsonObject();
        ChatCompletionRequest request = request(List.of(
                ApiMessage.user("Alice", "看哪里"),
                ApiMessage.assistantToolCalls(List.of(new ToolCall("old_call", "look", "{}"))),
                ApiMessage.tool("old_call", "{\"status\":\"ok\"}")), List.of(tool), 0);

        ChatCompletionResult result = client().complete(request, "test-secret", IGNORE_STREAM).join();

        assertThat(authorization).hasValue("Bearer test-secret");
        assertThat(result.content()).isEqualTo("完成");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(new ApiUsage(8, 2, 10));

        JsonObject sent = requestBody.get();
        assertThat(sent.get("model").getAsString()).isEqualTo("deepseek-v4-flash");
        assertThat(sent.has("prompt_cache_key")).isFalse();
        assertThat(sent.get("stream").getAsBoolean()).isTrue();
        assertThat(sent.getAsJsonObject("stream_options").get("include_usage").getAsBoolean()).isTrue();
        assertThat(sent.getAsJsonArray("tools")).hasSize(1);
        JsonArray messages = sent.getAsJsonArray("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getAsJsonObject().get("role").getAsString()).isEqualTo("system");
        assertThat(messages.get(0).getAsJsonObject().get("content").getAsString()).isEqualTo("system rules");
        assertThat(messages.get(1).getAsJsonObject().get("name").getAsString()).isEqualTo("Alice");
        assertThat(messages.get(1).getAsJsonObject().get("content").getAsString())
                .isEqualTo("<player>Alice</player>\n看哪里");
        assertThat(messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls")).hasSize(1);
        assertThat(messages.get(3).getAsJsonObject().get("tool_call_id").getAsString()).isEqualTo("old_call");
    }

    @Test
    void canOmitPlayerNameFieldWithoutRemovingContentMarker() {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, ordinaryResponse("ok"));
        });
        ChatCompletionRequest request = new ChatCompletionRequest(
                endpoint(), "test/model", "upstream-model", "system",
                List.of(ApiMessage.user("Alice", "hello")), List.of(), Duration.ofSeconds(2),
                0, Duration.ZERO, 0, new JsonObject(), Optional.empty(), Optional.empty(),
                false, false);

        client().complete(request, "secret", IGNORE_STREAM).join();

        JsonObject user = requestBody.get().getAsJsonArray("messages").get(1).getAsJsonObject();
        assertThat(user.has("name")).isFalse();
        assertThat(user.get("content").getAsString()).isEqualTo("<player>Alice</player>\nhello");
    }

    @Test
    void canOmitPlayerContentMarkerWithoutRemovingNameField() {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, ordinaryResponse("ok"));
        });
        ChatCompletionRequest request = new ChatCompletionRequest(
                endpoint(), "test/model", "upstream-model", "system",
                List.of(ApiMessage.user("Alice", "hello")), List.of(), Duration.ofSeconds(2),
                0, Duration.ZERO, 0, new JsonObject(), Optional.empty(), Optional.empty(),
                false, true, false);

        client().complete(request, "secret", IGNORE_STREAM).join();

        JsonObject user = requestBody.get().getAsJsonArray("messages").get(1).getAsJsonObject();
        assertThat(user.get("name").getAsString()).isEqualTo("Alice");
        assertThat(user.get("content").getAsString()).isEqualTo("hello");
    }

    @Test
    void debugLogsEveryAttemptWithOnlyLongMessageTextTruncated() {
        AtomicInteger attempts = new AtomicInteger();
        List<JsonObject> sentBodies = new CopyOnWriteArrayList<>();
        List<String> debugLogs = new CopyOnWriteArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            sentBodies.add(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            if (attempts.incrementAndGet() == 1) {
                respond(exchange, 503, "{\"error\":{\"type\":\"server_error\"}}");
            } else {
                respond(exchange, 200, ordinaryResponse("ok"));
            }
        });

        String system = "系".repeat(99) + "😀tail";
        String user = "u".repeat(101);
        String reasoning = "r".repeat(101);
        String arguments = "{\"payload\":\"" + "a".repeat(140) + "\"}";
        JsonObject providerTool = new JsonObject();
        providerTool.addProperty("type", "web_search");
        providerTool.addProperty("description", "d".repeat(140));
        JsonObject extra = new JsonObject();
        extra.addProperty("custom_parameter", "p".repeat(140));
        ApiMessage assistant = new ApiMessage("assistant", null,
                List.of(new ToolCall("call-1", "lookup", arguments)), null,
                Map.of("reasoning_content", reasoning));
        ChatCompletionRequest request = new ChatCompletionRequest(
                endpoint(), "test/model", "upstream-model", system,
                List.of(ApiMessage.user(user), assistant), List.of(providerTool),
                Duration.ofSeconds(2), 1, Duration.ofMillis(1), 512,
                extra, Optional.empty(), Optional.empty(), true);
        ChatCompletionsClient client = new ChatCompletionsClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build(), debugLogs::add);

        client.complete(request, "never-log-this-secret", IGNORE_STREAM).join();

        assertThat(attempts).hasValue(2);
        assertThat(debugLogs).hasSize(2);
        assertThat(debugLogs.get(0)).contains("attempt=1", "model=test/model");
        assertThat(debugLogs.get(1)).contains("attempt=2", "model=test/model");
        assertThat(debugLogs).allSatisfy(log -> assertThat(log).doesNotContain("never-log-this-secret"));

        int bodyOffset = debugLogs.getFirst().indexOf(" body=") + " body=".length();
        JsonObject logged = JsonParser.parseString(debugLogs.getFirst().substring(bodyOffset)).getAsJsonObject();
        JsonArray loggedMessages = logged.getAsJsonArray("messages");
        assertThat(loggedMessages.get(0).getAsJsonObject().get("content").getAsString())
                .isEqualTo("系".repeat(99) + "😀...");
        assertThat(loggedMessages.get(1).getAsJsonObject().get("content").getAsString())
                .isEqualTo("u".repeat(100) + "...");
        assertThat(loggedMessages.get(2).getAsJsonObject().get("reasoning_content").getAsString())
                .isEqualTo("r".repeat(100) + "...");
        assertThat(loggedMessages.get(2).getAsJsonObject().getAsJsonArray("tool_calls")
                .get(0).getAsJsonObject().getAsJsonObject("function").get("arguments").getAsString())
                .isEqualTo(arguments);
        assertThat(logged.getAsJsonArray("tools").get(0)).isEqualTo(providerTool);
        assertThat(logged.get("custom_parameter").getAsString()).isEqualTo("p".repeat(140));

        assertThat(sentBodies).hasSize(2).allSatisfy(sent -> {
            assertThat(sent.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .get("content").getAsString()).isEqualTo(system);
            assertThat(sent.getAsJsonArray("messages").get(1).getAsJsonObject()
                    .get("content").getAsString()).isEqualTo(user);
            assertThat(sent.getAsJsonArray("tools").get(0)).isEqualTo(providerTool);
        });
    }

    @Test
    void disabledRequestDiagnosticsProducesNoRequestLog() {
        List<String> debugLogs = new CopyOnWriteArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, ordinaryResponse("ok"));
        });
        ChatCompletionsClient client = new ChatCompletionsClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build(), debugLogs::add);

        client.complete(request(List.of(ApiMessage.user("quiet")), List.of(), 0),
                "secret", IGNORE_STREAM).join();

        assertThat(debugLogs).isEmpty();
    }

    @Test
    void insertsEnabledPromptCacheKeyAsATopLevelRequestField() {
        AtomicReference<String> rawBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            rawBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, ordinaryResponse("cached"));
        });
        String cacheKey = "mineclaw:550e8400-e29b-41d4-a716-446655440000";
        ChatCompletionRequest request = new ChatCompletionRequest(
                endpoint(), "mimo/mimo-v2.5", "mimo-v2.5", "system", List.of(), List.of(),
                Duration.ofSeconds(2), 0, Duration.ZERO, 16_384, new JsonObject(),
                Optional.empty(), Optional.of(cacheKey));

        client().complete(request, "secret", IGNORE_STREAM).join();

        JsonObject sent = JsonParser.parseString(rawBody.get()).getAsJsonObject();
        assertThat(sent.get("prompt_cache_key").getAsString()).isEqualTo(cacheKey);
        assertThat(rawBody.get().indexOf("\"prompt_cache_key\""))
                .isLessThan(rawBody.get().indexOf("\"stream\""));
    }

    @Test
    void usesStandardBearerHeaderForMimoModelReferences() {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, ordinaryResponse("ok"));
        });
        ChatCompletionRequest request = new ChatCompletionRequest(
                endpoint(), "mimo/mimo-v2.5", "mimo-v2.5", "system rules", List.of(), List.of(),
                Duration.ofSeconds(2), 0, Duration.ZERO, 8192, new JsonObject(), Optional.empty());

        client().complete(request, "mimo-secret", IGNORE_STREAM).join();

        assertThat(apiKey.get()).isNull();
        assertThat(authorization).hasValue("Bearer mimo-secret");
    }

    @Test
    void streamsDeltasAsTheyArrive() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"一\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"二\"},"
                        .concat("\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        StringBuilder deltas = new StringBuilder();

        ChatCompletionResult result = client().complete(request(List.of(ApiMessage.user("数数")), List.of(), 0),
                "secret", new ChatCompletionsClient.StreamObserver() {
                    @Override
                    public void onDelta(String delta) {
                        deltas.append(delta);
                    }

                    @Override
                    public void onReset() {
                        deltas.setLength(0);
                    }
                }).join();

        assertThat(deltas).hasToString("一二");
        assertThat(result.content()).isEqualTo("一二");
        assertThat(result.finishReason()).isEqualTo("stop");
    }

    @Test
    void resetsPartialDeltasBeforeRetryingAClosedIncompleteStream() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                if (attempts.incrementAndGet() == 1) {
                    output.write(("data: {\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"stale\"}}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    output.write(("data: {\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"fresh\"},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        StringBuilder visible = new StringBuilder();
        AtomicInteger resets = new AtomicInteger();

        ChatCompletionResult result = client().complete(
                request(List.of(ApiMessage.user("retry stream")), List.of(), 1), "secret",
                new ChatCompletionsClient.StreamObserver() {
                    @Override
                    public void onDelta(String delta) {
                        visible.append(delta);
                    }

                    @Override
                    public void onReset() {
                        visible.setLength(0);
                        resets.incrementAndGet();
                    }
                }).join();

        assertThat(result.content()).isEqualTo("fresh");
        assertThat(visible).hasToString("fresh");
        assertThat(resets).hasValue(1);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void omitsToolsMemberWhenNoToolsAreAvailable() {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, ordinaryResponse("no tools"));
        });

        client().complete(request(List.of(), List.of(), 0), "secret", IGNORE_STREAM).join();

        assertThat(requestBody.get().has("tools")).isFalse();
    }

    @Test
    void sendsProviderToolsWithoutFunctionWrapping() {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, ordinaryResponse("searched"));
        });
        JsonObject providerTool = JsonParser.parseString("""
                {"type":"web_search","max_keyword":3,"force_search":true,"limit":1,
                 "user_location":{"type":"approximate","country":"China","region":"Hubei","city":"Wuhan"}}
                """).getAsJsonObject();

        client().complete(request(List.of(ApiMessage.user("search")), List.of(providerTool), 0),
                "secret", IGNORE_STREAM).join();

        JsonObject sent = requestBody.get().getAsJsonArray("tools").get(0).getAsJsonObject();
        assertThat(sent).isEqualTo(providerTool);
        assertThat(sent.get("type").getAsString()).isEqualTo("web_search");
        assertThat(sent.has("function")).isFalse();
    }

    @Test
    void sendsModelLimitExtraBodyAndInterleavedAssistantField() {
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"ok","reasoning_content":"private"},
                    "finish_reason":"stop"}]}
                    """);
        });
        JsonObject extra = JsonParser.parseString("{\"thinking\":{\"type\":\"enabled\"}}")
                .getAsJsonObject();
        ApiMessage assistant = new ApiMessage("assistant", null,
                List.of(new ToolCall("call-1", "look", "{}")), null,
                Map.of("reasoning_content", "original-private"));
        ChatCompletionRequest request = new ChatCompletionRequest(endpoint(), "mimo/mimo-v2.5",
                "mimo-v2.5", "system", List.of(assistant, ApiMessage.tool("call-1", "{}")),
                List.of(), Duration.ofSeconds(2), 0, Duration.ZERO, 8192, extra,
                Optional.of("reasoning_content"));

        ChatCompletionResult result = client().complete(request, "secret", IGNORE_STREAM).join();

        JsonObject sent = requestBody.get();
        assertThat(sent.get("model").getAsString()).isEqualTo("mimo-v2.5");
        assertThat(sent.get("max_completion_tokens").getAsInt()).isEqualTo(8192);
        assertThat(sent.getAsJsonObject("thinking").get("type").getAsString()).isEqualTo("enabled");
        assertThat(sent.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("reasoning_content").getAsString()).isEqualTo("original-private");
        assertThat(result.interleavedValue()).isEqualTo("private");
    }

    @Test
    void concatenatesStreamedInterleavedReasoningWithoutBroadcastingItAsContent() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"a\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"b\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        ChatCompletionRequest request = new ChatCompletionRequest(endpoint(), "mimo/mimo-v2.5",
                "mimo-v2.5", "", List.of(), List.of(), Duration.ofSeconds(2), 0,
                Duration.ZERO, 1, new JsonObject(), Optional.of("reasoning_content"));
        StringBuilder visible = new StringBuilder();

        ChatCompletionResult result = client().complete(request, "secret",
                new ChatCompletionsClient.StreamObserver() {
                    @Override public void onDelta(String delta) { visible.append(delta); }
                    @Override public void onReset() { visible.setLength(0); }
                }).join();

        assertThat(result.interleavedValue()).isEqualTo("ab");
        assertThat(result.content()).isEmpty();
        assertThat(visible).isEmpty();
    }

    @Test
    void retries429AndServerErrorsWithInitialPlusMaxRetriesAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                respond(exchange, 429, "{\"error\":{\"type\":\"rate_limit_error\"}}");
            } else if (attempt == 2) {
                respond(exchange, 503, "{\"error\":{\"type\":\"server_error\"}}");
            } else {
                respond(exchange, 200, ordinaryResponse("第三次成功"));
            }
        });

        ChatCompletionResult result = client().complete(request(List.of(ApiMessage.user("retry")), List.of(), 2),
                "secret", IGNORE_STREAM).join();

        assertThat(result.content()).isEqualTo("第三次成功");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void retriesRecoverableErrorObjectEvenWhenStatusIsSuccessful() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                respond(exchange, 200, "{\"error\":{\"message\":\"Service temporarily overloaded\","
                        + "\"type\":\"bad_response_status_code\",\"code\":\"bad_response_status_code\"}}");
            } else {
                respond(exchange, 200, ordinaryResponse("恢复"));
            }
        });

        ChatCompletionResult result = client().complete(
                request(List.of(), List.of(), 1), "secret", IGNORE_STREAM).join();

        assertThat(result.content()).isEqualTo("恢复");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void retriesTimedOutAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, 200, ordinaryResponse("timeout recovered"));
        });
        ChatCompletionRequest request = new ChatCompletionRequest(endpoint(), "deepseek-v4-flash", "",
                List.of(), List.of(), Duration.ofMillis(100), 1, Duration.ofMillis(1));

        ChatCompletionResult result = client().complete(request, "secret", IGNORE_STREAM).join();

        assertThat(result.content()).isEqualTo("timeout recovered");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryTerminalClientErrorOrExposeCredential() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 401, "{\"error\":{\"type\":\"invalid_api_key\"}}");
        });

        assertThatThrownBy(() -> client().complete(request(List.of(), List.of(), 2),
                        "never-print-this", IGNORE_STREAM).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChatCompletionException.class)
                .hasMessageNotContaining("never-print-this");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retainsRawProviderErrorResponseWithoutParsingJson() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().set("x-request-id", "request-123");
            respond(exchange, 400, """
                    {"error":{"code":"bad_schema","type":"invalid_request","param":"thinking",
                    "message":"invalid option"},"api_key":"TOP_SECRET","messages":["PRIVATE"],
                    "reasoning_content":"PRIVATE_REASONING"}
                    """);
        });

        Throwable cause = org.assertj.core.api.Assertions.catchThrowable(() ->
                client().complete(request(List.of(), List.of(), 3), "credential", IGNORE_STREAM).join());
        assertThat(cause).isInstanceOf(CompletionException.class);
        ChatCompletionException failure = (ChatCompletionException) cause.getCause();
        assertThat(failure.statusCode()).isEqualTo(400);
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.requestId()).isEqualTo("request-123");
        assertThat(failure.responseBody()).contains(
                "\"code\":\"bad_schema\"", "TOP_SECRET", "PRIVATE", "PRIVATE_REASONING")
                .doesNotContain("credential");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retainsRawSseProviderErrorResponse() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            byte[] response = """
                    event: error
                    data: upstream overloaded
                    data: retry later

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });

        Throwable cause = org.assertj.core.api.Assertions.catchThrowable(() ->
                client().complete(request(List.of(), List.of(), 0), "credential", IGNORE_STREAM).join());

        assertThat(cause).isInstanceOf(CompletionException.class);
        ChatCompletionException failure = (ChatCompletionException) cause.getCause();
        assertThat(failure.statusCode()).isEqualTo(429);
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.responseBody()).isEqualTo("""
                event: error
                data: upstream overloaded
                data: retry later

                data: [DONE]

                """);
    }

    private ChatCompletionsClient client() {
        return new ChatCompletionsClient(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    private ChatCompletionRequest request(List<ApiMessage> messages, List<JsonObject> tools, int maxRetries) {
        return new ChatCompletionRequest(endpoint(), "deepseek-v4-flash", "system rules", messages, tools,
                Duration.ofSeconds(2), maxRetries, Duration.ofMillis(1));
    }

    private URI endpoint() {
        return URI.create("http://" + server.getAddress().getHostString() + ':' + server.getAddress().getPort()
                + "/v1/chat/completions");
    }

    private static String ordinaryResponse(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
