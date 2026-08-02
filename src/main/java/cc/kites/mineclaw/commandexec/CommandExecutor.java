package cc.kites.mineclaw.commandexec;

import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.support.MessageService;
import cc.kites.mineclaw.tool.ToolDispatcher;
import cc.kites.mineclaw.tool.ToolExecution;
import cc.kites.mineclaw.tool.ToolResult;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.bukkit.Server;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/** Implements the {@code run_command} policy without performing Bukkit work off-owner. */
public final class CommandExecutor implements ToolDispatcher.CommandTool {
    private final CommandRuntime runtime;
    private final ApprovalManager approvals;
    private final AuditLogger audit;
    private final Supplier<CommandRules> rules;
    private final CommandRequest.Limits limits;

    public CommandExecutor(
            Server server,
            FoliaTasks tasks,
            ApprovalManager approvals,
            MessageService messages,
            AuditLogger audit,
            Supplier<CommandRules> rules
    ) {
        this(server, tasks, approvals, messages, audit, rules, ForkJoinPool.commonPool(),
                new CommandRootIndex());
    }

    public CommandExecutor(
            Server server,
            FoliaTasks tasks,
            ApprovalManager approvals,
            MessageService messages,
            AuditLogger audit,
            Supplier<CommandRules> rules,
            Executor ioExecutor
    ) {
        this(server, tasks, approvals, messages, audit, rules, ioExecutor, new CommandRootIndex());
    }

    public CommandExecutor(
            Server server,
            FoliaTasks tasks,
            ApprovalManager approvals,
            MessageService messages,
            AuditLogger audit,
            Supplier<CommandRules> rules,
            Executor ioExecutor,
            CommandRootIndex commandRoots
    ) {
        this(new BukkitCommandRuntime(server, tasks, messages, ioExecutor, commandRoots), approvals, audit, rules,
                CommandRequest.DEFAULT_LIMITS);
    }

    public CommandExecutor(
            CommandRuntime runtime,
            ApprovalManager approvals,
            AuditLogger audit,
            Supplier<CommandRules> rules
    ) {
        this(runtime, approvals, audit, rules, CommandRequest.DEFAULT_LIMITS);
    }

