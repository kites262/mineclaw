package cc.kites.mineclaw.turn;

import cc.kites.mineclaw.api.ChatCompletionResult;

import java.util.Objects;

/** Pure protocol decision used after a complete streamed response has been assembled. */
public final class TurnProtocol {
    private TurnProtocol() { }

    public static Decision decide(ChatCompletionResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.toolCalls().isEmpty()) {
            return "tool_calls".equals(result.finishReason())
                    ? Decision.TOOL_CALLS : Decision.PROTOCOL_ERROR;
        }
        if ("stop".equals(result.finishReason())) {
            return Decision.FINAL_MESSAGE;
        }
        return Decision.PROTOCOL_ERROR;
    }

    public enum Decision {
        TOOL_CALLS,
        FINAL_MESSAGE,
        PROTOCOL_ERROR
    }
}
