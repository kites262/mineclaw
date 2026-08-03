package cc.kites.mineclaw.turn;

import cc.kites.mineclaw.api.ApiMessage;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Deterministically partitions completed history without ever splitting a Turn. */
record ContextCompactionPlan(List<List<ApiMessage>> compactedTurns,
                             List<List<ApiMessage>> retainedTurns) {
    ContextCompactionPlan {
        compactedTurns = copy(compactedTurns);
        retainedTurns = copy(retainedTurns);
        if (compactedTurns.isEmpty()) {
            throw new IllegalArgumentException("a compaction plan must compact at least one Turn");
        }
    }

    static ContextCompactionPlan select(List<List<ApiMessage>> history, int recentTokenBudget,
                                        ToIntFunction<List<ApiMessage>> tokenCost) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(tokenCost, "tokenCost");
        if (history.isEmpty()) {
            throw new IllegalArgumentException("history must not be empty");
        }
        int remaining = Math.max(0, recentTokenBudget);
        int retainedStart = history.size();
        while (retainedStart > 1) {
            List<ApiMessage> candidate = history.get(retainedStart - 1);
            int cost = Math.max(1, tokenCost.applyAsInt(candidate));
            if (cost > remaining) {
                break;
            }
            remaining -= cost;
            retainedStart--;
        }
        return new ContextCompactionPlan(history.subList(0, retainedStart),
                history.subList(retainedStart, history.size()));
    }

    private static List<List<ApiMessage>> copy(List<List<ApiMessage>> source) {
        return Objects.requireNonNull(source, "source").stream().map(List::copyOf).toList();
    }
}