    public CommandExecutor(
            CommandRuntime runtime,
            ApprovalManager approvals,
            AuditLogger audit,
            Supplier<CommandRules> rules,
            CommandRequest.Limits limits
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Resolves target players on the global scheduler. Direct dispatch completes before the returned
     * execution is completed; an approval execution contains a continuation completed after approve/timeout.
     */
    public CompletableFuture<ToolExecution> execute(JsonObject arguments, TurnPlayer turnPlayer) {
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        long generationBefore = approvals.generation();
        CommandRules current;
        try {
            current = Objects.requireNonNull(rules.get(), "command rules");
        } catch (RuntimeException exception) {
            auditInvalid(turnPlayer, safeMessage(exception));
            return CompletableFuture.completedFuture(ToolExecution.completed(
                    simple("terminal_error", "rules_unavailable", safeMessage(exception))));
        }
        long generationAfter = approvals.generation();
        if (generationBefore != generationAfter || !approvals.isAccepting()) {
            auditInvalid(turnPlayer, "configuration changed while command policy was being read");
            return CompletableFuture.completedFuture(ToolExecution.completed(simple(
                    "denied", "configuration_changed", "command policy changed during request")));
        }
        return executeAtGeneration(arguments, turnPlayer, current, generationAfter);
    }

    /** Adapter for {@link ToolDispatcher}; policy still receives an explicit immutable {@link CommandRules}. */
    @Override
    public CompletableFuture<ToolExecution> execute(JsonObject arguments, ToolDispatcher.TurnPlayer turnPlayer,
                                                    MineclawConfig config) {
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(config, "config");
        return execute(arguments, new TurnPlayer(turnPlayer.id(), turnPlayer.name()));
    }

    CompletableFuture<ToolExecution> execute(
            JsonObject arguments, TurnPlayer turnPlayer, CommandRules current) {
        return executeAtGeneration(arguments, turnPlayer, current, approvals.generation());
    }

    private CompletableFuture<ToolExecution> executeAtGeneration(
            JsonObject arguments, TurnPlayer turnPlayer, CommandRules current, long policyGeneration) {
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(current, "current");
        CommandRequest request;
        try {
            request = CommandRequest.parse(arguments, limits);
        } catch (CommandRequest.InvalidCommandRequestException | NullPointerException exception) {
            auditInvalid(turnPlayer, exception.getMessage());
            return CompletableFuture.completedFuture(ToolExecution.completed(
                    simple("invalid", "invalid_request", safeMessage(exception))));
        }

        if (!current.runEnabled()) {
            audit(turnPlayer, request, "disabled", "not-checked", "none", "denied");
            return CompletableFuture.completedFuture(ToolExecution.completed(
                    result("denied", "disabled", "command execution is disabled", request, "none")));
        }

        if (request.console()) {
            return executeConsole(turnPlayer, request, current, policyGeneration);
        }
        return executePlayer(turnPlayer, request, current, policyGeneration);
    }

    public ApprovalManager.ApprovalOutcome approve(UUID playerId, String token) {
        return approvals.approve(playerId, token);
    }

    /** Accepts the actor's current request after a trusted in-world gesture was validated. */
    public ApprovalManager.ApprovalOutcome approveCurrent(UUID playerId) {
        return approvals.approveCurrent(playerId);
    }

    public ApprovalManager.ApprovalOutcome reject(UUID playerId, String token) {
        return approvals.reject(playerId, token);
    }

    private CompletableFuture<ToolExecution> executeConsole(
            TurnPlayer turnPlayer, CommandRequest request, CommandRules current, long policyGeneration) {
        if (!current.consoleAllowed(request)) {
            audit(turnPlayer, request, "console", "miss", "not-required", "denied");
            return CompletableFuture.completedFuture(ToolExecution.completed(result(
                    "denied", "whitelist_miss", "console command is not whitelisted", request, "console")));
        }
        audit(turnPlayer, request, "console", "matched", "not-required", "dispatching");
        return stage(dispatchConsole(turnPlayer, request, policyGeneration)).thenApply(ToolExecution::completed);
    }

    private CompletableFuture<ToolExecution> executePlayer(
            TurnPlayer turnPlayer, CommandRequest request, CommandRules current, long policyGeneration) {
        String identifier = request.player().orElseThrow();
        CompletableFuture<Optional<CommandRuntime.OnlinePlayer>> found = stage(runtime.findOnlinePlayer(identifier));
        return found.handle((target, error) -> {
            if (error != null) {
                audit(turnPlayer, request, "player:" + identifier, "not-checked", "none", "terminal-error");
                return CompletableFuture.completedFuture(ToolExecution.completed(result(
                        "terminal_error", "player_lookup_failed", safeMessage(error), request, "player:" + identifier)));
            }
            if (target.isEmpty()) {
                audit(turnPlayer, request, "player:" + identifier, "not-checked", "none", "denied-offline");
                return CompletableFuture.completedFuture(ToolExecution.completed(result(
                        "denied", "player_offline", "target player is not online", request, "player:" + identifier)));
            }
            return resolvedPlayer(turnPlayer, request, current, target.orElseThrow(), policyGeneration);
        }).thenCompose(stage -> stage);
    }

    private CompletableFuture<ToolExecution> resolvedPlayer(
            TurnPlayer turnPlayer,
            CommandRequest request,
            CommandRules current,
            CommandRuntime.OnlinePlayer target,
            long policyGeneration
    ) {
        boolean samePlayer = turnPlayer.uuid().equals(target.uuid());
        if (samePlayer && current.playerAllowed(request)) {
            audit(turnPlayer, request, identity(target), "matched", "not-required", "dispatching");
            return stage(dispatchPlayer(turnPlayer, request, target, "matched", "not-required",
                    policyGeneration, true))
                    .thenApply(ToolExecution::completed);
        }

        String whitelist = samePlayer ? "miss" : "skipped-cross-player";
        if (!commandRuntimeEnabled() || approvals.generation() != policyGeneration) {
            audit(turnPlayer, request, identity(target), whitelist, "invalidated", "denied");
            return CompletableFuture.completedFuture(ToolExecution.completed(result(
                    "denied", "configuration_changed", "command policy changed before approval prompt",
                    request, identity(target))));
        }
        String approvalToken = UUID.randomUUID().toString();
        ApprovalManager.Registration registration = approvals.reserveAtGeneration(
                target.uuid(), approvalToken, policyGeneration,
                approvalGeneration -> approvedDispatch(
                        turnPlayer, request, target, whitelist, approvalGeneration),
                () -> approvalTimedOut(turnPlayer, request, target, whitelist),
                () -> approvalRejected(turnPlayer, request, target, whitelist),
                () -> audit(turnPlayer, request, identity(target), whitelist, "cancelled", "cancelled"));
        if (!registration.accepted()) {
            ToolResult rejection = registration.continuation().getNow(result(
                    "denied", "approval_busy", "target already has a pending approval", request,
                    identity(target)));
            audit(turnPlayer, request, identity(target), whitelist, "not-created", "denied");
            return CompletableFuture.completedFuture(ToolExecution.completed(rejection));
        }

        Map<String, String> prompt = Map.of(
                "command", request.command(),
                "intent", request.intent(),
                "player", target.name(),
                "requester", turnPlayer.name(),
                "token", approvalToken);
        return stage(runtime.sendApprovalPrompt(target, prompt)).handle((sent, error) -> {
            if (error != null || !Boolean.TRUE.equals(sent)) {
                ToolResult unavailable = result("denied", "player_offline",
                        "target player became unavailable or cannot approve before approval prompt",
                        request, identity(target));
                boolean aborted = registration.abort(unavailable);
                audit(turnPlayer, request, identity(target), whitelist,
                        aborted ? "prompt-failed" : "invalidated",
                        aborted ? "denied-unavailable" : "denied");
                return ToolExecution.completed(registration.continuation().getNow(unavailable));
            }
            // PRD timeout starts only after the private prompt has actually been sent.
            if (!registration.activate()) {
                runtime.send(target, "approve_unavailable", Map.of());
                ToolResult rejection = registration.continuation().getNow(result(
                        "denied", "configuration_changed",
                        "command approval was invalidated before timeout activation", request,
                        identity(target)));
                audit(turnPlayer, request, identity(target), whitelist, "invalidated", "denied");
                return ToolExecution.completed(rejection);
            }
            audit(turnPlayer, request, identity(target), whitelist, "pending", "pending-approval");
            return ToolExecution.pending(result("pending_approval", "approval_required",
                    "target player approval is required", request, identity(target)), registration.continuation());
        });
    }

    private CompletionStage<ToolResult> approvedDispatch(
            TurnPlayer turnPlayer,
            CommandRequest request,
            CommandRuntime.OnlinePlayer target,
            String whitelist,
            long approvalGeneration
    ) {
        if (!commandRuntimeEnabled() || approvals.generation() != approvalGeneration) {
            audit(turnPlayer, request, identity(target), whitelist, "invalidated", "denied");
            return CompletableFuture.completedFuture(result("denied", "configuration_changed",
                    "command approval was invalidated by configuration or lifecycle change",
                    request, identity(target)));
        }
        audit(turnPlayer, request, identity(target), whitelist, "approved", "dispatching");
        CompletionStage<Boolean> notice = runtime.send(target, "approve_started", Map.of());
        return stage(notice).handle((ignored, error) -> null)
                .thenCompose(ignored -> dispatchPlayer(turnPlayer, request, target, whitelist, "approved",
                        approvalGeneration, false));
    }

    private void approvalTimedOut(
            TurnPlayer turnPlayer,
            CommandRequest request,
            CommandRuntime.OnlinePlayer target,
            String whitelist
    ) {
        runtime.send(target, "approve_timeout", Map.of());
        audit(turnPlayer, request, identity(target), whitelist, "timeout", "timeout");
    }

    private void approvalRejected(
            TurnPlayer turnPlayer,
            CommandRequest request,
            CommandRuntime.OnlinePlayer target,
            String whitelist
    ) {
        runtime.send(target, "approve_rejected", Map.of());
        audit(turnPlayer, request, identity(target), whitelist, "rejected", "denied");
    }

    private CompletionStage<ToolResult> dispatchConsole(
            TurnPlayer turnPlayer, CommandRequest request, long policyGeneration) {
        return stage(runtime.executeConsoleGuarded(request.command(), () ->
                approvals.generation() == policyGeneration && consoleStillAllowed(request)))
                .handle((dispatch, error) -> {
            CommandDispatchResult observed = observed(dispatch, error);
            if (observed.outcome() == CommandDispatchResult.Outcome.PLAYER_DISPATCHED
                    || observed.outcome() == CommandDispatchResult.Outcome.PLAYER_OFFLINE) {
                observed = CommandDispatchResult.resultUnknown(
                        "command runtime returned a player-only outcome for console dispatch");
            }
            if (observed.outcome() == CommandDispatchResult.Outcome.DISPATCH_REJECTED
                    && (approvals.generation() != policyGeneration || !consoleStillAllowed(request))) {
                audit(turnPlayer, request, "console", "matched", "not-required", "configuration-changed");
                return dispatchResult("denied", "configuration_changed",
                        "console command policy changed before dispatch", request, "console", observed,
                        "not_started", observed.feedback());
            }
            DispatchPresentation presentation = consolePresentation(observed);
            audit(turnPlayer, request, "console", "matched", "not-required", presentation.auditOutcome());
            return dispatchResult(presentation.status(), presentation.code(), presentation.message(),
                    request, "console", observed, presentation.executionResult(), observed.feedback());
        });
    }

    private CompletionStage<ToolResult> dispatchPlayer(
            TurnPlayer turnPlayer,
            CommandRequest request,
            CommandRuntime.OnlinePlayer target,
            String whitelist,
            String approval,
            long policyGeneration,
            boolean directWhitelist
    ) {
        return stage(runtime.executePlayerGuarded(target, request.command(), () ->
                approvals.generation() == policyGeneration
                        && (directWhitelist ? playerStillAllowed(request) : commandRuntimeEnabled())))
                .handle((dispatch, error) -> {
            CommandDispatchResult observed = observed(dispatch, error);
            if (observed.outcome() == CommandDispatchResult.Outcome.CONSOLE_DISPATCHED) {
                observed = CommandDispatchResult.resultUnknown(
                        "command runtime returned a console-only outcome for player dispatch");
            }
            if (observed.outcome() == CommandDispatchResult.Outcome.DISPATCH_REJECTED
                    && (approvals.generation() != policyGeneration
                    || (directWhitelist ? !playerStillAllowed(request) : !commandRuntimeEnabled()))) {
                audit(turnPlayer, request, identity(target), whitelist, approval, "configuration-changed");
                return dispatchResult("denied", "configuration_changed",
                        "player command policy changed before dispatch", request, identity(target), observed,
                        "not_started", "");
            }
            DispatchPresentation presentation = playerPresentation(observed);
            audit(turnPlayer, request, identity(target), whitelist, approval, presentation.auditOutcome());
            if (!directWhitelist
                    && observed.outcome() != CommandDispatchResult.Outcome.PLAYER_DISPATCHED) {
                runtime.send(target, "approve_unavailable", Map.of());
            }
            // Player command feedback is never captured. A true performCommand result confirms only
            // that dispatch was accepted, so no player path may populate the feedback field.
            return dispatchResult(presentation.status(), presentation.code(), presentation.message(),
                    request, identity(target), observed, presentation.executionResult(), "");
        });
    }

    private static CommandDispatchResult observed(CommandDispatchResult dispatch, Throwable error) {
        if (error != null) {
            return CommandDispatchResult.executionException(safeMessage(error));
        }
        return dispatch == null
                ? CommandDispatchResult.resultUnknown("command runtime returned no dispatch result")
                : dispatch;
    }

    private static DispatchPresentation consolePresentation(CommandDispatchResult dispatch) {
        return switch (dispatch.outcome()) {
            case CONSOLE_DISPATCHED -> new DispatchPresentation(
                    "dispatched", "none",
                    "console command was dispatched; actual effects are unknown and feedback contains only synchronous output",
                    "dispatched", "unknown");
            case COMMAND_NOT_FOUND -> new DispatchPresentation(
                    "terminal_error", "command_not_found", "command was not found", "command-not-found",
                    "not_started");
            case DISPATCH_REJECTED -> new DispatchPresentation(
                    "terminal_error", "dispatch_rejected", "console command dispatch was rejected",
                    "dispatch-rejected", "not_started");
            case EXECUTION_EXCEPTION -> new DispatchPresentation(
                    "terminal_error", "execution_exception", detailOr(dispatch, "command dispatch threw an exception"),
                    "execution-exception", "failed");
            case RESULT_UNKNOWN -> new DispatchPresentation(
                    "terminal_error", "result_unknown", detailOr(dispatch, "console dispatch result is unknown"),
                    "result-unknown", "unknown");
            case PLAYER_OFFLINE, PLAYER_DISPATCHED -> throw new IllegalStateException(
                    "player-only outcome must be normalized before console presentation");
        };
    }

    private static DispatchPresentation playerPresentation(CommandDispatchResult dispatch) {
        return switch (dispatch.outcome()) {
            case PLAYER_DISPATCHED -> new DispatchPresentation(
                    "dispatched", "none",
                    "player command was submitted to the command system; actual execution result is unknown",
                    "dispatched", "unknown");
            case PLAYER_OFFLINE -> new DispatchPresentation(
                    "denied", "player_offline", "target player is no longer online", "player-offline",
                    "not_started");
            case COMMAND_NOT_FOUND -> new DispatchPresentation(
                    "terminal_error", "command_not_found", "command was not found", "command-not-found",
                    "not_started");
            case DISPATCH_REJECTED -> new DispatchPresentation(
                    "terminal_error", "dispatch_rejected", "player command dispatch was rejected",
                    "dispatch-rejected", "not_started");
            case EXECUTION_EXCEPTION -> new DispatchPresentation(
                    "terminal_error", "execution_exception", detailOr(dispatch, "command dispatch threw an exception"),
                    "execution-exception", "failed");
            case RESULT_UNKNOWN -> new DispatchPresentation(
                    "terminal_error", "result_unknown", detailOr(dispatch, "player dispatch result is unknown"),
                    "result-unknown", "unknown");
            case CONSOLE_DISPATCHED -> throw new IllegalStateException(
                    "console-only outcome must be normalized before player presentation");
        };
    }

    private static String detailOr(CommandDispatchResult dispatch, String fallback) {
        return dispatch.detail().isBlank() ? fallback : dispatch.detail();
    }


    private boolean commandRuntimeEnabled() {
        if (!approvals.isAccepting()) {
            return false;
        }
        try {
            return Objects.requireNonNull(rules.get(), "command rules").runEnabled();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean consoleStillAllowed(CommandRequest request) {
        if (!approvals.isAccepting()) {
            return false;
        }
        try {
            CommandRules current = Objects.requireNonNull(rules.get(), "command rules");
            return current.runEnabled() && current.consoleAllowed(request);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean playerStillAllowed(CommandRequest request) {
        if (!approvals.isAccepting()) {
            return false;
        }
        try {
            CommandRules current = Objects.requireNonNull(rules.get(), "command rules");
            return current.runEnabled() && current.playerAllowed(request);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void auditInvalid(TurnPlayer turnPlayer, String reason) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("turn_player", turnPlayer.label());
        fields.put("command", "<invalid>");
        fields.put("intent", "<invalid>");
        fields.put("player", "<invalid>");
        fields.put("execution_identity", "none");
        fields.put("whitelist", "not-checked");
        fields.put("approval", "none");
        fields.put("result", "invalid");
        fields.put("reason", reason == null ? "invalid request" : reason);
        audit.command("request", fields);
    }

    private void audit(TurnPlayer turnPlayer, CommandRequest request, String identity,
                       String whitelist, String approval, String outcome) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("turn_player", turnPlayer.label());
        fields.put("command", request.command());
        fields.put("intent", request.intent());
        fields.put("player", request.player().orElse("null"));
        fields.put("execution_identity", identity);
        fields.put("whitelist", whitelist);
        fields.put("approval", approval);
        fields.put("result", outcome);
        audit.command("request", fields);
    }

    private static ToolResult simple(String status, String errorCode, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("error_code", errorCode);
        output.addProperty("message", message);
        return new ToolResult(status, output);
    }

    private static ToolResult result(String status, String code, String message,
                                     CommandRequest request, String identity) {
        return result(status, code, message, request, identity, "");
    }

    private static ToolResult result(String status, String code, String message,
                                     CommandRequest request, String identity, String feedback) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        output.addProperty("command", request.command());
        output.addProperty("intent", request.intent());
        if (request.player().isPresent()) {
            output.addProperty("player", request.player().orElseThrow());
        } else {
            output.add("player", JsonNull.INSTANCE);
        }
        output.addProperty("execution_identity", identity);
        if (feedback != null && !feedback.isBlank()) {
            output.addProperty("feedback", feedback);
        }
        return new ToolResult(status, output);
    }

    private static ToolResult dispatchResult(
            String status,
            String code,
            String message,
            CommandRequest request,
            String identity,
            CommandDispatchResult dispatch,
            String executionResult,
            String feedback
    ) {
        ToolResult result = result(status, code, message, request, identity, feedback);
        result.output().addProperty("dispatch_status", switch (dispatch.outcome()) {
            case CONSOLE_DISPATCHED, PLAYER_DISPATCHED -> "accepted";
            case PLAYER_OFFLINE -> "player_offline";
            case COMMAND_NOT_FOUND -> "command_not_found";
            case DISPATCH_REJECTED -> "rejected";
            case EXECUTION_EXCEPTION -> "exception";
            case RESULT_UNKNOWN -> "unknown";
        });
        result.output().addProperty("dispatch_outcome",
                dispatch.outcome().name().toLowerCase(Locale.ROOT));
        result.output().addProperty("execution_result", executionResult);
        return result;
    }
    private static String identity(CommandRuntime.OnlinePlayer player) {
        return "player:" + player.name() + "(" + player.uuid() + ")";
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static <T> CompletableFuture<T> stage(CompletionStage<T> source) {
        Objects.requireNonNull(source, "source");
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private record DispatchPresentation(
            String status,
            String code,
            String message,
            String auditOutcome,
            String executionResult
    ) {
    }

    public record TurnPlayer(UUID uuid, String name) {
        public TurnPlayer {
            Objects.requireNonNull(uuid, "uuid");
            name = Objects.requireNonNull(name, "name");
        }

        public String label() {
            return name + "(" + uuid + ")";
        }
    }
}
