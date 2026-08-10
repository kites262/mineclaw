package cc.kites.mineclaw.turn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCoordinatorRetryTest {
    @Test
    void responseAttemptsAreCappedAtThree() {
        assertThat(TurnCoordinator.responseRetries(0)).isZero();
        assertThat(TurnCoordinator.responseRetries(1)).isEqualTo(1);
        assertThat(TurnCoordinator.responseRetries(2)).isEqualTo(2);
        assertThat(TurnCoordinator.responseRetries(5)).isEqualTo(2);
        assertThat(TurnCoordinator.MAX_RESPONSE_ATTEMPTS).isEqualTo(3);
    }
}
