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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionsClientTest {
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
                ApiMessage.user("看哪里"),
                ApiMessage.assistantToolCalls(List.of(new ToolCall("old_call", "look", "{}"))),
                ApiMessage.tool("old_call", "{\"status\":\"ok\"}")), List.of(tool), 0);

        ChatCompletionResult result = client().complete(request, "test-secret", null).join();

        assertThat(authorization).hasValue("Bearer test-secret");
        assertThat(result.content()).isEqualTo("完成");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(new ApiUsage(8, 2, 10));

        JsonObject sent = requestBody.get();
        assertThat(sent.get("model").getAsString()).isEqualTo("deepseek-v4-flash");
        assertThat(sent.get("stream").getAsBoolean()).isTrue();
        assertThat(sent.getAsJsonObject("stream_options").get("include_usage").getAsBoolean()).isTrue();
        assertThat(sent.getAsJsonArray("tools")).hasSize(1);
        JsonArray messages = sent.getAsJsonArray("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getAsJsonObject().get("role").getAsString()).isEqualTo("system");
        assertThat(messages.get(0).getAsJsonObject().get("content").getAsString()).isEqualTo("system rules");
        assertThat(messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls")).hasSize(1);
        assertThat(messages.get(3).getAsJsonObject().get("tool_call_id").getAsString()).isEqualTo("old_call");
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
                "secret", deltas::append).join();

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

        ChatCompletionResult result = client().completeObserved(
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

        client().complete(request(List.of(), List.of(), 0), "secret", null).join();

        assertThat(requestBody.get().has("tools")).isFalse();
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
                "secret", null).join();

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

        ChatCompletionResult result = client().complete(request(List.of(), List.of(), 1), "secret", null).join();

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

        ChatCompletionResult result = client().complete(request, "secret", null).join();

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
                        "never-print-this", null).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChatCompletionException.class)
                .hasMessageNotContaining("never-print-this");
        assertThat(attempts).hasValue(1);
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
