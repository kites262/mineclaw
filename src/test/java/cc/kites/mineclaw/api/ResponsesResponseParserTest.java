package cc.kites.mineclaw.api;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponsesResponseParserTest {
    @Test
    void parsesOrdinaryCompletedResponseAndPreservesEveryOutputItem() {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                {
                  "status": "completed",
                  "output": [
                    {"type":"reasoning","id":"rs_1","encrypted_content":"opaque"},
                    {"type":"message","id":"msg_1","role":"assistant","content":[
                      {"type":"output_text","text":"你"},
                      {"type":"output_text","text":"好"}
                    ]}
                  ],
                  "usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}
                }
                """));

        ChatCompletionResult result = parser.finish();

        assertThat(result.content()).isEqualTo("你好");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(new ApiUsage(10, 4, 14));
        assertThat(result.responseOutputItems()).hasSize(2);
        assertThat(result.responseOutputItems().get(0).get("encrypted_content").getAsString())
                .isEqualTo("opaque");
        assertThat(result.responseOutputItems().get(1).get("id").getAsString()).isEqualTo("msg_1");
    }

    @Test
    void normalizesFunctionCallsByCallIdAndKeepsTheirCompleteItems() {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                {
                  "status":"completed",
                  "output":[
                    {"type":"function_call","id":"fc_a","call_id":"call_a",
                     "name":"block_inspect","arguments":"{\\\"mode\\\":\\\"feet\\\"}","status":"completed"},
                    {"type":"function_call","id":"fc_b","call_id":"call_b",
                     "name":"item_inspect","arguments":"{}","status":"completed"}
                  ]
                }
                """));

        ChatCompletionResult result = parser.finish();

        assertThat(result.content()).isEmpty();
        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.toolCalls()).containsExactly(
                new ToolCall("call_a", "block_inspect", "{\"mode\":\"feet\"}"),
                new ToolCall("call_b", "item_inspect", "{}"));
        assertThat(result.toolCalls()).extracting(ToolCall::id).doesNotContain("fc_a", "fc_b");
        assertThat(result.responseOutputItems()).extracting(item -> item.get("id").getAsString())
                .containsExactly("fc_a", "fc_b");
    }

    @Test
    void exposesOrdinaryRefusalContentAsTheFinalReply() {
        ChatCompletionResult result = parseOrdinary("""
                {"status":"completed","output":[{"type":"message","role":"assistant",
                  "content":[{"type":"refusal","refusal":"I cannot help with that."}]}]}
                """);

        assertThat(result.content()).isEqualTo("I cannot help with that.");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.responseOutputItems()).singleElement().satisfies(item ->
                assertThat(item.getAsJsonArray("content").get(0).getAsJsonObject()
                        .get("type").getAsString()).isEqualTo("refusal"));
    }

    @Test
    void supportsOfficialIncompleteDetailsAndUpstreamStopReasons() {
        assertThat(parseOrdinary("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}
                """).finishReason()).isEqualTo("length");
        assertThat(parseOrdinary("""
                {"stop_reason":"end_turn","output":[{"type":"message","content":[
                  {"type":"output_text","text":"done"}]}]}
                """).finishReason()).isEqualTo("stop");
        assertThat(parseOrdinary("""
                {"stop_reason":"tool_use","output":[{"type":"function_call","id":"fc_1",
                  "call_id":"call_1","name":"list","arguments":"{}"}]}
                """).finishReason()).isEqualTo("tool_calls");
        assertThat(parseOrdinary("""
                {"stop_reason":"max_tokens","output":[]}
                """).finishReason()).isEqualTo("length");
    }

    @Test
    void streamsOnlyTextDeltasAndUsesCompletedResponseAsTheFinalAuthority() {
        StringBuilder visible = new StringBuilder();
        ResponsesResponseParser parser = new ResponsesResponseParser(visible::append);
        String stream = ""
                + "event: response.created\n"
                + "data: {\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\","
                + "\"output\":[]}}\n\n"
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"delta\":\"一\"}\n\n"
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                + "\"delta\":\"二\"}\n\n"
                + "event: response.output_text.done\n"
                + "data: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_1\","
                + "\"text\":\"一二\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"一二\"}]}],\"usage\":{"
                + "\"input_tokens\":7,\"output_tokens\":2,\"total_tokens\":9}}}\n\n";

        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            parser.accept(ByteBuffer.wrap(new byte[]{value}));
        }
        ChatCompletionResult result = parser.finish();

        assertThat(visible).hasToString("一二");
        assertThat(result.content()).isEqualTo("一二");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(new ApiUsage(7, 2, 9));
        assertThat(result.responseOutputItems()).singleElement().satisfies(item ->
                assertThat(item.get("id").getAsString()).isEqualTo("msg_1"));
    }

    @Test
    void synthesizesTargetShapedTextOutputWhenCompletedEventOnlyContainsMetadata() {
        StringBuilder visible = new StringBuilder();
        ResponsesResponseParser parser = new ResponsesResponseParser(visible::append);
        String stream = ""
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"id\":\"evt_1\","
                + "\"response\":{\"id\":\"resp_1\",\"model\":\"target-model\"},\"delta\":\"目\"}\n\n"
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"id\":\"evt_2\","
                + "\"response\":{\"id\":\"resp_1\",\"model\":\"target-model\"},\"delta\":\"标\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"id\":\"evt_3\",\"response\":{"
                + "\"id\":\"resp_1\",\"model\":\"target-model\",\"usage\":{"
                + "\"input_tokens\":11,\"output_tokens\":2,\"total_tokens\":13}}}\n\n"
                + "data: [DONE]\n\n"
                + "event: ping\n"
                + "data: {\"type\":\"ping\"}\n\n";
        parser.accept(StandardCharsets.UTF_8.encode(stream));

        ChatCompletionResult result = parser.finish();

        assertThat(visible).hasToString("目标");
        assertThat(result.content()).isEqualTo("目标");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(new ApiUsage(11, 2, 13));
        assertThat(result.responseOutputItems()).singleElement().satisfies(item -> {
            assertThat(item.get("type").getAsString()).isEqualTo("message");
            assertThat(item.get("role").getAsString()).isEqualTo("assistant");
            assertThat(item.getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("text").getAsString()).isEqualTo("目标");
        });
    }

    @Test
    void streamsAndSynthesizesRefusalWhenTerminalEventOnlyContainsMetadata() {
        StringBuilder visible = new StringBuilder();
        ResponsesResponseParser parser = new ResponsesResponseParser(visible::append);
        String stream = ""
                + "event: response.refusal.delta\n"
                + "data: {\"type\":\"response.refusal.delta\",\"delta\":\"不\"}\n\n"
                + "event: response.refusal.delta\n"
                + "data: {\"type\":\"response.refusal.delta\",\"delta\":\"可以\"}\n\n"
                + "event: response.refusal.done\n"
                + "data: {\"type\":\"response.refusal.done\",\"refusal\":\"不可以\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\"}}\n\n";
        parser.accept(StandardCharsets.UTF_8.encode(stream));

        ChatCompletionResult result = parser.finish();

        assertThat(visible).hasToString("不可以");
        assertThat(result.content()).isEqualTo("不可以");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.responseOutputItems()).singleElement().satisfies(item -> {
            var part = item.getAsJsonArray("content").get(0).getAsJsonObject();
            assertThat(part.get("type").getAsString()).isEqualTo("refusal");
            assertThat(part.get("refusal").getAsString()).isEqualTo("不可以");
        });
    }

    @Test
    void usesCompletedTextEventWhenCompatibleStreamOmitsTextDeltasAndTerminalOutput() {
        StringBuilder visible = new StringBuilder();
        ResponsesResponseParser parser = new ResponsesResponseParser(visible::append);
        parser.accept(StandardCharsets.UTF_8.encode("""
                event: response.output_text.done
                data: {"type":"response.output_text.done","text":"fallback"}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_1"}}

                """));

        ChatCompletionResult result = parser.finish();

        assertThat(visible).hasToString("fallback");
        assertThat(result.content()).isEqualTo("fallback");
    }

    @Test
    void mergesStreamedItemsWhenTerminalMetadataCarriesAnEmptyOutputArray() {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        String stream = ""
                + "event: response.output_item.added\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[]}}\n\n"
                + "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"保留\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{"
                + "\"status\":\"completed\",\"output\":[]}}\n\n";
        parser.accept(StandardCharsets.UTF_8.encode(stream));

        ChatCompletionResult result = parser.finish();

        assertThat(result.content()).isEqualTo("保留");
        assertThat(result.responseOutputItems()).singleElement().satisfies(item -> {
            assertThat(item.get("type").getAsString()).isEqualTo("message");
            assertThat(item.getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("text").getAsString()).isEqualTo("保留");
        });
    }

    @Test
    void acceptsStringMessageContentAndContentPartDoneFallback() {
        assertThat(parseOrdinary("""
                {"status":"completed","output":[{"type":"message",
                  "content":"plain text"}]}
                """).content()).isEqualTo("plain text");

        StringBuilder visible = new StringBuilder();
        ResponsesResponseParser parser = new ResponsesResponseParser(visible::append);
        parser.accept(StandardCharsets.UTF_8.encode(""
                + "event: response.content_part.done\n"
                + "data: {\"type\":\"response.content_part.done\",\"part\":{"
                + "\"type\":\"output_text\",\"text\":\"part fallback\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{"
                + "\"status\":\"completed\"}}\n\n"));

        assertThat(parser.finish().content()).isEqualTo("part fallback");
        assertThat(visible).hasToString("part fallback");
    }

    @Test
    void aggregatesTargetShapedFunctionDeltasAndPreservesReasoningWithoutTerminalOutput() {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        String stream = ""
                + "event: response.output_item.added\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{"
                + "\"type\":\"reasoning\",\"id\":\"rs_1\",\"status\":\"in_progress\","
                + "\"encrypted_content\":\"opaque\"}}\n\n"
                + "event: response.output_item.done\n"
                + "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{"
                + "\"type\":\"reasoning\",\"id\":\"rs_1\",\"status\":\"completed\","
                + "\"encrypted_content\":\"opaque\"}}\n\n"
                + "event: response.output_item.added\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{"
                + "\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                + "\"name\":\"read\",\"arguments\":\"\"}}\n\n"
                + "event: response.function_call_arguments.delta\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,"
                + "\"delta\":\"{\\\"path\\\":\"}\n\n"
                + "event: response.function_call_arguments.delta\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,"
                + "\"delta\":\"\\\"AGENTS.md\\\"}\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"id\":\"evt_done\",\"response\":{"
                + "\"id\":\"resp_1\",\"model\":\"target-model\",\"usage\":{"
                + "\"input_tokens\":19,\"output_tokens\":7,\"total_tokens\":26}}}\n\n"
                + "data: [DONE]\n\n"
                + "event: ping\n"
                + "data: {\"type\":\"ping\"}\n\n";
        parser.accept(StandardCharsets.UTF_8.encode(stream));

        ChatCompletionResult result = parser.finish();

        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.toolCalls()).containsExactly(
                new ToolCall("call_1", "read", "{\"path\":\"AGENTS.md\"}"));
        assertThat(result.usage()).isEqualTo(new ApiUsage(19, 7, 26));
        assertThat(result.responseOutputItems()).hasSize(2);
        assertThat(result.responseOutputItems().get(0).get("type").getAsString()).isEqualTo("reasoning");
        assertThat(result.responseOutputItems().get(0).get("status").getAsString()).isEqualTo("completed");
        assertThat(result.responseOutputItems().get(1).get("type").getAsString())
                .isEqualTo("function_call");
        assertThat(result.responseOutputItems().get(1).get("arguments").getAsString())
                .isEqualTo("{\"path\":\"AGENTS.md\"}");
    }

    @Test
    void acceptsFunctionArgumentEventsButUsesCompletedFunctionItem() {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        String stream = ""
                + "event: response.output_item.added\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{"
                + "\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                + "\"name\":\"read\",\"arguments\":\"\"}}\n\n"
                + "event: response.function_call_arguments.delta\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,"
                + "\"delta\":\"{\\\"path\\\":\"}\n\n"
                + "event: response.function_call_arguments.done\n"
                + "data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":0,"
                + "\"arguments\":\"{\\\"path\\\":\\\"AGENTS.md\\\"}\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"output\":[{\"type\":\"function_call\",\"id\":\"fc_1\","
                + "\"call_id\":\"call_1\",\"name\":\"read\","
                + "\"arguments\":\"{\\\"path\\\":\\\"AGENTS.md\\\"}\","
                + "\"status\":\"completed\"}]}}\n\n";
        parser.accept(StandardCharsets.UTF_8.encode(stream));

        ChatCompletionResult result = parser.finish();

        assertThat(result.toolCalls()).containsExactly(
                new ToolCall("call_1", "read", "{\"path\":\"AGENTS.md\"}"));
        assertThat(result.responseOutputItems()).singleElement().satisfies(item ->
                assertThat(item.get("status").getAsString()).isEqualTo("completed"));
    }

    @Test
    void requiresAResponsesTerminalEventInsteadOfTreatingDoneAsCompletion() {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                data: {"type":"response.output_text.delta","delta":"partial"}

                data: [DONE]

                """));

        assertThatThrownBy(parser::finish)
                .isInstanceOf(ChatCompletionException.class)
                .hasMessageContaining("terminal event");
    }

    @Test
    void rejectsFailedResponsesAndTypedErrorEventsWithOriginalDiagnostics() {
        ResponsesResponseParser ordinary = new ResponsesResponseParser(ignored -> { });
        ordinary.accept(StandardCharsets.UTF_8.encode("""
                {"status":"failed","error":{"type":"server_error","message":"overloaded"},"output":[]}
                """));
        Throwable ordinaryFailure = org.assertj.core.api.Assertions.catchThrowable(ordinary::finish);
        assertThat(ordinaryFailure).isInstanceOf(ChatCompletionException.class);
        assertThat(((ChatCompletionException) ordinaryFailure).retryable()).isTrue();
        assertThat(((ChatCompletionException) ordinaryFailure).responseBody())
                .contains("server_error", "overloaded");

        ResponsesResponseParser streamed = new ResponsesResponseParser(ignored -> { });
        Throwable streamFailure = org.assertj.core.api.Assertions.catchThrowable(() ->
                streamed.accept(StandardCharsets.UTF_8.encode(""
                        + "event: error\n"
                        + "data: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"bad input\"}}\n\n")));
        assertThat(streamFailure).isInstanceOf(ChatCompletionException.class);
        assertThat(((ChatCompletionException) streamFailure).retryable()).isFalse();
        assertThat(((ChatCompletionException) streamFailure).responseBody())
                .contains("invalid_request_error", "bad input");
    }

    @Test
    void rejectsEmptyAndDuplicateFunctionCallIdsAndMissingNames() {
        assertThatThrownBy(() -> parseOrdinary("""
                {"status":"completed","output":[{"type":"function_call","call_id":"",
                  "name":"list","arguments":"{}"}]}
                """))
                .isInstanceOf(ChatCompletionException.class).hasMessageContaining("empty call_id");
        assertThatThrownBy(() -> parseOrdinary("""
                {"status":"completed","output":[
                  {"type":"function_call","call_id":"call_same","name":"list","arguments":"{}"},
                  {"type":"function_call","call_id":"call_same","name":"read","arguments":"{}"}]}
                """))
                .isInstanceOf(ChatCompletionException.class).hasMessageContaining("duplicate function call_id");
        assertThatThrownBy(() -> parseOrdinary("""
                {"status":"completed","output":[{"type":"function_call","call_id":"call_1",
                  "name":"","arguments":"{}"}]}
                """))
                .isInstanceOf(ChatCompletionException.class).hasMessageContaining("empty function name");
    }

    private static ChatCompletionResult parseOrdinary(String body) {
        ResponsesResponseParser parser = new ResponsesResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode(body));
        return parser.finish();
    }
}
