package cc.kites.mineclaw.turn;

import cc.kites.mineclaw.api.ChatCompletionResult;
import cc.kites.mineclaw.api.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnProtocolTest {
    @Test
    void structuredToolCallsTakePriorityAndStopEndsWithFinalMessage() {
        assertThat(TurnProtocol.decide(new ChatCompletionResult("", List.of(
                new ToolCall("call_1", "list", "{}")), "tool_calls", null)))
                .isEqualTo(TurnProtocol.Decision.TOOL_CALLS);
        assertThat(TurnProtocol.decide(new ChatCompletionResult("done", List.of(), "stop", null)))
                .isEqualTo(TurnProtocol.Decision.FINAL_MESSAGE);
    }

    @Test
    void missingOrNonTerminalFinishReasonsAreProtocolErrors() {
        assertThat(TurnProtocol.decide(new ChatCompletionResult("ordinary", List.of(), null, null)))
                .isEqualTo(TurnProtocol.Decision.PROTOCOL_ERROR);
        for (String reason : List.of("length", "content_filter", "tool_calls", "unknown")) {
            assertThat(TurnProtocol.decide(new ChatCompletionResult("partial", List.of(), reason, null)))
                    .as(reason).isEqualTo(TurnProtocol.Decision.PROTOCOL_ERROR);
        }
        assertThat(TurnProtocol.decide(new ChatCompletionResult("", List.of(), null, null)))
                .isEqualTo(TurnProtocol.Decision.PROTOCOL_ERROR);
        for (String reason : List.of("length", "content_filter", "stop")) {
            assertThat(TurnProtocol.decide(new ChatCompletionResult("", List.of(
                    new ToolCall("call_1", "run_command", "{}")), reason, null)))
                    .as("tool call with " + reason).isEqualTo(TurnProtocol.Decision.PROTOCOL_ERROR);
        }
        assertThat(TurnProtocol.decide(new ChatCompletionResult("", List.of(
                new ToolCall("call_1", "run_command", "{}")), null, null)))
                .isEqualTo(TurnProtocol.Decision.PROTOCOL_ERROR);
    }
}
