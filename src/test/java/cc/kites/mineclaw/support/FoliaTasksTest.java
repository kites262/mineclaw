package cc.kites.mineclaw.support;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FoliaTasksTest {

    @Test
    void entitySupplierCompletesOnRunAndOnRetirement() {
        Harness successful = new Harness();
        CompletableFuture<String> completed = successful.tasks.entity(successful.entity, () -> "done");

        successful.entityScheduler.runTask();

        assertThat(completed.join()).isEqualTo("done");

        Harness retired = new Harness();
        AtomicBoolean invoked = new AtomicBoolean();
        CompletableFuture<String> failed = retired.tasks.entity(retired.entity, () -> {
            invoked.set(true);
            return "unexpected";
        });

        retired.entityScheduler.retireEntity();

        assertFailure(failed, "entity retired");
        retired.entityScheduler.runTask();
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectedEntityAndSchedulerFailureReturnTerminalFutures() {
        Harness rejected = new Harness();
        rejected.entityScheduler.accept = false;

        CompletableFuture<String> rejectedFuture = rejected.tasks.entity(rejected.entity, () -> "unexpected");

        assertFailure(rejectedFuture, "entity scheduler rejected task");

        Harness failed = new Harness();
        failed.globalScheduler.failure = new IllegalStateException("scheduler unavailable");

        CompletableFuture<String> failedFuture = failed.tasks.global(() -> "unexpected");

        assertFailure(failedFuture, "scheduler unavailable");
    }

    @Test
    void cancelAllCompletesQueuedSupplierFuturesAndSuppressesLateCallbacks() {
        Harness harness = new Harness();
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<Integer> entityFuture = harness.tasks.entity(harness.entity,
                () -> invocations.incrementAndGet());
        CompletableFuture<Integer> globalFuture = harness.tasks.global(invocations::incrementAndGet);

        harness.enabled.set(false);
        harness.tasks.cancelAll();

        assertFailure(entityFuture, "plugin disabled while Folia task was pending");
        assertFailure(globalFuture, "plugin disabled while Folia task was pending");
        assertThat(harness.globalScheduler.cancelCalls).isOne();
        assertThat(harness.asyncScheduler.cancelCalls).isOne();

        // Model callbacks which escaped scheduler cancellation: lifecycle guards must make them inert.
        harness.entityScheduler.runTask();
        harness.globalScheduler.runTask();
        assertThat(invocations).hasValue(0);
    }

    @Test
    void disabledOrClosedFacadeRejectsNewSupplierTasksWithoutScheduling() {
        Harness disabled = new Harness();
        disabled.enabled.set(false);

        CompletableFuture<String> disabledEntity = disabled.tasks.entity(disabled.entity, () -> "unexpected");
        CompletableFuture<String> disabledGlobal = disabled.tasks.global(() -> "unexpected");

        assertFailure(disabledEntity, "plugin disabled or Folia task lifecycle closed");
        assertFailure(disabledGlobal, "plugin disabled or Folia task lifecycle closed");
        assertThat(disabled.entityScheduler.executeCalls).isZero();
        assertThat(disabled.globalScheduler.executeCalls).isZero();

        Harness closed = new Harness();
        closed.tasks.cancelAll();

        CompletableFuture<String> closedFuture = closed.tasks.global(() -> "unexpected");

        assertFailure(closedFuture, "plugin disabled or Folia task lifecycle closed");
        assertThat(closed.globalScheduler.executeCalls).isZero();
    }

    @Test
    void supplierThrowableStillCompletesFutureExceptionally() {
        Harness harness = new Harness();
        CompletableFuture<String> future = harness.tasks.global(() -> {
            throw new AssertionError("supplier failed");
        });

        harness.globalScheduler.runTask();

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AssertionError.class)
                .hasRootCauseMessage("supplier failed");
    }

    @Test
    void cancellationNeverWaitsForAnAlreadyClaimedRegionOperation() throws Exception {
        Harness harness = new Harness();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> future = harness.tasks.global(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        Thread worker = Thread.ofVirtual().start(harness.globalScheduler::runTask);
        assertThat(started.await(1L, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(Duration.ofSeconds(1), harness.tasks::cancelAll);
        assertFailure(future, "plugin disabled while Folia task was pending");

        release.countDown();
        worker.join();
    }

    private static void assertFailure(CompletableFuture<?> future, String message) {
        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(message);
    }

    private static final class Harness {
        private final AtomicBoolean enabled = new AtomicBoolean(true);
        private final TestEntityScheduler entityScheduler = new TestEntityScheduler();
        private final TestGlobalScheduler globalScheduler = new TestGlobalScheduler();
        private final TestAsyncScheduler asyncScheduler = new TestAsyncScheduler();
        private final Server server = proxy(Server.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getGlobalRegionScheduler" -> globalScheduler;
            case "getAsyncScheduler" -> asyncScheduler;
            default -> defaultValue(method.getReturnType());
        });
        private final Plugin plugin = proxy(Plugin.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "isEnabled" -> enabled.get();
            default -> defaultValue(method.getReturnType());
        });
        private final Entity entity = proxy(Entity.class, (ignored, method, arguments) ->
                method.getName().equals("getScheduler")
                        ? entityScheduler : defaultValue(method.getReturnType()));
        private final FoliaTasks tasks = new FoliaTasks(plugin);
    }

    private static final class TestEntityScheduler implements EntityScheduler {
        private boolean accept = true;
        private int executeCalls;
        private Runnable task;
        private Runnable retired;

        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
            executeCalls++;
            if (!accept) {
                return false;
            }
            this.task = run;
            this.retired = retired;
            return true;
        }

        private void runTask() {
            if (task != null) {
                task.run();
            }
        }

        private void retireEntity() {
            if (retired != null) {
                retired.run();
            }
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                        Runnable retired, long delayTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            Runnable retired, long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestGlobalScheduler implements GlobalRegionScheduler {
        private int executeCalls;
        private int cancelCalls;
        private RuntimeException failure;
        private Runnable task;

        @Override
        public void execute(Plugin plugin, Runnable run) {
            executeCalls++;
            if (failure != null) {
                throw failure;
            }
            task = run;
        }

        private void runTask() {
            if (task != null) {
                task.run();
            }
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delayTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            cancelCalls++;
        }
    }

    private static final class TestAsyncScheduler implements AsyncScheduler {
        private int cancelCalls;

        @Override
        public ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                        long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            cancelCalls++;
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
