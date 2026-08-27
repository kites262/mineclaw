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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Incrementally parses an OpenAI-compatible Responses JSON body or typed SSE stream. */
final class ResponsesResponseParser {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final Consumer<String> deltaConsumer;
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder eventData = new StringBuilder();
    private final StringBuilder streamedText = new StringBuilder();
    private final StringBuilder streamedRefusal = new StringBuilder();
    private final Map<Integer, JsonObject> streamedOutputItems = new TreeMap<>();
    private final Map<String, Integer> streamedItemIndexes = new java.util.HashMap<>();
    private final Map<Integer, StringBuilder> functionArgumentDeltas = new java.util.HashMap<>();
    private final Map<Integer, String> completedFunctionArguments = new java.util.HashMap<>();
    private boolean sseSeen;
    private String eventName = "";
    private JsonObject terminalResponse;
    private int receivedBytes;
    private int nextSyntheticOutputIndex;

    ResponsesResponseParser(Consumer<String> deltaConsumer) {
        this.deltaConsumer = Objects.requireNonNull(deltaConsumer, "deltaConsumer");
    }

    void accept(ByteBuffer source) {
        ByteBuffer bytes = source.slice();
        while (bytes.hasRemaining()) {
            if (++receivedBytes > MAX_RESPONSE_BYTES) {
                throw new ChatCompletionException("Responses response exceeded 4 MiB", 200, false);
            }
            int value = Byte.toUnsignedInt(bytes.get());
            raw.write(value);
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
            if (terminalResponse == null) {
                throw malformed("Responses SSE stream ended before a terminal event", null);
            }
            return parseResponse(terminalResponse, terminalResponse.toString());
        }
        String body = raw.toString(StandardCharsets.UTF_8);
        return parseResponse(parseObject(body, "Malformed Responses JSON response"), body);
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
        if (text.startsWith("event:")) {
            sseSeen = true;
            eventName = eventField(text, "event:");
            return;
        }
        if (text.startsWith("data:")) {
            sseSeen = true;
            String data = eventField(text, "data:");
            if (!eventData.isEmpty()) {
                eventData.append('\n');
            }
            eventData.append(data);
        }
    }

