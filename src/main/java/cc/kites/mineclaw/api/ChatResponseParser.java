package cc.kites.mineclaw.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Incrementally parses SSE while retaining compatibility with an ordinary JSON response. */
final class ChatResponseParser {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final Consumer<String> deltaConsumer;
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder eventData = new StringBuilder();
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, MutableToolCall> toolCalls = new TreeMap<>();
    private boolean sseSeen;
    private boolean done;
    private boolean parsedEvent;
    private String finishReason;
    private ApiUsage usage;
    private int receivedBytes;

    ChatResponseParser(Consumer<String> deltaConsumer) {
        this.deltaConsumer = Objects.requireNonNull(deltaConsumer, "deltaConsumer");
    }

    void accept(ByteBuffer source) {
        ByteBuffer bytes = source.slice();
        while (bytes.hasRemaining()) {
            if (++receivedBytes > MAX_RESPONSE_BYTES) {
                throw new ChatCompletionException("Chat Completions response exceeded 4 MiB", 200, false);
            }
            int value = Byte.toUnsignedInt(bytes.get());
            if (!sseSeen) {
                raw.write(value);
            }
            if (value == '\n') {
                consumeLine();
            } else {
                line.write(value);
            }
        }
    }

    ChatCompletionResult finish() {
        if (line.size() > 0) {
            consumeLine();
        }
        if (sseSeen) {
            dispatchEvent();
            if (!parsedEvent && !done) {
                throw malformed("SSE response contained no Chat Completions event", null);
            }
            if (finishReason == null) {
                throw malformed("SSE response ended before finish_reason", null);
            }
            return streamedResult();
        }
        return parseOrdinaryJson(new String(raw.toByteArray(), StandardCharsets.UTF_8));
    }

