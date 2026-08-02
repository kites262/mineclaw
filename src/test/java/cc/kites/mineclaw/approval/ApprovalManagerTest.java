package cc.kites.mineclaw.approval;

import cc.kites.mineclaw.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalManagerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "10000000-0000-4000-8000-000000000001";
    private static final String OTHER_TOKEN = "20000000-0000-4000-8000-000000000002";

    @Test
    void approveConsumesRequestExactlyOnceAndCompletesContinuation() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicInteger executions = new AtomicInteger();
        ApprovalManager.Registration registration = manager.request(PLAYER, TOKEN,
                () -> CompletableFuture.completedFuture(ok(executions.incrementAndGet())),
                () -> { }, () -> { });

        assertThat(registration.accepted()).isTrue();
        assertThat(manager.approve(PLAYER, OTHER_TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        scheduler.runAll();

        assertThat(registration.continuation().join().status()).isEqualTo("ok");
        assertThat(executions).hasValue(1);
        assertThat(manager.pendingCount()).isZero();
        assertThat(scheduler.tasks.getFirst().delay).isEqualTo(Duration.ofSeconds(60));
        assertThat(scheduler.tasks.getFirst().cancelled).isTrue();
    }

    @Test
    void trustedPlayerGestureConsumesTheCurrentWaitingRequestExactlyOnce() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicInteger executions = new AtomicInteger();
        ApprovalManager.Registration registration = manager.request(PLAYER, TOKEN,
                () -> CompletableFuture.completedFuture(ok(executions.incrementAndGet())),
                () -> { }, () -> { });

        assertThat(manager.approveCurrent(UUID.randomUUID())).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.approveCurrent(PLAYER)).isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(manager.approveCurrent(PLAYER)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);

        assertThat(registration.continuation().join().status()).isEqualTo("ok");
        assertThat(executions).hasValue(1);
        assertThat(manager.pendingCount()).isZero();
    }

    @Test
    void gestureAndTokenDecisionRaceStillDispatchesExactlyOnce() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicInteger executions = new AtomicInteger();
        ApprovalManager.Registration registration = manager.request(PLAYER, TOKEN,
                () -> CompletableFuture.completedFuture(ok(executions.incrementAndGet())),
                () -> { }, () -> { });
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<ApprovalManager.ApprovalOutcome> gesture = CompletableFuture.supplyAsync(() -> {
            await(start);
            return manager.approveCurrent(PLAYER);
        });
        CompletableFuture<ApprovalManager.ApprovalOutcome> button = CompletableFuture.supplyAsync(() -> {
            await(start);
            return manager.approve(PLAYER, TOKEN);
        });
        start.countDown();

        assertThat(List.of(gesture.join(), button.join())).containsExactlyInAnyOrder(
                ApprovalManager.ApprovalOutcome.STARTED, ApprovalManager.ApprovalOutcome.NONE);
        assertThat(registration.continuation().join().status()).isEqualTo("ok");
        assertThat(executions).hasValue(1);
    }

    @Test
    void timeoutWinsRaceAndNeverRunsApprovedAction() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        ApprovalManager.Registration registration = manager.request(
                PLAYER, TOKEN, Duration.ofSeconds(60),
                () -> CompletableFuture.completedFuture(ok(executions.incrementAndGet())),
                timeouts::incrementAndGet, () -> { });

        scheduler.runAll();

        assertThat(registration.continuation().join().status()).isEqualTo("timeout");
        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(executions).hasValue(0);
        assertThat(timeouts).hasValue(1);
    }

    @Test
    void rejectConsumesRequestExactlyOnceAndReturnsAnExplicitDenial() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        long generation = manager.generation();
        ApprovalManager.Registration registration = manager.requestAtGeneration(
                PLAYER, TOKEN, generation,
                ignored -> CompletableFuture.completedFuture(ok(executions.incrementAndGet())),
                () -> { }, rejections::incrementAndGet, cancellations::incrementAndGet);

        assertThat(registration.accepted()).isTrue();
        assertThat(manager.reject(PLAYER, OTHER_TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(registration.continuation()).isNotDone();
        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.REJECTED);
        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        scheduler.runAll();

        ToolResult result = registration.continuation().join();
        assertThat(result.status()).isEqualTo("denied");
        assertThat(result.output().get("message").getAsString()).contains("explicitly rejected");
        assertThat(executions).hasValue(0);
        assertThat(rejections).hasValue(1);
        assertThat(cancellations).hasValue(0);
        assertThat(manager.generation()).isEqualTo(generation);
        assertThat(manager.pendingCount()).isZero();
        assertThat(scheduler.tasks.getFirst().cancelled).isTrue();
    }

    @Test
    void duplicateIsRejectedAndDisableCancelsAllPendingRequests() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicInteger cancellations = new AtomicInteger();
        ApprovalManager.Registration first = manager.request(PLAYER, TOKEN,
                () -> CompletableFuture.completedFuture(ok(1)), () -> { }, cancellations::incrementAndGet);
        ApprovalManager.Registration duplicate = manager.request(PLAYER, OTHER_TOKEN,
                () -> CompletableFuture.completedFuture(ok(2)), () -> { }, () -> { });

        manager.cancelAll();

        assertThat(first.accepted()).isTrue();
        assertThat(first.continuation().join().status()).isEqualTo("terminal_error");
        assertThat(duplicate.accepted()).isFalse();
        assertThat(duplicate.continuation().join().status()).isEqualTo("denied");
        assertThat(cancellations).hasValue(1);
        assertThat(manager.request(UUID.randomUUID(), UUID.randomUUID().toString(),
                () -> CompletableFuture.completedFuture(ok(3)),
                () -> { }, () -> { }).accepted()).isFalse();
    }

    @Test
    void configInvalidationCancelsOldRequestsButKeepsManagerOpenWithNewGeneration() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        AtomicLong approvedGeneration = new AtomicLong(-1L);
        ApprovalManager.Registration old = manager.requestWithGeneration(PLAYER, TOKEN,
                generation -> {
                    approvedGeneration.set(generation);
                    return CompletableFuture.completedFuture(ok(1));
                }, () -> { }, () -> { });
        long oldGeneration = manager.generation();

        manager.invalidatePending();

        assertThat(old.continuation().join().status()).isEqualTo("denied");
        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(approvedGeneration).hasValue(-1L);
        assertThat(manager.generation()).isEqualTo(oldGeneration + 1L);
        ApprovalManager.Registration fresh = manager.requestWithGeneration(PLAYER, OTHER_TOKEN,
                generation -> {
                    approvedGeneration.set(generation);
                    return CompletableFuture.completedFuture(ok(2));
                }, () -> { }, () -> { });
        assertThat(fresh.accepted()).isTrue();

        assertThat(manager.approve(PLAYER, OTHER_TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(fresh.continuation().join().status()).isEqualTo("ok");
        assertThat(approvedGeneration).hasValue(manager.generation());
    }

    @Test
    void tokenFromAnOldPromptCannotConsumeAReplacementRequest() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        ApprovalManager.Registration old = manager.request(PLAYER, TOKEN,
                () -> CompletableFuture.completedFuture(ok(1)), () -> { }, () -> { });

        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.REJECTED);
        assertThat(old.continuation().join().status()).isEqualTo("denied");
        ApprovalManager.Registration replacement = manager.request(PLAYER, OTHER_TOKEN,
                () -> CompletableFuture.completedFuture(ok(2)), () -> { }, () -> { });

        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(replacement.continuation()).isNotDone();
        assertThat(manager.hasPending(PLAYER)).isTrue();
        assertThat(manager.approve(PLAYER, OTHER_TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(replacement.continuation().join().status()).isEqualTo("ok");
    }

    @Test
    void reservationArmsOnlyAfterPromptAndAbortHandleCannotTouchReplacement() {
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager manager = new ApprovalManager(scheduler);
        ApprovalManager.Registration reservation = manager.reserveAtGeneration(
                PLAYER, TOKEN, manager.generation(),
                ignored -> CompletableFuture.completedFuture(ok(1)),
                () -> { }, () -> { }, () -> { });

        assertThat(reservation.accepted()).isTrue();
        assertThat(scheduler.tasks).isEmpty();
        assertThat(manager.approve(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.approveCurrent(PLAYER)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(manager.reject(PLAYER, TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(reservation.abort(ToolResult.simple("denied", "prompt delivery failed"))).isTrue();

        ApprovalManager.Registration replacement = manager.reserveAtGeneration(
                PLAYER, OTHER_TOKEN, manager.generation(),
                ignored -> CompletableFuture.completedFuture(ok(2)),
                () -> { }, () -> { }, () -> { });
        assertThat(reservation.abort(ToolResult.simple("denied", "late failure"))).isFalse();
        assertThat(manager.hasPending(PLAYER)).isTrue();
        assertThat(replacement.activate()).isTrue();
        assertThat(scheduler.tasks).hasSize(1);
        assertThat(manager.approve(PLAYER, OTHER_TOKEN)).isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(replacement.continuation().join().status()).isEqualTo("ok");
    }

    private static ToolResult ok(int value) {
        return ToolResult.simple("ok", "execution " + value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while starting approval race", exception);
        }
    }

    private static final class ManualScheduler implements ApprovalManager.TimeoutScheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public ApprovalManager.Cancellable schedule(Duration delay, Runnable action) {
            assertThat(delay).isPositive();
            Task task = new Task(delay, action);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        private void runAll() {
            List.copyOf(tasks).forEach(Task::run);
        }
    }

    private static final class Task {
        private final Duration delay;
        private final Runnable action;
        private boolean cancelled;

        private Task(Duration delay, Runnable action) {
            this.delay = delay;
            this.action = action;
        }

        private void run() {
            if (!cancelled) {
                action.run();
            }
        }
    }
}
