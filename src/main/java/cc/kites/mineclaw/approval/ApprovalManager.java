package cc.kites.mineclaw.approval;

import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.tool.ToolResult;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Compatibility facade for the original command-approval API. All registrations live in the
 * shared generic {@link InteractionManager}, so command approvals and scripted interactions obey
 * the same per-player, generation, token, and cancellation rules.
 */
public final class ApprovalManager {
    public static final Duration DEFAULT_TIMEOUT = InteractionManager.DEFAULT_TIMEOUT;
    private static final InteractionManager.Confirm COMMAND_CONFIRM = new InteractionManager.Confirm(
            "Command approval", "Allow Mineclaw to dispatch the pending command?");

    private final InteractionManager interactions;

    public ApprovalManager(FoliaTasks tasks) {
        this(new InteractionManager(Objects.requireNonNull(tasks, "tasks")));
    }

    public ApprovalManager(TimeoutScheduler scheduler) {
        this(new InteractionManager((delay, action) -> {
            Cancellable cancellable = Objects.requireNonNull(scheduler, "scheduler").schedule(delay, action);
            return Objects.requireNonNull(cancellable, "cancellable")::cancel;
        }));
    }

    public ApprovalManager(InteractionManager interactions) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    /** Generic manager used by JavaScript workflows and interaction command routing. */
    public InteractionManager interactions() {
        return interactions;
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

    public Registration requestWithGeneration(
            UUID playerId,
            String token,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        return activate(register(playerId, token, DEFAULT_TIMEOUT,
                InteractionManager.CURRENT_GENERATION, approvedAction, timedOut, cancelled, cancelled));
    }

    public Registration requestWithGeneration(
            UUID playerId,
            String token,
            Duration timeout,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable cancelled
    ) {
        return activate(register(playerId, token, timeout,
                InteractionManager.CURRENT_GENERATION, approvedAction, timedOut, cancelled, cancelled));
    }

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

    public Registration requestAtGeneration(
            UUID playerId,
            String token,
            long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable rejected,
            Runnable cancelled
    ) {
        requireGeneration(expectedGeneration);
        return activate(register(playerId, token, DEFAULT_TIMEOUT, expectedGeneration,
                approvedAction, timedOut, rejected, cancelled));
    }

    public Registration reserveAtGeneration(
            UUID playerId,
            String token,
            long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable rejected,
            Runnable cancelled
    ) {
        requireGeneration(expectedGeneration);
        return register(playerId, token, DEFAULT_TIMEOUT, expectedGeneration,
                approvedAction, timedOut, rejected, cancelled);
    }

    private Registration register(
            UUID playerId,
            String token,
            Duration timeout,
            long expectedGeneration,
            LongFunction<? extends CompletionStage<ToolResult>> approvedAction,
            Runnable timedOut,
            Runnable rejected,
            Runnable cancelled
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(approvedAction, "approvedAction");
        Objects.requireNonNull(timedOut, "timedOut");
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(cancelled, "cancelled");

        String scopeId = "command:" + token;
        InteractionManager.Request request = new InteractionManager.Request(
                playerId, "Player", token, scopeId, token, COMMAND_CONFIRM, timeout, expectedGeneration);
        InteractionManager.Registration generic = interactions.reserve(request);
        if (!generic.accepted()) {
            return Registration.rejected(legacyRejection(generic.result().join()));
        }

        CompletableFuture<ToolResult> continuation = new CompletableFuture<>();
        generic.result().whenComplete((decision, failure) -> {
            if (failure != null) {
                runSafely(cancelled);
                continuation.complete(simple("terminal_error", "interaction_failure", safeMessage(failure)));
                return;
            }
            switch (decision.status()) {
                case APPROVED -> runApproved(approvedAction, generic.generation(), continuation);
                case REJECTED -> {
                    runSafely(rejected);
                    continuation.complete(ToolResult.simple(
                            "denied", "target player explicitly rejected command approval"));
                }
                case TIMEOUT -> {
                    runSafely(timedOut);
                    continuation.complete(ToolResult.simple("timeout", "command approval timed out"));
                }
                case PLAYER_OFFLINE -> {
                    runSafely(cancelled);
                    continuation.complete(simple("denied", "player_offline",
                            "target player went offline during command approval"));
                }
                case BUSY -> continuation.complete(ToolResult.simple(
                        "denied", "player already has a pending approval"));
                case DENIED, INVALID -> {
                    runSafely(cancelled);
                    continuation.complete(simple(decision.status().wireName(), decision.errorCode(),
                            decision.message()));
                }
                case CANCELLED -> {
                    runSafely(cancelled);
                    continuation.complete(cancelledResult(decision));
                }
            }
        });
        return new Registration(true, continuation, generic);
    }

    private Registration activate(Registration registration) {
        if (!registration.accepted() || registration.activate()) {
            return registration;
        }
        return new Registration(false, registration.continuation(), registration.generic);
    }

    public ApprovalOutcome approve(UUID playerId, String token) {
        return outcome(interactions.approve(playerId, token));
    }

    /** Trusted gesture path; select interactions are deliberately ineligible. */
    public ApprovalOutcome approveCurrent(UUID playerId) {
        return outcome(interactions.approveCurrentConfirm(playerId));
    }

    public ApprovalOutcome reject(UUID playerId, String token) {
        InteractionManager.Outcome result = interactions.reject(playerId, token);
        return result == InteractionManager.Outcome.REJECTED ? ApprovalOutcome.REJECTED : ApprovalOutcome.NONE;
    }

    /** Completes a player's pending command or scripted interaction as offline. */
    public boolean playerOffline(UUID playerId) {
        return interactions.playerOffline(playerId);
    }

    /** Withdraws the exact current player request for legacy callers. */
    public boolean cancel(UUID playerId, ToolResult result) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(result, "result");
        return interactions.cancelCurrent(playerId, adapt(result, "approval_cancelled"));
    }

