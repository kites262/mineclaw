package cc.kites.mineclaw.turn;

import cc.kites.mineclaw.api.ApiMessage;
import cc.kites.mineclaw.api.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactionPlanTest {
    @Test
    void retainsRecentWholeTurnsAndAlwaysLeavesOlderMaterialToCompact() {
        List<ApiMessage> old = turn("old");
        List<ApiMessage> middle = turn("middle");
        List<ApiMessage> recent = turn("recent");

        ContextCompactionPlan plan = ContextCompactionPlan.select(
                List.of(old, middle, recent), 4, ignored -> 2);

        assertThat(plan.compactedTurns()).containsExactly(old);
        assertThat(plan.retainedTurns()).containsExactly(middle, recent);
    }

    @Test
    void neverSplitsAssistantToolCallAndResultsWhenBudgetIsTooSmall() {
        List<ApiMessage> toolTurn = List.of(
                ApiMessage.user("operate"),
                ApiMessage.assistantToolCalls(List.of(new ToolCall("call", "run_command", "{}"))),
                ApiMessage.tool("call", "{\"status\":\"dispatched\"}"),
                ApiMessage.assistant("done"));
        List<ApiMessage> recent = turn("recent");

        ContextCompactionPlan plan = ContextCompactionPlan.select(
                List.of(toolTurn, recent), 0, ignored -> 100);

        assertThat(plan.compactedTurns()).containsExactly(toolTurn, recent);
        assertThat(plan.retainedTurns()).isEmpty();
        assertThat(plan.compactedTurns().getFirst()).hasSize(4);
    }

    private static List<ApiMessage> turn(String value) {
        return List.of(ApiMessage.user(value), ApiMessage.assistant(value + " answer"));
    }
}
