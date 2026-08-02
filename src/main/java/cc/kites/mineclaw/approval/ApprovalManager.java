package cc.kites.mineclaw.approval;

import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.tool.ToolResult;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Thread-safe one-shot approval registry. A player may have at most one pending approval. */
public final class ApprovalManager {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final TimeoutScheduler scheduler;
    private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong generation = new AtomicLong();

    public ApprovalManager(FoliaTasks tasks) {
        this(foliaScheduler(tasks));
    }

    public ApprovalManager(TimeoutScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public Registration request(
            UUID playerId,
            String token,
            Supplier<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        return request(playerId, token, DEFAULT_TIMEOUT, approvedAction, timedOut, cancelled);
    }

    public Registration request(
            UUID playerId,
            String token,
            Duration timeout,
            Supplier<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        Objects.requireNonNull(approvedAction, "approvedAction");
        return requestWithGeneration(playerId, token, timeout,
                ignored -> approvedAction.get(), timedOut, cancelled);
    }

    /**
     * Registers an approval and passes the lifecycle generation captured atomically with insertion
     * to the approved action. This closes the reload-between-token-and-registration race.
     */
    public Registration requestWithGeneration(
            UUID playerId,
            String token,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        return activate(register(playerId, token, DEFAULT_TIMEOUT, null,
                approvedAction, timedOut, cancelled, cancelled));
    }

    public Registration requestWithGeneration(
            UUID playerId,
            String token,
            Duration timeout,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        return activate(register(playerId, token, timeout, null,
                approvedAction, timedOut, cancelled, cancelled));
    }

    /** Registers only if no lifecycle/config change occurred since the private prompt began. */
    public Registration requestAtGeneration(
            UUID playerId,
            String token,
            long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        return requestAtGeneration(playerId, token, expectedGeneration, approvedAction,
                timedOut, cancelled, cancelled);
    }

    /** Registers with distinct callbacks for an explicit player rejection and lifecycle cancellation. */
    public Registration requestAtGeneration(
            UUID playerId,
            String token,
            long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable rejected,
            Runnable cancelled
    ) {
        if (expectedGeneration < 0L) {
            throw new IllegalArgumentException("expected generation must not be negative");
        }
        return activate(register(playerId, token, DEFAULT_TIMEOUT, expectedGeneration,
                approvedAction, timedOut, rejected, cancelled));
    }

    /**
     * Reserves an exact player/token request without starting its timeout. The caller must send the
     * private prompt and then invoke {@link Registration#activate()}, or abort this exact handle if
     * prompt delivery fails.
     */
    public Registration reserveAtGeneration(
            UUID playerId,
            String token,
            long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable rejected,
            Runnable cancelled
    ) {
        if (expectedGeneration < 0L) {
            throw new IllegalArgumentException("expected generation must not be negative");
        }
        return register(playerId, token, DEFAULT_TIMEOUT, expectedGeneration,
                approvedAction, timedOut, rejected, cancelled);
    }

    private Registration register(
            UUID playerId,
            String token,
            Duration timeout,
            Long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable rejected,
            Runnable cancelled
    ) {
        Objects.requireNonNull(playerId, "playerId");
        token = requireToken(token);
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(approvedAction, "approvedAction");
        Objects.requireNonNull(timedOut, "timedOut");
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(cancelled, "cancelled");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("approval timeout must be positive");
        }

        Pending candidate;
        synchronized (lifecycleLock) {
            if (!accepting.get()) {
                return Registration.rejected(disabledResult());
            }
            long requestGeneration = generation.get();
            if (expectedGeneration != null && expectedGeneration != requestGeneration) {
                return Registration.rejected(ToolResult.simple(
                        "denied", "configuration changed before command approval registration"));
            }
            candidate = new Pending(playerId, token, requestGeneration, timeout,
                    () -> approvedAction.apply(requestGeneration), timedOut, rejected, cancelled);
            Pending existing = pending.putIfAbsent(playerId, candidate);
            if (existing != null) {
                return Registration.rejected(ToolResult.simple("denied", "player already has a pending approval"));
            }
        }

        return new Registration(true, candidate.continuation, this, candidate);
    }

    private Registration activate(Registration registration) {
        if (!registration.accepted() || registration.activate()) {
            return registration;
        }
        return new Registration(false, registration.continuation(), this, registration.pending);
    }

    private boolean activate(Pending value) {
        synchronized (lifecycleLock) {
            if (!accepting.get() || pending.get(value.playerId) != value
                    || value.generation != generation.get()
                    || !value.transition(State.RESERVED, State.WAITING)) {
                return false;
            }
        }
        try {
            Cancellable timeoutTask = scheduler.schedule(value.timeoutDelay, () -> timeout(value));
            value.installTimeout(Objects.requireNonNull(timeoutTask, "timeoutTask"));
            return true;
        } catch (RuntimeException exception) {
            cancelExact(value, State.WAITING,
                    ToolResult.simple("terminal_error", safeMessage(exception)));
            return false;
        }
    }