    public void cancelAll() {
        interactions.close();
    }

    public void invalidatePending() {
        interactions.invalidatePending();
    }

    public long generation() {
        return interactions.generation();
    }

    public boolean isAccepting() {
        return interactions.isAccepting();
    }

    public boolean hasPending(UUID playerId) {
        return interactions.hasPending(playerId);
    }

    public int pendingCount() {
        return interactions.pendingCount();
    }

    private static void runApproved(LongFunction<? extends CompletionStage<ToolResult>> action,
                                    long generation, CompletableFuture<ToolResult> continuation) {
        CompletionStage<ToolResult> result;
        try {
            result = Objects.requireNonNull(action.apply(generation), "approved action result");
        } catch (RuntimeException exception) {
            continuation.complete(simple("terminal_error", "approved_action_failed", safeMessage(exception)));
            return;
        }
        result.whenComplete((toolResult, failure) -> {
            if (failure != null) {
                continuation.complete(simple("terminal_error", "approved_action_failed", safeMessage(failure)));
            } else if (toolResult == null) {
                continuation.complete(simple("terminal_error", "approved_action_failed",
                        "approved action returned no result"));
            } else {
                continuation.complete(toolResult);
            }
        });
    }

    private static ToolResult legacyRejection(InteractionManager.Result result) {
        return switch (result.status()) {
            case BUSY -> ToolResult.simple("denied", "player already has a pending approval");
            case CANCELLED -> cancelledResult(result);
            case PLAYER_OFFLINE -> simple("denied", "player_offline", result.message());
            default -> simple(result.status().wireName(), result.errorCode(), result.message());
        };
    }

    private static ToolResult cancelledResult(InteractionManager.Result result) {
        if ("plugin_disabled".equals(result.errorCode())) {
            return ToolResult.simple("terminal_error", "plugin disabled while command approval was pending");
        }
        if ("configuration_changed".equals(result.errorCode())) {
            return ToolResult.simple("denied", "configuration changed during command approval");
        }
        return simple("cancelled", result.errorCode(), result.message());
    }

    private static ToolResult simple(String status, String errorCode, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("error_code", errorCode == null ? "interaction_failure" : errorCode);
        output.addProperty("message", message == null ? "interaction failed" : message);
        return new ToolResult(status, output);
    }

    private static ApprovalOutcome outcome(InteractionManager.Outcome result) {
        return result == InteractionManager.Outcome.APPROVED
                ? ApprovalOutcome.STARTED : ApprovalOutcome.NONE;
    }

    private static void requireGeneration(long generation) {
        if (generation < 0L) {
            throw new IllegalArgumentException("expected generation must not be negative");
        }
    }

    private static void runSafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Registry completion must not be held hostage by a notification callback.
        }
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public enum ApprovalOutcome {
        STARTED,
        REJECTED,
        NONE
    }

    public static final class Registration {
        private final boolean accepted;
        private final CompletableFuture<ToolResult> continuation;
        private final InteractionManager.Registration generic;

        private Registration(boolean accepted, CompletableFuture<ToolResult> continuation,
                             InteractionManager.Registration generic) {
            this.accepted = accepted;
            this.continuation = Objects.requireNonNull(continuation, "continuation");
            this.generic = generic;
        }

        public boolean accepted() {
            return accepted;
        }

        public CompletableFuture<ToolResult> continuation() {
            return continuation;
        }

        public boolean activate() {
            return accepted && generic.activate();
        }

        public boolean abort(ToolResult result) {
            Objects.requireNonNull(result, "result");
            if (!accepted) {
                return false;
            }
            return generic.abort(adapt(result, "approval_aborted"));
        }

        /** Exact cancellation hook used by async ToolExecution owners. */
        public boolean cancel(ToolResult result) {
            Objects.requireNonNull(result, "result");
            if (!accepted) {
                return false;
            }
            String message = result.output().has("message")
                    ? result.output().get("message").getAsString() : "approval was cancelled";
            return generic.cancel(InteractionManager.Result.cancelled("approval_cancelled", message));
        }

        private static Registration rejected(ToolResult result) {
            return new Registration(false, CompletableFuture.completedFuture(result), null);
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

    private static InteractionManager.Result adapt(ToolResult result, String fallbackCode) {
        String status = result.status();
        String message = result.output().has("message")
                ? result.output().get("message").getAsString() : "approval was cancelled";
        String errorCode = result.output().has("error_code")
                ? result.output().get("error_code").getAsString() : fallbackCode;
        return switch (status) {
            case "invalid" -> InteractionManager.Result.invalid(errorCode, message);
            case "cancelled" -> InteractionManager.Result.cancelled(errorCode, message);
            default -> InteractionManager.Result.denied(errorCode, message);
        };
    }
}
