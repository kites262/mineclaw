package cc.kites.mineclaw.interaction;

import cc.kites.mineclaw.support.FoliaTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Thread-safe, one-shot player interaction registry shared by command approvals and scripted
 * workflows. A player may have at most one pending Mineclaw interaction across all scopes.
 */
public final class InteractionManager {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final long CURRENT_GENERATION = -1L;
    private static final Pattern TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OPTION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final TimeoutScheduler scheduler;
    private final LongSupplier nanoTime;
    private final Object lifecycleLock = new Object();
    private final Map<UUID, Pending> pendingByPlayer = new HashMap<>();
    private final Map<String, Set<Pending>> pendingByScope = new HashMap<>();
    private boolean accepting = true;
    private long generation;

    public InteractionManager(FoliaTasks tasks) {
        this(foliaScheduler(tasks));
    }

    public InteractionManager(TimeoutScheduler scheduler) {
        this(scheduler, System::nanoTime);
    }

    InteractionManager(TimeoutScheduler scheduler, LongSupplier nanoTime) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Reserves an interaction without starting its timeout. Activate only after prompt delivery. */
    public Registration reserve(Request request) {
        Objects.requireNonNull(request, "request");
        Pending candidate;
        long requestGeneration;
        synchronized (lifecycleLock) {
            if (!accepting) {
                return Registration.rejected(Result.cancelled(
                        "plugin_disabled", "Mineclaw interaction service is closed"));
            }
            requestGeneration = generation;
            if (request.expectedGeneration() != CURRENT_GENERATION
                    && request.expectedGeneration() != requestGeneration) {
                return Registration.rejected(Result.cancelled(
                        "configuration_changed", "interaction generation changed before registration"));
            }
            if (pendingByPlayer.containsKey(request.playerId())) {
                return Registration.rejected(Result.busy());
            }
            candidate = new Pending(request, requestGeneration);
            pendingByPlayer.put(request.playerId(), candidate);
            pendingByScope.computeIfAbsent(request.scopeId(), ignored -> new HashSet<>()).add(candidate);
        }
        return new Registration(true, candidate.result, requestGeneration, this, candidate);
    }

    /** Convenience for interactions whose prompt is already visible. */
    public Registration request(Request request) {
        Registration registration = reserve(request);
        if (registration.accepted() && !registration.activate()) {
            return new Registration(false, registration.result(), registration.generation(), this,
                    registration.pending);
        }
        return registration;
    }

    /** Accepts a token-bound confirm interaction. Select interactions never match this path. */
    public Outcome approve(UUID playerId, String token) {
        return completeResponse(playerId, token, pending -> pending.request.interaction() instanceof Confirm,
                Result.approved(Boolean.TRUE), Outcome.APPROVED);
    }

    /** Rejects either a confirm or select interaction. */
    public Outcome reject(UUID playerId, String token) {
        return completeResponse(playerId, token, ignored -> true,
                Result.rejected(), Outcome.REJECTED);
    }

    /** Selects one of the exact option identifiers registered with this token. */
    public Outcome select(UUID playerId, String token, String optionId) {
        Objects.requireNonNull(optionId, "optionId");
        return completeResponse(playerId, token, pending -> pending.request.interaction() instanceof Select select
                        && select.options().stream().anyMatch(option -> option.id().equals(optionId)),
                Result.approved(optionId), Outcome.SELECTED);
    }

    /** Tokenless trusted gesture path. It deliberately matches confirm interactions only. */
    public Outcome approveCurrentConfirm(UUID playerId) {
        return completeResponse(playerId, null,
                pending -> pending.request.interaction() instanceof Confirm,
                Result.approved(Boolean.TRUE), Outcome.APPROVED);
    }

    /** Completes a target player's exact pending interaction when that UUID disconnects. */
    public boolean playerOffline(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Pending pending;
        synchronized (lifecycleLock) {
            pending = pendingByPlayer.get(playerId);
            if (pending == null || !pending.transitionPending(State.COMPLETED)) {
                return false;
            }
            detachLocked(pending);
        }
        finish(pending, Result.playerOffline());
        return true;
    }

