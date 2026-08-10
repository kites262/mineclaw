package cc.kites.mineclaw.turn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnActionBarPolicyTest {
    @Test
    void streamsOnlyTheFirstModelResponse() {
        assertThat(TurnActionBarPolicy.streamDeltas(0)).isTrue();
        assertThat(TurnActionBarPolicy.streamDeltas(1)).isFalse();
        assertThat(TurnActionBarPolicy.streamDeltas(2)).isFalse();
    }

    @Test
    void holdsFirstThenAtomicallyReplacesOnlyCompletedIntermediateResponses() {
        assertThat(TurnActionBarPolicy.completion(0, TurnProtocol.Decision.TOOL_CALLS))
                .isEqualTo(TurnActionBarPolicy.Completion.HOLD);
        assertThat(TurnActionBarPolicy.completion(1, TurnProtocol.Decision.TOOL_CALLS))
                .isEqualTo(TurnActionBarPolicy.Completion.REPLACE);
        assertThat(TurnActionBarPolicy.completion(2, TurnProtocol.Decision.TOOL_CALLS))
                .isEqualTo(TurnActionBarPolicy.Completion.REPLACE);
    }

    @Test
    void neverCommitsTheFinalPublicResponseToTheActionBar() {
        assertThat(TurnActionBarPolicy.completion(0, TurnProtocol.Decision.FINAL_MESSAGE))
                .isEqualTo(TurnActionBarPolicy.Completion.IGNORE);
        assertThat(TurnActionBarPolicy.completion(2, TurnProtocol.Decision.FINAL_MESSAGE))
                .isEqualTo(TurnActionBarPolicy.Completion.IGNORE);
        assertThat(TurnActionBarPolicy.completion(1, TurnProtocol.Decision.PROTOCOL_ERROR))
                .isEqualTo(TurnActionBarPolicy.Completion.IGNORE);
    }

    @Test
    void rejectsNegativeRoundIndexes() {
        assertThatThrownBy(() -> TurnActionBarPolicy.streamDeltas(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TurnActionBarPolicy.completion(
                -1, TurnProtocol.Decision.TOOL_CALLS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
