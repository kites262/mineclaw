package cc.kites.mineclaw.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Atomic global and per-player cooldown gate. */
public final class RateLimiter {
    private final Map<UUID, Long> playerAcceptedAt = new HashMap<>();
    private long globalAcceptedAt = Long.MIN_VALUE / 2L;

    public synchronized Result acquire(UUID player, long nowMillis, long playerCooldownMillis,
                                       long globalCooldownMillis, boolean bypass) {
        if (bypass) {
            return new Result(true, 0L);
        }
        long playerRemaining = remaining(nowMillis, playerAcceptedAt.getOrDefault(player, Long.MIN_VALUE / 2L),
                Math.max(0L, playerCooldownMillis));
        long globalRemaining = remaining(nowMillis, globalAcceptedAt, Math.max(0L, globalCooldownMillis));
        long wait = Math.max(playerRemaining, globalRemaining);
        if (wait > 0L) {
            return new Result(false, wait);
        }
        playerAcceptedAt.put(player, nowMillis);
        globalAcceptedAt = nowMillis;
        return new Result(true, 0L);
    }

    public synchronized void clear() {
        playerAcceptedAt.clear();
        globalAcceptedAt = Long.MIN_VALUE / 2L;
    }

    private static long remaining(long now, long previous, long cooldown) {
        long elapsed = now - previous;
        return elapsed >= cooldown ? 0L : cooldown - Math.max(0L, elapsed);
    }

    public record Result(boolean accepted, long remainingMillis) { }
}
