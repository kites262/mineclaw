package cc.kites.mineclaw.session;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local switch that makes every ordinary public chat message wake Mineclaw. */
public final class ServerListenMode {
    private final AtomicBoolean enabled = new AtomicBoolean();

    public boolean isEnabled() {
        return enabled.get();
    }

    public boolean enable() {
        return enabled.compareAndSet(false, true);
    }

    public boolean disable() {
        return enabled.getAndSet(false);
    }

    public void reset() {
        enabled.set(false);
    }
}