    private void consumeLine() {
        byte[] bytes = line.toByteArray();
        line.reset();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            dispatchEvent();
            return;
        }
        if (text.startsWith(":")) {
            return;
        }
        if (text.startsWith("data:")) {
            sseSeen = true;
            String data = text.substring("data:".length());
            if (data.startsWith(" ")) {
                data = data.substring(1);
            }
            if (!eventData.isEmpty()) {
                eventData.append('\n');
            }
            eventData.append(data);
        }
    }

    private void dispatchEvent() {
        if (eventData.isEmpty()) {
            return;
        }
        String payload = eventData.toString();
        eventData.setLength(0);
        if ("[DONE]".equals(payload.trim())) {
            done = true;
            return;
        }
        parseEvent(payload);
        parsedEvent = true;
    }

    private void parseEvent(String payload) {
        JsonObject root;
        try {
            root = JsonParser.parseString(payload).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw malformed("Malformed Chat Completions SSE event", exception);
        }
        throwIfError(root, 200);
        usage = parseUsage(root, usage);
        JsonArray choices = array(root, "choices");
        if (choices == null) {
            return;
        }
        for (JsonElement choiceElement : choices) {
            if (!choiceElement.isJsonObject()) {
                continue;
            }
            JsonObject choice = choiceElement.getAsJsonObject();
            int choiceIndex = integer(choice, "index", 0);
            if (choiceIndex != 0) {
                continue;
            }
            String reason = nullableString(choice, "finish_reason");
            if (reason != null) {
                finishReason = reason;
            }
            JsonObject delta = object(choice, "delta");
            if (delta != null) {
                appendDelta(delta);
            } else {
                JsonObject message = object(choice, "message");
                if (message != null) {
                    appendDelta(message);
                }
            }
        }
    }

    private void appendDelta(JsonObject delta) {
        String text = nullableString(delta, "content");
        if (text != null && !text.isEmpty()) {
            content.append(text);
            deltaConsumer.accept(text);
        }
        JsonArray calls = array(delta, "tool_calls");
        if (calls == null) {
            return;
        }
        int fallbackIndex = 0;
        for (JsonElement callElement : calls) {
            if (!callElement.isJsonObject()) {
                fallbackIndex++;
                continue;
            }
            JsonObject call = callElement.getAsJsonObject();
            int index = integer(call, "index", fallbackIndex++);
            MutableToolCall accumulator = toolCalls.computeIfAbsent(index, ignored -> new MutableToolCall());
            accumulator.id.append(string(call, "id"));
            JsonObject function = object(call, "function");
            if (function != null) {
                accumulator.name.append(string(function, "name"));
                accumulator.arguments.append(string(function, "arguments"));
            }
        }
    }

    private ChatCompletionResult streamedResult() {
        List<ToolCall> calls = toolCalls.values().stream().map(MutableToolCall::toToolCall).toList();
        return new ChatCompletionResult(content.toString(), validateToolCalls(calls), finishReason, usage);
    }

    private static ChatCompletionResult parseOrdinaryJson(String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw malformed("Malformed Chat Completions JSON response", exception);
        }
        throwIfError(root, 200);
        JsonArray choices = array(root, "choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw malformed("Chat Completions response did not contain a choice", null);
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = object(choice, "message");
        if (message == null) {
            throw malformed("Chat Completions choice did not contain a message", null);
        }
        String content = nullableString(message, "content");
        List<ToolCall> calls = new ArrayList<>();
        JsonArray callArray = array(message, "tool_calls");
        if (callArray != null) {
            for (JsonElement element : callArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject call = element.getAsJsonObject();
                JsonObject function = object(call, "function");
                calls.add(new ToolCall(string(call, "id"),
                        function == null ? "" : string(function, "name"),
                        function == null ? "" : string(function, "arguments")));
            }
        }
        return new ChatCompletionResult(content, validateToolCalls(calls), nullableString(choice, "finish_reason"),
                parseUsage(root, null));
    }

    /** Rejects incomplete identities only once all streamed fragments have been assembled. */
    private static List<ToolCall> validateToolCalls(List<ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        for (ToolCall call : calls) {
            if (call.id().isBlank()) {
                throw malformed("Chat Completions tool call had an empty id", null);
            }
            if (!ids.add(call.id())) {
                throw malformed("Chat Completions response contained duplicate tool call id: " + call.id(), null);
            }
            if (call.name().isBlank()) {
                throw malformed("Chat Completions tool call had an empty function name", null);
            }
        }
        return calls;
    }

    static ChatCompletionException errorResponse(int statusCode, String body) {
        JsonObject root = null;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                root = parsed.getAsJsonObject();
            }
        } catch (JsonParseException ignored) {
            // A status and a sanitized generic message are sufficient for malformed error bodies.
        }
        boolean retryable = ChatCompletionsClient.isRetryableStatus(statusCode)
                || (root != null && isRecoverableError(root));
        return new ChatCompletionException("Chat Completions request failed with HTTP " + statusCode,
                statusCode, retryable);
    }

    private static void throwIfError(JsonObject root, int statusCode) {
        if (!root.has("error") || !root.get("error").isJsonObject()) {
            return;
        }
        boolean retryable = isRecoverableError(root);
        throw new ChatCompletionException("Chat Completions endpoint returned an error", statusCode, retryable);
    }

    private static boolean isRecoverableError(JsonObject root) {
        JsonObject error = object(root, "error");
        if (error == null) {
            return false;
        }
        String type = string(error, "type").toLowerCase(java.util.Locale.ROOT);
        String code = string(error, "code").toLowerCase(java.util.Locale.ROOT);
        String message = string(error, "message").toLowerCase(java.util.Locale.ROOT);
        return recoverableValue(type) || recoverableValue(code) || recoverableValue(message);
    }

    private static boolean recoverableValue(String value) {
        return value.contains("rate_limit")
                || value.contains("server_error")
                || value.contains("overloaded")
                || value.contains("bad_response_status_code")
                || value.contains("timeout")
                || value.contains("temporarily_unavailable")
                || value.contains("service_unavailable");
    }

    private static ApiUsage parseUsage(JsonObject root, ApiUsage fallback) {
        JsonObject value = object(root, "usage");
        if (value == null) {
            return fallback;
        }
        return new ApiUsage(nullableInteger(value, "prompt_tokens"),
                nullableInteger(value, "completion_tokens"), nullableInteger(value, "total_tokens"));
    }

    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String nullableString(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    private static String string(JsonObject root, String name) {
        String value = nullableString(root, name);
        return value == null ? "" : value;
    }

    private static Integer nullableInteger(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int integer(JsonObject root, String name, int fallback) {
        Integer value = nullableInteger(root, name);
        return value == null ? fallback : value;
    }

    private static ChatCompletionException malformed(String message, Throwable cause) {
        return new ChatCompletionException(message, 200, true, cause);
    }

    private static final class MutableToolCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private ToolCall toToolCall() {
            return new ToolCall(id.toString(), name.toString(), arguments.toString());
        }
    }
}
