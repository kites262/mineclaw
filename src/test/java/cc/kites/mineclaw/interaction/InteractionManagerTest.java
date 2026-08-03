package cc.kites.mineclaw.interaction;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionManagerTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TOKEN = "10000000-0000-4000-8000-000000000001";
    private static final String OTHER_TOKEN = "20000000-0000-4000-8000-000000000002";

    @Test
    void confirmIsBoundToPlayerTokenAndCompletesExactlyOnce() {
        ManualScheduler scheduler = new ManualScheduler();
        InteractionManager manager = new InteractionManager(scheduler);
        InteractionManager.Registration registration = manager.request(confirm(ALICE, "Alice", TOKEN, "scope-a"));

        assertThat(registration.accepted()).isTrue();
        assertThat(manager.approve(BOB, TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(manager.approve(ALICE, OTHER_TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(manager.approve(ALICE, TOKEN)).isEqualTo(InteractionManager.Outcome.APPROVED);
        assertThat(manager.reject(ALICE, TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);

        InteractionManager.Result result = registration.result().join();
        assertThat(result.status()).isEqualTo(InteractionManager.Status.APPROVED);
        assertThat(result.value()).isEqualTo(Boolean.TRUE);
        assertThat(result.errorCode()).isNull();
        assertThat(manager.pendingCount()).isZero();
        assertThat(scheduler.tasks.getFirst().cancelled).isTrue();
    }

    @Test
    void selectAcceptsOnlyRegisteredOptionAndGestureCannotApproveIt() {
        ManualScheduler scheduler = new ManualScheduler();
        InteractionManager manager = new InteractionManager(scheduler);
        InteractionManager.Select select = new InteractionManager.Select("Choose", "Pick one", List.of(
                new InteractionManager.Option("a", "Plan A"),
                new InteractionManager.Option("b", "Plan B")));
        InteractionManager.Registration registration = manager.request(new InteractionManager.Request(
                ALICE, "Alice", TOKEN, "scope-a", "interaction-a", select,
                Duration.ofSeconds(30), InteractionManager.CURRENT_GENERATION));

        assertThat(manager.approveCurrentConfirm(ALICE)).isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(manager.approve(ALICE, TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(manager.select(ALICE, TOKEN, "missing")).isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(registration.result()).isNotDone();
        assertThat(manager.select(ALICE, TOKEN, "b")).isEqualTo(InteractionManager.Outcome.SELECTED);

        assertThat(registration.result().join())
                .extracting(InteractionManager.Result::status, InteractionManager.Result::value)
                .containsExactly(InteractionManager.Status.APPROVED, "b");
    }

    @Test
    void differentPlayersCanWaitInParallelButOnePlayerGetsBusy() {
        InteractionManager manager = new InteractionManager(new ManualScheduler());
        InteractionManager.Registration alice = manager.request(confirm(ALICE, "Alice", TOKEN, "scope"));
        InteractionManager.Registration duplicate = manager.request(
                confirm(ALICE, "Alice", OTHER_TOKEN, "other-scope"));
        InteractionManager.Registration bob = manager.request(
                confirm(BOB, "Bob", OTHER_TOKEN, "scope"));

        assertThat(alice.accepted()).isTrue();
        assertThat(bob.accepted()).isTrue();
        assertThat(duplicate.accepted()).isFalse();
        assertThat(duplicate.result().join().status()).isEqualTo(InteractionManager.Status.BUSY);
        assertThat(manager.pendingCount()).isEqualTo(2);
    }

    @Test
    void scopeCancellationIsExactAndCannotCancelAReplacement() {
        InteractionManager manager = new InteractionManager(new ManualScheduler());
        InteractionManager.Registration first = manager.request(confirm(ALICE, "Alice", TOKEN, "scope-a"));

        assertThat(manager.cancelScope("scope-a")).isOne();
        assertThat(first.result().join().status()).isEqualTo(InteractionManager.Status.CANCELLED);
        InteractionManager.Registration replacement = manager.request(
                confirm(ALICE, "Alice", OTHER_TOKEN, "scope-b"));

        assertThat(first.cancel(InteractionManager.Result.cancelled("late", "late cancellation"))).isFalse();
        assertThat(manager.cancelScope("scope-a")).isZero();
        assertThat(replacement.result()).isNotDone();
        assertThat(manager.approve(ALICE, OTHER_TOKEN)).isEqualTo(InteractionManager.Outcome.APPROVED);
    }

    @Test
    void playerOfflineCompletesReservedOrWaitingInteractionWithStableBusinessResult() {
        InteractionManager manager = new InteractionManager(new ManualScheduler());
        InteractionManager.Registration reserved = manager.reserve(confirm(ALICE, "Alice", TOKEN, "scope-a"));

        assertThat(manager.playerOffline(ALICE)).isTrue();
        assertThat(manager.playerOffline(ALICE)).isFalse();
        assertThat(reserved.activate()).isFalse();
        assertThat(reserved.result().join())
                .extracting(InteractionManager.Result::status, InteractionManager.Result::value,
                        InteractionManager.Result::errorCode)
                .containsExactly(InteractionManager.Status.PLAYER_OFFLINE, null, "player_offline");
    }

    @Test
    void generationInvalidationRevokesTokensAndKeepsManagerOpen() {
        InteractionManager manager = new InteractionManager(new ManualScheduler());
        long generation = manager.generation();
        InteractionManager.Registration old = manager.request(confirmAtGeneration(
                ALICE, "Alice", TOKEN, "scope-a", generation));

        manager.invalidatePending();

        assertThat(old.result().join().status()).isEqualTo(InteractionManager.Status.CANCELLED);
        assertThat(manager.generation()).isEqualTo(generation + 1L);
        assertThat(manager.isAccepting()).isTrue();
        assertThat(manager.approve(ALICE, TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);
        InteractionManager.Registration stale = manager.reserve(confirmAtGeneration(
                ALICE, "Alice", OTHER_TOKEN, "scope-b", generation));
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.result().join().errorCode()).isEqualTo("configuration_changed");
    }

    @Test
    void responseAfterDeadlineBecomesTimeoutEvenIfSchedulerCallbackIsLate() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong clock = new AtomicLong(10L);
        InteractionManager manager = new InteractionManager(scheduler, clock::get);
        InteractionManager.Registration registration = manager.request(new InteractionManager.Request(
                ALICE, "Alice", TOKEN, "scope-a", "interaction-a",
                new InteractionManager.Confirm("Confirm", "Proceed?"), Duration.ofNanos(5L),
                InteractionManager.CURRENT_GENERATION));

        clock.set(15L);

        assertThat(manager.approve(ALICE, TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(registration.result().join().status()).isEqualTo(InteractionManager.Status.TIMEOUT);
        assertThat(registration.result().join().value()).isNull();
    }

    @Test
    void selectSchemaRejectsDuplicateOrUnsafeOptionIdentifiers() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new InteractionManager.Select(
                "Choose", "Pick", List.of(
                new InteractionManager.Option("same", "A"),
                new InteractionManager.Option("same", "B")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new InteractionManager.Option("bad value", "Bad")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InteractionManager.Request confirm(
            UUID player, String name, String token, String scope) {
        return confirmAtGeneration(player, name, token, scope, InteractionManager.CURRENT_GENERATION);
    }

    private static InteractionManager.Request confirmAtGeneration(
            UUID player, String name, String token, String scope, long generation) {
        return new InteractionManager.Request(player, name, token, scope, "interaction-" + token,
                new InteractionManager.Confirm("Confirm", "Proceed?"), Duration.ofSeconds(60), generation);
    }

    private static final class ManualScheduler implements InteractionManager.TimeoutScheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public InteractionManager.Cancellable schedule(Duration delay, Runnable action) {
            Task task = new Task(action);
            tasks.add(task);
            return () -> task.cancelled = true;
        }
    }

    private static final class Task {
        private final Runnable action;
        private boolean cancelled;

        private Task(Runnable action) {
            this.action = action;
        }

        @SuppressWarnings("unused")
        private void run() {
            if (!cancelled) {
                action.run();
            }
        }
    }
}
