package cc.kites.mineclaw.turn;

/** Selects Action Bar delivery without exposing final public-answer deltas. */
final class TurnActionBarPolicy {
    private TurnActionBarPolicy() {
    }

    static boolean streamDeltas(int toolRound) {
        if (toolRound < 0) {
            throw new IllegalArgumentException("toolRound must be non-negative");
        }
        return toolRound == 0;
    }

    static Completion completion(int toolRound, TurnProtocol.Decision decision) {
        if (toolRound < 0) {
            throw new IllegalArgumentException("toolRound must be non-negative");
        }
        if (decision != TurnProtocol.Decision.TOOL_CALLS) {
            return Completion.IGNORE;
        }
        return toolRound == 0 ? Completion.HOLD : Completion.REPLACE;
    }

    enum Completion {
        HOLD,
        REPLACE,
        IGNORE
    }
}