    private static String eventField(String line, String prefix) {
        String value = line.substring(prefix.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private void dispatchEvent() {
        if (eventData.isEmpty()) {
            eventName = "";
            return;
        }
        String payload = eventData.toString();
        eventData.setLength(0);
        String fallbackType = eventName;
        eventName = "";
        if ("[DONE]".equals(payload.trim())) {
            return;
        }

        JsonObject event = parseObject(payload, "Malformed Responses SSE event");
        String type = nullableString(event, "type");
        if (type == null || type.isBlank()) {
            type = fallbackType;
        }
        switch (type) {
            case "response.output_text.delta" -> {
                String delta = nullableString(event, "delta");
                if (delta != null && !delta.isEmpty()) {
                    streamedText.append(delta);
                    deltaConsumer.accept(delta);
                }
            }
            case "response.output_text.done" -> recordCompletedVisibleContent(
                    event, "text", streamedText);
            case "response.content_part.done" -> recordCompletedContentPart(event);
            case "response.refusal.delta" -> {
                String delta = nullableString(event, "delta");
                if (delta != null && !delta.isEmpty()) {
                    streamedRefusal.append(delta);
                    deltaConsumer.accept(delta);
                }
            }
            case "response.refusal.done" -> recordCompletedVisibleContent(
                    event, "refusal", streamedRefusal);
            case "response.output_item.added" -> recordOutputItem(event, false);
            case "response.output_item.done" -> recordOutputItem(event, true);
            case "response.function_call_arguments.delta" -> recordFunctionArgumentsDelta(event);
            case "response.function_call_arguments.done" -> recordFunctionArgumentsDone(event);
            case "response.completed", "response.incomplete" -> recordTerminal(event, payload, type);
            case "response.failed" -> throw failedEvent(event, payload);
            case "error" -> throw endpointError(payload);
            default -> {
                // Other typed lifecycle, content-part, reasoning, and Tool events are represented
                // authoritatively by the terminal response's complete output array.
            }
        }
    }

    private void recordCompletedVisibleContent(JsonObject event, String field,
                                               StringBuilder accumulator) {
        if (!accumulator.isEmpty()) {
            return;
        }
        String completed = nullableString(event, field);
        if (completed != null && !completed.isEmpty()) {
            accumulator.append(completed);
            deltaConsumer.accept(completed);
        }
    }

    /**
     * A few OpenAI-compatible gateways expose the complete content part on
     * {@code response.content_part.done} but omit the corresponding
     * output_text/refusal done event. Treat that part as a fallback only: any
     * deltas already received remain authoritative and must not be duplicated.
     */
    private void recordCompletedContentPart(JsonObject event) {
        JsonObject part = object(event, "part");
        if (part == null) {
            return;
        }
        String type = nullableString(part, "type");
        if ("output_text".equals(type)) {
            recordCompletedVisibleContent(part, "text", streamedText);
        } else if ("refusal".equals(type)) {
            recordCompletedVisibleContent(part, "refusal", streamedRefusal);
        }
    }

    private void recordOutputItem(JsonObject event, boolean completed) {
        JsonObject item = object(event, "item");
        if (item == null) {
            throw malformed("Responses output item event did not contain an item", null);
        }
        int index = outputIndex(event, item, true);
        JsonObject copy = item.deepCopy();
        streamedOutputItems.put(index, copy);
        indexItem(index, copy);
        if (completed && "function_call".equals(nullableString(copy, "type"))) {
            String arguments = nullableString(copy, "arguments");
            if (arguments != null) {
                completedFunctionArguments.put(index, arguments);
            }
        }
    }

    private void recordFunctionArgumentsDelta(JsonObject event) {
        int index = outputIndex(event, null, true);
        ensureFunctionItem(event, index);
        String delta = nullableString(event, "delta");
        if (delta != null) {
            functionArgumentDeltas.computeIfAbsent(index, ignored -> new StringBuilder()).append(delta);
        }
    }

    private void recordFunctionArgumentsDone(JsonObject event) {
        int index = outputIndex(event, null, true);
        ensureFunctionItem(event, index);
        String arguments = nullableString(event, "arguments");
        if (arguments != null) {
            completedFunctionArguments.put(index, arguments);
        }
    }

    private void ensureFunctionItem(JsonObject event, int index) {
        if (streamedOutputItems.containsKey(index)) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call");
        String itemId = nullableString(event, "item_id");
        if (itemId != null) {
            item.addProperty("id", itemId);
        }
        streamedOutputItems.put(index, item);
        indexItem(index, item);
    }

    private int outputIndex(JsonObject event, JsonObject item, boolean allocate) {
        Integer explicit = nullableInteger(event, "output_index");
        if (explicit != null && explicit >= 0) {
            nextSyntheticOutputIndex = Math.max(nextSyntheticOutputIndex, explicit + 1);
            return explicit;
        }
        String itemId = nullableString(event, "item_id");
        if (itemId == null && item != null) {
            itemId = nullableString(item, "id");
        }
        Integer known = itemId == null ? null : streamedItemIndexes.get(itemId);
        if (known != null) {
            return known;
        }
        if (!allocate) {
            return -1;
        }
        while (streamedOutputItems.containsKey(nextSyntheticOutputIndex)) {
            nextSyntheticOutputIndex++;
        }
        return nextSyntheticOutputIndex++;
    }

    private void indexItem(int index, JsonObject item) {
        String itemId = nullableString(item, "id");
        if (itemId != null && !itemId.isBlank()) {
            streamedItemIndexes.put(itemId, index);
        }
    }

    private void recordTerminal(JsonObject event, String payload, String eventType) {
        if (terminalResponse != null) {
            throw malformed("Responses SSE stream contained multiple terminal events", null);
        }
        JsonObject response = object(event, "response");
        if (response == null) {
            response = event;
        }
        throwIfFailed(response, payload);
        terminalResponse = completeTerminalResponse(response, eventType);
    }

    private JsonObject completeTerminalResponse(JsonObject response, String eventType) {
        JsonObject completed = response.deepCopy();
        if (!completed.has("status")) {
            completed.addProperty("status", "response.incomplete".equals(eventType)
                    ? "incomplete" : "completed");
        }
        if (completed.has("output")) {
            JsonElement existing = completed.get("output");
            // Some gateways send output: [] on the terminal metadata event,
            // even though the stream already delivered output items/text.
            // Preserve a non-empty authoritative output, but synthesize the
            // streamed result when the array is empty and useful data exists.
            if (!existing.isJsonArray()
                    || !existing.getAsJsonArray().isEmpty()
                    || (streamedOutputItems.isEmpty() && !hasStreamedVisibleContent())) {
                return completed;
            }
        }

        JsonArray output = new JsonArray();
        boolean hasMessage = false;
        for (Map.Entry<Integer, JsonObject> entry : streamedOutputItems.entrySet()) {
            JsonObject item = materializedOutputItem(entry.getKey(), entry.getValue());
            if ("message".equals(nullableString(item, "type"))) {
                hasMessage = true;
                fillStreamedMessageIfEmpty(item);
            }
            output.add(item);
        }
        if (!hasMessage && hasStreamedVisibleContent()) {
            output.add(streamedMessage());
        }
        completed.add("output", output);
        return completed;
    }

    private JsonObject materializedOutputItem(int index, JsonObject source) {
        JsonObject item = source.deepCopy();
        if (!"function_call".equals(nullableString(item, "type"))) {
            return item;
        }
        String completed = completedFunctionArguments.get(index);
        if (completed != null) {
            item.addProperty("arguments", completed);
            return item;
        }
        StringBuilder deltas = functionArgumentDeltas.get(index);
        if (deltas != null && !deltas.isEmpty()) {
            item.addProperty("arguments", deltas.toString());
        }
        return item;
    }

    private void fillStreamedMessageIfEmpty(JsonObject item) {
        if (!hasStreamedVisibleContent() || messageHasVisibleContent(item)) {
            return;
        }
        JsonArray content = new JsonArray();
        appendStreamedContent(content);
        item.add("content", content);
    }

    private boolean hasStreamedVisibleContent() {
        return !streamedText.isEmpty() || !streamedRefusal.isEmpty();
    }

    private static boolean messageHasVisibleContent(JsonObject item) {
        JsonArray content = array(item, "content");
        if (content == null) {
            return false;
        }
        for (JsonElement element : content) {
            if (element.isJsonObject()) {
                String type = nullableString(element.getAsJsonObject(), "type");
                if ("output_text".equals(type) || "refusal".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonObject streamedMessage() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        appendStreamedContent(content);
        message.add("content", content);
        return message;
    }

    private void appendStreamedContent(JsonArray content) {
        if (!streamedText.isEmpty()) {
            content.add(outputText(streamedText.toString()));
        }
        if (!streamedRefusal.isEmpty()) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "refusal");
            part.addProperty("refusal", streamedRefusal.toString());
            content.add(part);
        }
    }

    private static JsonObject outputText(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "output_text");
        part.addProperty("text", text);
        return part;
    }

    private static ChatCompletionException failedEvent(JsonObject event, String payload) {
        JsonObject response = object(event, "response");
        return response == null ? endpointError(payload) : failedResponse(response, payload);
    }

    private static ChatCompletionResult parseResponse(JsonObject response, String upstreamResponse) {
        throwIfFailed(response, upstreamResponse);
        JsonArray output = array(response, "output");
        if (output == null) {
            throw malformed("Responses response did not contain an output array", null);
        }

        StringBuilder content = new StringBuilder();
        ArrayList<ToolCall> calls = new ArrayList<>();
        ArrayList<JsonObject> outputItems = new ArrayList<>();
        for (JsonElement element : output) {
            if (!element.isJsonObject()) {
                throw malformed("Responses output contained a non-object item", null);
            }
            JsonObject item = element.getAsJsonObject();
            outputItems.add(item.deepCopy());
            String type = nullableString(item, "type");
            if ("message".equals(type)) {
                appendMessageText(item, content);
            } else if ("function_call".equals(type)) {
                calls.add(new ToolCall(string(item, "call_id"), string(item, "name"),
                        string(item, "arguments")));
            }
        }
        validateToolCalls(calls);
        String finishReason = finishReason(response, !calls.isEmpty());
        return new ChatCompletionResult(content.toString(), calls, finishReason,
                parseUsage(response), "", outputItems);
    }

    private static void appendMessageText(JsonObject message, StringBuilder content) {
        JsonElement rawContent = message.get("content");
        if (rawContent != null && rawContent.isJsonPrimitive()
                && rawContent.getAsJsonPrimitive().isString()) {
            content.append(rawContent.getAsString());
            return;
        }
        JsonArray parts = array(message, "content");
        if (parts == null) {
            return;
        }
        for (JsonElement element : parts) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            String type = nullableString(part, "type");
            String text = switch (type == null ? "" : type) {
                case "output_text" -> nullableString(part, "text");
                case "refusal" -> nullableString(part, "refusal");
                default -> null;
            };
            if (text != null) {
                content.append(text);
            }
        }
    }

    private static void validateToolCalls(List<ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        for (ToolCall call : calls) {
            if (call.id().isBlank()) {
                throw malformed("Responses function call had an empty call_id", null);
            }
            if (!ids.add(call.id())) {
                throw malformed("Responses response contained duplicate function call_id: " + call.id(), null);
            }
            if (call.name().isBlank()) {
                throw malformed("Responses function call had an empty function name", null);
            }
        }
    }

    private static String finishReason(JsonObject response, boolean hasToolCalls) {
        String status = nullableString(response, "status");
        if ("failed".equals(status) || "cancelled".equals(status)) {
            throw failedResponse(response, response.toString());
        }

        String stopReason = nullableString(response, "stop_reason");
        if (stopReason != null && !stopReason.isBlank()) {
            return normalizeStopReason(stopReason, hasToolCalls);
        }
        if ("completed".equals(status)) {
            return hasToolCalls ? "tool_calls" : "stop";
        }
        if ("incomplete".equals(status)) {
            JsonObject details = object(response, "incomplete_details");
            String reason = details == null ? null : nullableString(details, "reason");
            return reason == null || reason.isBlank()
                    ? "incomplete" : normalizeStopReason(reason, hasToolCalls);
        }
        if (status == null || status.isBlank()) {
            throw malformed("Responses response did not contain status or stop_reason", null);
        }
        throw malformed("Responses response ended with non-terminal status: " + status, null);
    }

    private static String normalizeStopReason(String reason, boolean hasToolCalls) {
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "stop", "end_turn", "completed" -> hasToolCalls ? "tool_calls" : "stop";
            case "tool_calls", "tool_call", "function_call", "function_calls", "tool_use" -> "tool_calls";
            case "length", "max_tokens", "max_output_tokens" -> "length";
            case "content_filter", "safety" -> "content_filter";
            default -> reason;
        };
    }