    private boolean abort(Pending value, ToolResult result) {
        return cancelExact(value, State.RESERVED, result);
    }

    private boolean cancelExact(Pending value, State expected, ToolResult result) {
        Objects.requireNonNull(result, "result");
        synchronized (lifecycleLock) {
            if (pending.get(value.playerId) != value || !value.transition(expected, State.CANCELLED)) {
                return false;
            }
            pending.remove(value.playerId, value);
        }
        value.cancelTimeout();
        value.runSafely(value.cancelled);
        value.continuation.complete(result);
        return true;
    }

    /** Atomically consumes a player's approval. Concurrent or repeated calls cannot run it twice. */
    public ApprovalOutcome approve(UUID playerId, String token) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");
        return approveMatching(playerId, value -> value.token.equals(token));
    }

    /**
     * Accepts the currently displayed request for a trusted player gesture. Callers must establish
     * the acting player's identity and approval permission before invoking this tokenless path.
     */
    public ApprovalOutcome approveCurrent(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return approveMatching(playerId, ignored -> true);
    }

    private ApprovalOutcome approveMatching(UUID playerId, Predicate<Pending> matches) {
        Pending value;
        synchronized (lifecycleLock) {
            value = pending.get(playerId);
            if (value == null || !matches.test(value) || value.generation != generation.get()
                    || !value.transition(State.WAITING, State.APPROVED)) {
                return ApprovalOutcome.NONE;
            }
            pending.remove(playerId, value);
        }
        value.cancelTimeout();
        value.runApproved();
        return ApprovalOutcome.STARTED;
    }

    /** Atomically rejects and consumes a player's request without running the approved action. */
    public ApprovalOutcome reject(UUID playerId, String token) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");
        Pending value;
        synchronized (lifecycleLock) {
            value = pending.get(playerId);
            if (value == null || !value.token.equals(token) || value.generation != generation.get()
                    || !value.transition(State.WAITING, State.REJECTED)) {
                return ApprovalOutcome.NONE;
            }
            pending.remove(playerId, value);
        }
        value.cancelTimeout();
        value.runSafely(value.rejected);
        value.continuation.complete(ToolResult.simple(
                "denied", "target player explicitly rejected command approval"));
        return ApprovalOutcome.REJECTED;
    }

    /** Withdraws one pending request, completing its continuation with the supplied terminal result. */
    public boolean cancel(UUID playerId, ToolResult result) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(result, "result");
        Pending value;
        synchronized (lifecycleLock) {
            value = pending.get(playerId);
            if (value == null || !value.transitionPending(State.CANCELLED)) {
                return false;
            }
            pending.remove(playerId, value);
        }
        value.cancelTimeout();
        value.runSafely(value.cancelled);
        value.continuation.complete(result);
        return true;
    }

    /** Permanently closes this manager and terminally completes every pending continuation. */
    public void cancelAll() {
        List<Pending> invalidated;
        synchronized (lifecycleLock) {
            accepting.set(false);
            invalidated = detachPending();
        }
        completeInvalidated(invalidated, ToolResult.simple("terminal_error",
                "plugin disabled while command approval was pending"));
    }

    /** Invalidates requests created under an older config while keeping the manager open. */
    public void invalidatePending() {
        List<Pending> invalidated;
        synchronized (lifecycleLock) {
            invalidated = detachPending();
        }
        completeInvalidated(invalidated,
                ToolResult.simple("denied", "configuration changed during command approval"));
    }

    private List<Pending> detachPending() {
        generation.incrementAndGet();
        ArrayList<Pending> invalidated = new ArrayList<>();
        pending.forEach((playerId, value) -> {
            if (value.transitionPending(State.CANCELLED)) {
                pending.remove(playerId, value);
                invalidated.add(value);
            }
        });
        return invalidated;
    }

    private static void completeInvalidated(List<Pending> invalidated, ToolResult result) {
        invalidated.forEach(value -> {
            value.cancelTimeout();
            value.runSafely(value.cancelled);
            value.continuation.complete(result);
        });
    }

    public long generation() {
        return generation.get();
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    public boolean hasPending(UUID playerId) {
        return pending.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public int pendingCount() {
        return pending.size();
    }

    private void timeout(Pending value) {
        synchronized (lifecycleLock) {
            if (pending.get(value.playerId) != value
                    || value.generation != generation.get()
                    || !value.transition(State.WAITING, State.TIMED_OUT)) {
                return;
            }
            pending.remove(value.playerId, value);
        }
        value.runSafely(value.timedOut);
        value.continuation.complete(ToolResult.simple("timeout", "command approval timed out"));
    }

    private static TimeoutScheduler foliaScheduler(FoliaTasks tasks) {
        Objects.requireNonNull(tasks, "tasks");
        return (delay, action) -> {
            ScheduledTask task = tasks.asyncLater(delay.toMillis(), TimeUnit.MILLISECONDS, ignored -> action.run());
            return task::cancel;
        };
    }

    private static ToolResult disabledResult() {
        return ToolResult.simple("terminal_error", "plugin disabled while command approval was pending");
    }

    private static String requireToken(String token) {
        Objects.requireNonNull(token, "token");
        try {
            if (!UUID.fromString(token).toString().equals(token)) {
                throw new IllegalArgumentException("approval token must be canonical UUID text");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("approval token must be canonical UUID text", exception);
        }
        return token;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public enum ApprovalOutcome {
        STARTED,
        REJECTED,
        NONE
    }

    public static final class Registration {
        private final boolean accepted;
        private final CompletableFuture<ToolResult> continuation;
        private final ApprovalManager owner;
        private final Pending pending;

        private Registration(boolean accepted, CompletableFuture<ToolResult> continuation,
                             ApprovalManager owner, Pending pending) {
            this.accepted = accepted;
            this.continuation = Objects.requireNonNull(continuation, "continuation");
            this.owner = owner;
            this.pending = pending;
        }

        public boolean accepted() {
            return accepted;
        }

        public CompletableFuture<ToolResult> continuation() {
            return continuation;
        }

        /** Arms the timeout for this exact reservation. It may succeed at most once. */
        public boolean activate() {
            return accepted && owner.activate(pending);
        }

        /** Aborts only this exact, not-yet-activated reservation. */
        public boolean abort(ToolResult result) {
            Objects.requireNonNull(result, "result");
            return accepted && owner.abort(pending, result);
        }

        private static Registration rejected(ToolResult result) {
            return new Registration(false, CompletableFuture.completedFuture(result), null, null);
        }
    }

    @FunctionalInterface
    public interface TimeoutScheduler {
        Cancellable schedule(Duration delay, Runnable action);
    }

    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    private enum State {
        RESERVED,
        WAITING,
        APPROVED,
        REJECTED,
        TIMED_OUT,
        CANCELLED
    }

    private static final class Pending {
        private static final Cancellable NOOP = () -> { };

        private final UUID playerId;
        private final String token;
        private final long generation;
        private final Duration timeoutDelay;
        private final Supplier<? extends CompletionStage<ToolResult>> approvedAction;
        private final Runnable timedOut;
        private final Runnable rejected;
        private final Runnable cancelled;
        private final CompletableFuture<ToolResult> continuation = new CompletableFuture<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.RESERVED);
        private final AtomicReference<Cancellable> timeout = new AtomicReference<>(NOOP);

        private Pending(UUID playerId, String token, long generation, Duration timeoutDelay,
                        Supplier<? extends CompletionStage<ToolResult>> approvedAction,
                        Runnable timedOut, Runnable rejected, Runnable cancelled) {
            this.playerId = playerId;
            this.token = token;
            this.generation = generation;
            this.timeoutDelay = timeoutDelay;
            this.approvedAction = approvedAction;
            this.timedOut = timedOut;
            this.rejected = rejected;
            this.cancelled = cancelled;
        }

        private boolean transition(State expected, State replacement) {
            return state.compareAndSet(expected, replacement);
        }

        private boolean transitionPending(State replacement) {
            while (true) {
                State current = state.get();
                if (current != State.RESERVED && current != State.WAITING) {
                    return false;
                }
                if (state.compareAndSet(current, replacement)) {
                    return true;
                }
            }
        }

        private void installTimeout(Cancellable task) {
            timeout.set(task);
            if (state.get() != State.WAITING) {
                task.cancel();
            }
        }

        private void cancelTimeout() {
            timeout.getAndSet(NOOP).cancel();
        }

        private void runApproved() {
            CompletionStage<ToolResult> result;
            try {
                result = Objects.requireNonNull(approvedAction.get(), "approved action result");
            } catch (RuntimeException exception) {
                continuation.complete(ToolResult.simple("terminal_error", safeMessage(exception)));
                return;
            }
            result.whenComplete((toolResult, error) -> {
                if (error != null) {
                    continuation.complete(ToolResult.simple("terminal_error", safeMessage(error)));
                } else if (toolResult == null) {
                    continuation.complete(ToolResult.simple("terminal_error", "approved action returned no result"));
                } else {
                    continuation.complete(toolResult);
                }
            });
        }

        private void cancel(ToolResult result) {
            if (transitionPending(State.CANCELLED)) {
                cancelTimeout();
                runSafely(cancelled);
                continuation.complete(result);
            }
        }

        private void runSafely(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // State and continuation completion must not be held hostage by a notification callback.
            }
        }
    }
}
