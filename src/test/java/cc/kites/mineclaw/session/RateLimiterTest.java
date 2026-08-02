package cc.kites.mineclaw.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    @Test
    void appliesPlayerAndGlobalCooldownsAtomically() {
        RateLimiter limiter = new RateLimiter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(limiter.acquire(first, 10_000L, 5_000L, 1_000L, false).accepted()).isTrue();
        assertThat(limiter.acquire(second, 10_500L, 5_000L, 1_000L, false).remainingMillis()).isEqualTo(500L);
        assertThat(limiter.acquire(first, 11_000L, 5_000L, 1_000L, false).remainingMillis()).isEqualTo(4_000L);
        assertThat(limiter.acquire(first, 11_000L, 5_000L, 1_000L, true).accepted()).isTrue();
    }
}
