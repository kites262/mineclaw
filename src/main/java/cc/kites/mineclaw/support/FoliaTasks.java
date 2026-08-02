package cc.kites.mineclaw.support;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicit scheduler facade used for every Bukkit state access. */
public final class FoliaTasks {
    private final Plugin plugin;
    private final Server server;
    private final Set<Tracked<?>> outstanding = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public FoliaTasks(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
    }

    public boolean entity(Entity entity, Runnable task, Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        if (!open()) {
            return false;
        }
        try {
            return entity.getScheduler().execute(
                    plugin, () -> runIfOpen(task), () -> runIfOpen(retired), 1L);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public <T> CompletableFuture<T> entity(Entity entity, Supplier<T> operation) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(operation, "operation");
        Tracked<T> tracked = tracked();
        if (tracked.future.isDone()) {
            return tracked.future;
        }
        try {
            boolean accepted = entity.getScheduler().execute(plugin, () -> complete(tracked, operation),
                    () -> tracked.fail(new IllegalStateException("entity retired")), 1L);
            if (!accepted) {
                tracked.fail(new IllegalStateException("entity scheduler rejected task"));
            }
        } catch (RuntimeException exception) {
            tracked.fail(exception);
        }
        return tracked.future;
    }

    public void global(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (open()) {
            server.getGlobalRegionScheduler().execute(plugin, () -> runIfOpen(task));
        }
    }

    public <T> CompletableFuture<T> global(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        Tracked<T> tracked = tracked();
        if (tracked.future.isDone()) {
            return tracked.future;
        }
        try {
            server.getGlobalRegionScheduler().execute(plugin, () -> complete(tracked, operation));
        } catch (RuntimeException exception) {
            tracked.fail(exception);
        }
        return tracked.future;
    }

    public ScheduledTask async(Runnable task) {
        return server.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    public ScheduledTask asyncLater(long delay, TimeUnit unit, Consumer<ScheduledTask> task) {
        return server.getAsyncScheduler().runDelayed(plugin, task, delay, unit);
    }

    public void cancelAll() {
        accepting.set(false);
        List.copyOf(outstanding).forEach(
                tracked -> tracked.cancel("plugin disabled while Folia task was pending"));
        server.getGlobalRegionScheduler().cancelTasks(plugin);
        server.getAsyncScheduler().cancelTasks(plugin);
    }

    private <T> Tracked<T> tracked() {
        Tracked<T> tracked = new Tracked<>();
        outstanding.add(tracked);
        tracked.future.whenComplete((ignored, failure) -> outstanding.remove(tracked));
        if (!open()) {
            tracked.cancel("plugin disabled or Folia task lifecycle closed");
        }
        return tracked;
    }

    private <T> void complete(Tracked<T> tracked, Supplier<T> operation) {
        if (!open() || !tracked.claim()) {
            tracked.cancel("plugin disabled or Folia task lifecycle closed");
            return;
        }
        if (!open() || !tracked.running()) {
            tracked.cancel("plugin disabled or Folia task lifecycle closed");
            return;
        }
        try {
            tracked.succeed(operation.get());
        } catch (Throwable exception) {
            tracked.fail(exception);
        }
    }

    private boolean open() {
        return accepting.get() && plugin.isEnabled();
    }

    private void runIfOpen(Runnable task) {
        if (open()) {
            task.run();
        }
    }

    private enum State {
        PENDING,
        RUNNING,
        FINISHED,
        CANCELLED
    }

    private static final class Tracked<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

        private boolean claim() {
            return state.compareAndSet(State.PENDING, State.RUNNING);
        }

        private boolean running() {
            return state.get() == State.RUNNING;
        }

        private void succeed(T value) {
            if (state.compareAndSet(State.RUNNING, State.FINISHED)) {
                future.complete(value);
            }
        }

        private void fail(Throwable failure) {
            finish(State.FINISHED, failure);
        }

        private void cancel(String message) {
            finish(State.CANCELLED, new IllegalStateException(message));
        }

        private void finish(State terminal, Throwable failure) {
            while (true) {
                State current = state.get();
                if (current == State.FINISHED || current == State.CANCELLED) {
                    return;
                }
                if (state.compareAndSet(current, terminal)) {
                    future.completeExceptionally(failure);
                    return;
                }
            }
        }
    }
}
