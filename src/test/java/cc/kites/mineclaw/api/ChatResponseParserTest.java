package cc.kites.mineclaw.api;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatResponseParserTest {
    @Test
    void parsesArbitrarilyFragmentedSseWithMultipleToolCallsAndUsage() {
        StringBuilder deltas = new StringBuilder();
        ChatResponseParser parser = new ChatResponseParser(deltas::append);
        String response = ""
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"}}]}\r\n\r\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"inven\",\"arguments\":\"{\"}},"
                + "{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"look_\",\"arguments\":\"{\\\"dis\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"block\",\"arguments\":\"tance\\\":8}\"}},"
                + "{\"index\":1,\"function\":{\"name\":\"tory\",\"arguments\":\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"total_tokens\":14}}\n\n"
                + "data: [DONE]\n\n";

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            parser.accept(ByteBuffer.wrap(new byte[]{value}));
        }

        ChatCompletionResult result = parser.finish();
        assertThat(deltas).hasToString("你好");
        assertThat(result.content()).isEqualTo("你好");
        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.toolCalls()).containsExactly(
                new ToolCall("call_a", "look_block", "{\"distance\":8}"),
                new ToolCall("call_b", "inventory", "{}"));
        assertThat(result.usage()).isEqualTo(new ApiUsage(10, 4, 14));
    }

    @Test
    void parsesOrdinaryJsonResponse() {
        ChatResponseParser parser = new ChatResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                {
                  "choices": [{
                    "message": {
                      "content": null,
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "feet_block", "arguments": "{}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"total_tokens": 22}
                }
                """));

        ChatCompletionResult result = parser.finish();
        assertThat(result.content()).isEmpty();
        assertThat(result.toolCalls()).containsExactly(new ToolCall("call_1", "feet_block", "{}"));
        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.usage()).isEqualTo(new ApiUsage(null, null, 22));
    }

    @Test
    void rejectsAnAbruptlyClosedPartialStream() {
        ChatResponseParser parser = new ChatResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"));

        assertThatThrownBy(parser::finish)
                .isInstanceOf(ChatCompletionException.class)
                .hasMessageContaining("finish_reason");
    }

    @Test
    void doneMarkerDoesNotReplaceTheRequiredFinishReason() {
        ChatResponseParser parser = new ChatResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                data: {"choices":[{"index":0,"delta":{"content":"partial"}}]}

                data: [DONE]

                """));

        assertThatThrownBy(parser::finish)
                .isInstanceOf(ChatCompletionException.class)
                .hasMessageContaining("finish_reason");
    }

    @Test
    void rejectsEmptyAndDuplicateToolCallIdsAtTheCompletedResponseBoundary() {
        assertThatThrownBy(() -> parseOrdinary("""
                [
                  {"id":"", "function":{"name":"list", "arguments":"{}"}}
                ]
                """))
                .isInstanceOf(ChatCompletionException.class)
                .hasMessageContaining("empty id");

        assertThatThrownBy(() -> parseOrdinary("""
                [
                  {"id":"call_same", "function":{"name":"list", "arguments":"{}"}},
                  {"id":"call_same", "function":{"name":"read", "arguments":"{\\\"path\\\":\\\"a\\\"}"}}
                ]
                """))
                .isInstanceOf(ChatCompletionException.class)
                .hasMessageContaining("duplicate tool call id")
                .hasMessageContaining("call_same");
    }

    @Test
    void rejectsEmptyFunctionNameAfterStreamingFragmentsAreAssembled() {
        ChatResponseParser parser = new ChatResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """));

        assertThatThrownBy(parser::finish)
                .isInstanceOf(ChatCompletionException.class)
                .hasMessageContaining("empty function name");
    }

    private static ChatCompletionResult parseOrdinary(String toolCalls) {
        ChatResponseParser parser = new ChatResponseParser(ignored -> { });
        parser.accept(StandardCharsets.UTF_8.encode("""
                {
                  "choices": [{
                    "message": {"content": null, "tool_calls": %s},
                    "finish_reason": "tool_calls"
                  }]
                }
                """.formatted(toolCalls)));
        return parser.finish();
    }
}