    /** Cancels the player's current request for compatibility callers without a registration handle. */
    public boolean cancelCurrent(UUID playerId, Result result) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(result, "result");
        Pending pending;
        synchronized (lifecycleLock) {
            pending = pendingByPlayer.get(playerId);
            if (pending == null || !pending.transitionPending(State.CANCELLED)) {
                return false;
            }
            detachLocked(pending);
        }
        finish(pending, result);
        return true;
    }

    /** Cancels every interaction owned by one invocation/scope without touching replacements. */
    public int cancelScope(String scopeId) {
        return cancelScope(scopeId, Result.cancelled(
                "scope_cancelled", "interaction scope was cancelled"));
    }

    public int cancelScope(String scopeId, Result result) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(result, "result");
        List<Pending> cancelled = new ArrayList<>();
        synchronized (lifecycleLock) {
            Set<Pending> scoped = pendingByScope.get(scopeId);
            if (scoped != null) {
                for (Pending pending : List.copyOf(scoped)) {
                    if (pending.transitionPending(State.CANCELLED)) {
                        detachLocked(pending);
                        cancelled.add(pending);
                    }
                }
            }
        }
        cancelled.forEach(pending -> finish(pending, result));
        return cancelled.size();
    }

    /** Invalidates all current tokens while keeping the manager available for the next generation. */
    public void invalidatePending() {
        invalidatePending(Result.cancelled(
                "configuration_changed", "configuration changed during interaction"));
    }

    public void invalidatePending(Result result) {
        Objects.requireNonNull(result, "result");
        completeDetached(detachAll(false), result);
    }

    /** Permanently closes the manager and invalidates all current and future interaction tokens. */
    public void close() {
        completeDetached(detachAll(true), Result.cancelled(
                "plugin_disabled", "plugin disabled while interaction was pending"));
    }

    public long generation() {
        synchronized (lifecycleLock) {
            return generation;
        }
    }

    public boolean isAccepting() {
        synchronized (lifecycleLock) {
            return accepting;
        }
    }

    public boolean hasPending(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lifecycleLock) {
            return pendingByPlayer.containsKey(playerId);
        }
    }

    public int pendingCount() {
        synchronized (lifecycleLock) {
            return pendingByPlayer.size();
        }
    }

    private Outcome completeResponse(UUID playerId, String token,
                                     java.util.function.Predicate<Pending> responseMatches,
                                     Result result, Outcome outcome) {
        Objects.requireNonNull(playerId, "playerId");
        if (token != null) {
            Objects.requireNonNull(token, "token");
        }
        Pending pending;
        Result completion = result;
        Outcome returned = outcome;
        synchronized (lifecycleLock) {
            pending = pendingByPlayer.get(playerId);
            if (pending == null || token != null && !pending.request.token().equals(token)
                    || pending.generation != generation || pending.state.get() != State.WAITING
                    || !responseMatches.test(pending)) {
                return Outcome.NONE;
            }
            if (expired(pending)) {
                if (!pending.transition(State.WAITING, State.COMPLETED)) {
                    return Outcome.NONE;
                }
                completion = Result.timeout();
                returned = Outcome.NONE;
            } else if (!pending.transition(State.WAITING, State.COMPLETED)) {
                return Outcome.NONE;
            }
            detachLocked(pending);
        }
        finish(pending, completion);
        return returned;
    }

    private boolean activate(Pending pending) {
        synchronized (lifecycleLock) {
            if (!accepting || pendingByPlayer.get(pending.request.playerId()) != pending
                    || pending.generation != generation
                    || !pending.transition(State.RESERVED, State.WAITING)) {
                return false;
            }
            pending.deadlineNanos = deadline(nanoTime.getAsLong(), pending.request.timeout());
        }
        try {
            Cancellable timeoutTask = scheduler.schedule(pending.request.timeout(), () -> timeout(pending));
            pending.installTimeout(Objects.requireNonNull(timeoutTask, "timeoutTask"));
            return true;
        } catch (RuntimeException exception) {
            failExact(pending, exception);
            return false;
        }
    }

    private boolean abort(Pending pending, Result result) {
        return cancelExact(pending, State.RESERVED, result);
    }

    private boolean cancel(Pending pending, Result result) {
        Objects.requireNonNull(result, "result");
        synchronized (lifecycleLock) {
            if (pendingByPlayer.get(pending.request.playerId()) != pending
                    || !pending.transitionPending(State.CANCELLED)) {
                return false;
            }
            detachLocked(pending);
        }
        finish(pending, result);
        return true;
    }

    private boolean cancelExact(Pending pending, State expected, Result result) {
        Objects.requireNonNull(result, "result");
        synchronized (lifecycleLock) {
            if (pendingByPlayer.get(pending.request.playerId()) != pending
                    || !pending.transition(expected, State.CANCELLED)) {
                return false;
            }
            detachLocked(pending);
        }
        finish(pending, result);
        return true;
    }

    private void failExact(Pending pending, RuntimeException failure) {
        synchronized (lifecycleLock) {
            if (pendingByPlayer.get(pending.request.playerId()) != pending
                    || !pending.transition(State.WAITING, State.CANCELLED)) {
                return;
            }
            detachLocked(pending);
        }
        pending.cancelTimeout();
        pending.result.completeExceptionally(failure);
    }

    private void timeout(Pending pending) {
        synchronized (lifecycleLock) {
            if (pendingByPlayer.get(pending.request.playerId()) != pending
                    || pending.generation != generation
                    || !pending.transition(State.WAITING, State.COMPLETED)) {
                return;
            }
            detachLocked(pending);
        }
        finish(pending, Result.timeout());
    }

    private boolean expired(Pending pending) {
        long deadline = pending.deadlineNanos;
        return deadline != Long.MAX_VALUE && nanoTime.getAsLong() - deadline >= 0L;
    }

    private List<Pending> detachAll(boolean close) {
        ArrayList<Pending> detached = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (close) {
                accepting = false;
            }
            generation++;
            for (Pending pending : List.copyOf(pendingByPlayer.values())) {
                if (pending.transitionPending(State.CANCELLED)) {
                    detachLocked(pending);
                    detached.add(pending);
                }
            }
        }
        return detached;
    }

    private void detachLocked(Pending pending) {
        pendingByPlayer.remove(pending.request.playerId(), pending);
        Set<Pending> scoped = pendingByScope.get(pending.request.scopeId());
        if (scoped != null) {
            scoped.remove(pending);
            if (scoped.isEmpty()) {
                pendingByScope.remove(pending.request.scopeId());
            }
        }
    }

    private static void completeDetached(List<Pending> detached, Result result) {
        detached.forEach(pending -> finish(pending, result));
    }

    private static void finish(Pending pending, Result result) {
        pending.cancelTimeout();
        pending.result.complete(result);
    }

    private static long deadline(long start, Duration delay) {
        long nanos;
        try {
            nanos = delay.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
        long result = start + nanos;
        return ((start ^ result) & (nanos ^ result)) < 0L ? Long.MAX_VALUE : result;
    }

    private static TimeoutScheduler foliaScheduler(FoliaTasks tasks) {
        Objects.requireNonNull(tasks, "tasks");
        return (delay, action) -> {
            ScheduledTask task = tasks.asyncLater(delay.toMillis(), TimeUnit.MILLISECONDS,
                    ignored -> action.run());
            return task::cancel;
        };
    }

    private static String requireText(String value, String field, int minimum, int maximum) {
        value = Objects.requireNonNull(value, field);
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(field + " must contain " + minimum + "-" + maximum
                    + " Unicode code points");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }

    public enum Outcome {
        APPROVED,
        REJECTED,
        SELECTED,
        NONE
    }

    public enum Status {
        APPROVED("approved"),
        REJECTED("rejected"),
        TIMEOUT("timeout"),
        PLAYER_OFFLINE("player_offline"),
        BUSY("busy"),
        DENIED("denied"),
        CANCELLED("cancelled"),
        INVALID("invalid");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record Result(Status status, Object value, String errorCode, String message) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            if (status == Status.APPROVED) {
                if (!(value instanceof Boolean || value instanceof String)) {
                    throw new IllegalArgumentException("approved interaction value must be boolean or string");
                }
                if (errorCode != null) {
                    throw new IllegalArgumentException("approved interaction must not have an error code");
                }
                message = message == null ? "" : message;
            } else {
                if (value != null) {
                    throw new IllegalArgumentException("non-approved interaction value must be null");
                }
                errorCode = requireText(errorCode, "errorCode", 1, 64);
                message = requireText(message, "message", 1, 512);
            }
        }

        public static Result approved(Object value) {
            return new Result(Status.APPROVED, value, null, "");
        }

        public static Result rejected() {
            return new Result(Status.REJECTED, null, "player_rejected", "player rejected the interaction");
        }

        public static Result timeout() {
            return new Result(Status.TIMEOUT, null, "approval_timeout", "interaction timed out");
        }

        public static Result playerOffline() {
            return new Result(Status.PLAYER_OFFLINE, null, "player_offline", "target player went offline");
        }

        public static Result busy() {
            return new Result(Status.BUSY, null, "interaction_busy",
                    "target player already has a pending interaction");
        }

        public static Result cancelled(String errorCode, String message) {
            return new Result(Status.CANCELLED, null, errorCode, message);
        }

        public static Result denied(String errorCode, String message) {
            return new Result(Status.DENIED, null, errorCode, message);
        }

        public static Result invalid(String errorCode, String message) {
            return new Result(Status.INVALID, null, errorCode, message);
        }
    }

    public sealed interface Interaction permits Confirm, Select {
        String title();

        String message();
    }

    public record Confirm(String title, String message) implements Interaction {
        public Confirm {
            title = requireText(title, "title", 1, 64);
            message = requireText(message, "message", 1, 512);
        }
    }

    public record Select(String title, String message, List<Option> options) implements Interaction {
        public Select {
            title = requireText(title, "title", 1, 64);
            message = requireText(message, "message", 1, 512);
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.size() < 2 || options.size() > 8) {
                throw new IllegalArgumentException("select options must contain 2-8 entries");
            }
            Set<String> identifiers = new HashSet<>();
            for (Option option : options) {
                if (!identifiers.add(option.id())) {
                    throw new IllegalArgumentException("select option ids must be unique");
                }
            }
        }
    }

    public record Option(String id, String label) {
        public Option {
            id = Objects.requireNonNull(id, "id");
            if (!OPTION_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("option id must match " + OPTION_ID.pattern());
            }
            label = requireText(label, "label", 1, 128);
        }
    }

    public record Request(
            UUID playerId,
            String playerName,
            String token,
            String scopeId,
            String interactionId,
            Interaction interaction,
            Duration timeout,
            long expectedGeneration
    ) {
        public Request {
            Objects.requireNonNull(playerId, "playerId");
            playerName = requireText(playerName, "playerName", 1, 64);
            if (playerName.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("playerName must not contain whitespace");
            }
            token = Objects.requireNonNull(token, "token");
            if (!TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("token must be canonical lowercase UUID text");
            }
            try {
                if (!UUID.fromString(token).toString().equals(token)) {
                    throw new IllegalArgumentException("token must be canonical lowercase UUID text");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("token must be canonical lowercase UUID text", exception);
            }
            scopeId = requireText(scopeId, "scopeId", 1, 128);
            interactionId = requireText(interactionId, "interactionId", 1, 128);
            Objects.requireNonNull(interaction, "interaction");
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("interaction timeout must be positive");
            }
            if (expectedGeneration < CURRENT_GENERATION) {
                throw new IllegalArgumentException("expectedGeneration is invalid");
            }
        }

        public static Request current(UUID playerId, String playerName, String token, String scopeId,
                                      String interactionId, Interaction interaction, Duration timeout) {
            return new Request(playerId, playerName, token, scopeId, interactionId, interaction, timeout,
                    CURRENT_GENERATION);
        }
    }

    public static final class Registration {
        private final boolean accepted;
        private final CompletableFuture<Result> result;
        private final long generation;
        private final InteractionManager owner;
        private final Pending pending;

        private Registration(boolean accepted, CompletableFuture<Result> result, long generation,
                             InteractionManager owner, Pending pending) {
            this.accepted = accepted;
            this.result = Objects.requireNonNull(result, "result");
            this.generation = generation;
            this.owner = owner;
            this.pending = pending;
        }

        public boolean accepted() {
            return accepted;
        }

        public CompletableFuture<Result> result() {
            return result;
        }

        public long generation() {
            return generation;
        }

        public boolean activate() {
            return accepted && owner.activate(pending);
        }

        public boolean abort(Result result) {
            return accepted && owner.abort(pending, Objects.requireNonNull(result, "result"));
        }

        public boolean cancel(Result result) {
            return accepted && owner.cancel(pending, Objects.requireNonNull(result, "result"));
        }

        private static Registration rejected(Result result) {
            return new Registration(false, CompletableFuture.completedFuture(result), -1L, null, null);
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
        COMPLETED,
        CANCELLED
    }

    private static final class Pending {
        private static final Cancellable NOOP = () -> { };

        private final Request request;
        private final long generation;
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.RESERVED);
        private final AtomicReference<Cancellable> timeout = new AtomicReference<>(NOOP);
        private volatile long deadlineNanos = Long.MAX_VALUE;

        private Pending(Request request, long generation) {
            this.request = request;
            this.generation = generation;
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
                cancelSafely(task);
            }
        }

        private void cancelTimeout() {
            cancelSafely(timeout.getAndSet(NOOP));
        }

        private static void cancelSafely(Cancellable task) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // The interaction is already terminal; a scheduler cleanup failure must not wedge it.
            }
        }
    }
}