    private static ApiUsage parseUsage(JsonObject response) {
        JsonObject usage = object(response, "usage");
        if (usage == null) {
            return null;
        }
        return new ApiUsage(nullableInteger(usage, "input_tokens"),
                nullableInteger(usage, "output_tokens"), nullableInteger(usage, "total_tokens"));
    }

    private static void throwIfFailed(JsonObject response, String upstreamResponse) {
        String status = nullableString(response, "status");
        if (object(response, "error") != null) {
            throw endpointError(upstreamResponse);
        }
        if ("failed".equals(status) || "cancelled".equals(status)) {
            throw failedResponse(response, upstreamResponse);
        }
    }

    private static ChatCompletionException failedResponse(JsonObject response, String upstreamResponse) {
        return new ChatCompletionException("Responses endpoint returned a failed response", 200,
                isRecoverable(upstreamResponse), "", upstreamResponse);
    }

    private static ChatCompletionException endpointError(String upstreamResponse) {
        return new ChatCompletionException("Responses endpoint returned an error", 200,
                isRecoverable(upstreamResponse), "", upstreamResponse);
    }

    private static boolean isRecoverable(String response) {
        String value = response == null ? "" : response.toLowerCase(Locale.ROOT);
        return value.contains("rate_limit")
                || value.contains("server_error")
                || value.contains("overloaded")
                || value.contains("bad_response_status_code")
                || value.contains("timeout")
                || value.contains("temporarily_unavailable")
                || value.contains("service_unavailable");
    }

    private static JsonObject parseObject(String source, String message) {
        try {
            return JsonParser.parseString(source).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw malformed(message, exception);
        }
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

    private static ChatCompletionException malformed(String message, Throwable cause) {
        return new ChatCompletionException(message, 200, true, cause);
    }
}
