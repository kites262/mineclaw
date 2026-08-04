package cc.kites.mineclaw.commandexec;

import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.tool.ToolExecution;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorPolicyTest {
    private static final CommandExecutor.TurnPlayer TURN = new CommandExecutor.TurnPlayer(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "TurnPlayer");
    private static final CommandRuntime.OnlinePlayer OTHER = new CommandRuntime.OnlinePlayer(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "OtherPlayer");

    @Test
    void consoleRequiresEnabledRuleAndFullWhitelistMatch() {
        Fixture allowed = fixture(new CommandRules(true, List.of(), List.of(Pattern.compile("say hello"))));
        ToolExecution success = allowed.executor.execute(request("/Say   Hello", "announce", null), TURN).join();

        assertThat(success.pending()).isFalse();
        assertThat(success.immediate().status()).isEqualTo("dispatched");
        assertThat(allowed.runtime.consoleCommands).containsExactly("Say Hello");

        Fixture denied = fixture(new CommandRules(true, List.of(), List.of(Pattern.compile("say"))));
        ToolExecution miss = denied.executor.execute(request("say hello", "announce", null), TURN).join();
        assertThat(miss.immediate().status()).isEqualTo("denied");
        assertThat(miss.immediate().output().get("error_code").getAsString()).isEqualTo("whitelist_miss");
        assertThat(denied.runtime.consoleCommands).isEmpty();

        Fixture disabled = fixture(new CommandRules(false, List.of(), List.of(Pattern.compile(".*"))));
        assertThat(disabled.executor.execute(request("say hello", "announce", null), TURN).join()
                .immediate().output().get("error_code").getAsString()).isEqualTo("disabled");
    }

    @Test
    void sameTurnPlayerRunsWhitelistedCommandWithoutApproval() {
        Fixture fixture = fixture(new CommandRules(true, List.of(Pattern.compile("home")), List.of()));
        fixture.runtime.players.put(TURN.name(), new CommandRuntime.OnlinePlayer(TURN.uuid(), TURN.name()));

        ToolExecution execution = fixture.executor.execute(request("/HOME", "return home", TURN.name()), TURN).join();

        assertThat(execution.pending()).isFalse();
        assertThat(execution.immediate().status()).isEqualTo("dispatched");
        assertThat(execution.immediate().output().get("error_code").getAsString()).isEqualTo("none");
        assertThat(execution.immediate().output().get("dispatch_status").getAsString()).isEqualTo("accepted");
        assertThat(execution.immediate().output().get("execution_result").getAsString()).isEqualTo("unknown");
        assertThat(execution.immediate().output().has("feedback")).isFalse();
        assertThat(execution.immediate().output().get("message").getAsString()).doesNotContain("executed");
        assertThat(fixture.runtime.playerCommands).containsExactly("TurnPlayer:HOME");
        assertThat(fixture.runtime.messages).isEmpty();
    }

    @Test
    void whitelistMissCreatesOneShotApprovalAndCompletesContinuationAfterDispatch() {
        Fixture fixture = fixture(new CommandRules(true, List.of(Pattern.compile("home")), List.of()));
        CommandRuntime.OnlinePlayer same = new CommandRuntime.OnlinePlayer(TURN.uuid(), TURN.name());
        fixture.runtime.players.put(TURN.name(), same);

        ToolExecution execution = fixture.executor.execute(
                request("spawn", "visit spawn", TURN.name()), TURN).join();

        assertThat(execution.pending()).isTrue();
        assertThat(execution.immediate().status()).isEqualTo("pending_approval");
        assertThat(fixture.runtime.messages).extracting(Sent::key).containsExactly("approve_prompt");
        assertThat(fixture.runtime.playerCommands).isEmpty();
        String token = approvalToken(fixture);

        assertThat(fixture.executor.approve(TURN.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(fixture.executor.approve(TURN.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);

        assertThat(execution.continuation().join().status()).isEqualTo("dispatched");
        assertThat(execution.continuation().join().output().get("error_code").getAsString())
                .isEqualTo("none");
        assertThat(execution.continuation().join().output().get("execution_result").getAsString())
                .isEqualTo("unknown");
        assertThat(execution.continuation().join().output().has("feedback")).isFalse();
        assertThat(fixture.runtime.playerCommands).containsExactly("TurnPlayer:spawn");
        assertThat(fixture.runtime.messages).extracting(Sent::key)
                .containsExactly("approve_prompt", "approve_started");
    }

    @Test
    void validatedGestureUsesTheSameApprovedDispatchAndNotificationPath() {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);
        ToolExecution execution = fixture.executor.execute(
                request("home", "send other home", OTHER.name()), TURN).join();

        assertThat(execution.pending()).isTrue();
        assertThat(fixture.executor.approveCurrent(OTHER.uuid()))
                .isEqualTo(ApprovalManager.ApprovalOutcome.STARTED);
        assertThat(fixture.executor.approveCurrent(OTHER.uuid()))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);

        assertThat(execution.continuation().join().status()).isEqualTo("dispatched");
        assertThat(execution.continuation().join().output().get("error_code").getAsString())
                .isEqualTo("none");
        assertThat(execution.continuation().join().output().get("execution_result").getAsString())
                .isEqualTo("unknown");
        assertThat(fixture.runtime.playerCommands).containsExactly(OTHER.name() + ":home");
        assertThat(fixture.runtime.messages).extracting(Sent::key)
                .containsExactly("approve_prompt", "approve_started");
    }

    @Test
    void explicitRejectionFinishesPendingContinuationWithoutDispatch() {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);
        ToolExecution execution = fixture.executor.execute(
                request("home", "send other home", OTHER.name()), TURN).join();

        assertThat(execution.pending()).isTrue();
        String token = approvalToken(fixture);
        assertThat(fixture.executor.reject(OTHER.uuid(), UUID.randomUUID().toString()))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(execution.continuation()).isNotDone();
        assertThat(fixture.executor.reject(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.REJECTED);
        assertThat(fixture.executor.reject(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(fixture.executor.approve(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        fixture.scheduler.runAll();

        assertThat(execution.continuation().join().status()).isEqualTo("denied");
        assertThat(execution.continuation().join().output().get("message").getAsString())
                .contains("explicitly rejected");
        assertThat(fixture.runtime.playerCommands).isEmpty();
        assertThat(fixture.runtime.messages).extracting(Sent::key)
                .containsExactly("approve_prompt", "approve_rejected");
        assertThat(fixture.approvals.pendingCount()).isZero();
    }

    @Test
    void crossPlayerAlwaysSkipsPlayerWhitelistAndOfflineTargetIsDenied() {
        Fixture fixture = fixture(new CommandRules(true, List.of(Pattern.compile("home")), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);

        ToolExecution cross = fixture.executor.execute(request("home", "send other home", OTHER.name()), TURN).join();
        assertThat(cross.immediate().status()).isEqualTo("pending_approval");
        assertThat(fixture.runtime.playerCommands).isEmpty();

        Fixture offline = fixture(new CommandRules(true, List.of(Pattern.compile(".*")), List.of()));
        ToolExecution missing = offline.executor.execute(request("home", "go home", "Nobody"), TURN).join();
        assertThat(missing.immediate().status()).isEqualTo("denied");
        assertThat(missing.immediate().output().get("error_code").getAsString()).isEqualTo("player_offline");
    }

    @Test
    void timeoutNotifiesPlayerAndFinishesPendingContinuationWithoutDispatch() {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);
        ToolExecution execution = fixture.executor.execute(
                request("home", "send other home", OTHER.name()), TURN).join();
        String token = approvalToken(fixture);

        fixture.scheduler.runAll();

        assertThat(execution.continuation().join().status()).isEqualTo("timeout");
        assertThat(fixture.executor.reject(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(fixture.runtime.playerCommands).isEmpty();
        assertThat(fixture.runtime.messages).extracting(Sent::key)
                .containsExactly("approve_prompt", "approve_timeout");
    }

    @Test
    void approvalClockStartsOnlyAfterPrivatePromptHasBeenSent() {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);
        fixture.runtime.deferredPrompt = new CompletableFuture<>();

        CompletableFuture<ToolExecution> execution = fixture.executor.execute(
                request("home", "send other home", OTHER.name()), TURN);

        assertThat(execution).isNotDone();
        assertThat(fixture.approvals.pendingCount()).isOne();
        assertThat(fixture.scheduler.tasks).isEmpty();
        String token = approvalToken(fixture);
        assertThat(fixture.executor.approve(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        fixture.runtime.deferredPrompt.complete(Boolean.TRUE);

        assertThat(execution.join().immediate().status()).isEqualTo("pending_approval");
        assertThat(fixture.scheduler.tasks).hasSize(1);
    }

    @Test
    void reloadWhileApprovalPromptIsInFlightCannotRegisterAStaleRequest() {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);
        fixture.runtime.deferredPrompt = new CompletableFuture<>();
        CompletableFuture<ToolExecution> execution = fixture.executor.execute(
                request("home", "send other home", OTHER.name()), TURN);
        String token = approvalToken(fixture);

        fixture.approvals.invalidatePending();
        fixture.runtime.deferredPrompt.complete(Boolean.TRUE);

        ToolExecution rejected = execution.join();
        assertThat(rejected.pending()).isFalse();
        assertThat(rejected.immediate().status()).isEqualTo("denied");
        assertThat(fixture.approvals.pendingCount()).isZero();
        assertThat(fixture.executor.approve(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(fixture.runtime.playerCommands).isEmpty();
    }

    @Test
    void ownerThreadGuardRechecksConsoleWhitelistAfterReload() {
        CommandRules allowed = new CommandRules(true, List.of(), List.of(Pattern.compile("say hello")));
        CommandRules tightened = new CommandRules(true, List.of(), List.of());
        AtomicInteger reads = new AtomicInteger();
        Fixture fixture = fixture(() -> reads.getAndIncrement() == 0 ? allowed : tightened);

        ToolExecution execution = fixture.executor.execute(
                request("say hello", "announce", null), TURN).join();

        assertThat(execution.immediate().status()).isEqualTo("denied");
        assertThat(execution.immediate().output().get("error_code").getAsString())
                .isEqualTo("configuration_changed");
        assertThat(fixture.runtime.consoleCommands).isEmpty();
    }

    @Test
    void successfulReloadInvalidatesPendingApprovalBeforeItCanDispatch() {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of()));
        fixture.runtime.players.put(OTHER.name(), OTHER);
        ToolExecution execution = fixture.executor.execute(
                request("home", "send other home", OTHER.name()), TURN).join();
        String token = approvalToken(fixture);

        fixture.approvals.invalidatePending();

        assertThat(execution.continuation().join().status()).isEqualTo("denied");
        assertThat(fixture.executor.approve(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(fixture.executor.reject(OTHER.uuid(), token))
                .isEqualTo(ApprovalManager.ApprovalOutcome.NONE);
        assertThat(fixture.runtime.playerCommands).isEmpty();
    }

    @Test
    void consoleOutcomesRemainDistinctAndPreserveOnlySynchronousFeedback() {
        assertConsoleOutcome(CommandDispatchResult.consoleDispatched("changed one block"),
                "dispatched", "none", "unknown", "changed one block");
        assertConsoleOutcome(CommandDispatchResult.commandNotFound(),
                "terminal_error", "command_not_found", "not_started", null);
        assertConsoleOutcome(CommandDispatchResult.dispatchRejected("usage: /demo"),
                "terminal_error", "dispatch_rejected", "not_started", "usage: /demo");
        assertConsoleOutcome(CommandDispatchResult.executionException("plugin exploded", "before failure"),
                "terminal_error", "execution_exception", "failed", "before failure");
        assertConsoleOutcome(CommandDispatchResult.resultUnknown("no runtime result"),
                "terminal_error", "result_unknown", "unknown", null);
    }

    @Test
    void playerOutcomesRemainDistinctAndNeverExposeFeedback() {
        assertPlayerOutcome(CommandDispatchResult.playerDispatched(),
                "dispatched", "none", "unknown");
        assertPlayerOutcome(CommandDispatchResult.playerOffline(),
                "denied", "player_offline", "not_started");
        assertPlayerOutcome(CommandDispatchResult.commandNotFound(),
                "terminal_error", "command_not_found", "not_started");
        assertPlayerOutcome(CommandDispatchResult.dispatchRejected(),
                "terminal_error", "dispatch_rejected", "not_started");
        assertPlayerOutcome(CommandDispatchResult.executionException("plugin exploded", "must not escape"),
                "terminal_error", "execution_exception", "failed");
        assertPlayerOutcome(CommandDispatchResult.resultUnknown("no runtime result"),
                "terminal_error", "result_unknown", "unknown");
    }

    @Test
    void nullAndExceptionalRuntimeResultsAreReportedAsUnknownAndExecutionException() {
        Fixture nullResult = fixture(new CommandRules(true, List.of(), List.of(Pattern.compile("say hi"))));
        nullResult.runtime.consoleResult = null;
        ToolExecution unknown = nullResult.executor.execute(request("say hi", "announce", null), TURN).join();
        assertThat(unknown.immediate().output().get("error_code").getAsString()).isEqualTo("result_unknown");
        assertThat(unknown.immediate().output().get("dispatch_status").getAsString()).isEqualTo("unknown");

        Fixture failed = fixture(new CommandRules(true, List.of(), List.of(Pattern.compile("say hi"))));
        failed.runtime.consoleFailure = new IllegalStateException("scheduler failed");
        ToolExecution exception = failed.executor.execute(request("say hi", "announce", null), TURN).join();
        assertThat(exception.immediate().output().get("error_code").getAsString())
                .isEqualTo("execution_exception");
        assertThat(exception.immediate().output().get("message").getAsString()).isEqualTo("scheduler failed");
    }

    private static void assertConsoleOutcome(CommandDispatchResult runtimeResult, String status,
                                             String code, String executionResult, String feedback) {
        Fixture fixture = fixture(new CommandRules(true, List.of(), List.of(Pattern.compile("say hi"))));
        fixture.runtime.consoleResult = runtimeResult;

        ToolExecution execution = fixture.executor.execute(request("say hi", "announce", null), TURN).join();

        assertThat(execution.immediate().status()).isEqualTo(status);
        assertThat(execution.immediate().output().get("error_code").getAsString()).isEqualTo(code);
        assertThat(execution.immediate().output().get("execution_result").getAsString())
                .isEqualTo(executionResult);
        if (feedback == null) {
            assertThat(execution.immediate().output().has("feedback")).isFalse();
        } else {
            assertThat(execution.immediate().output().get("feedback").getAsString()).isEqualTo(feedback);
        }
    }

    private static void assertPlayerOutcome(CommandDispatchResult runtimeResult, String status,
                                            String code, String executionResult) {
        Fixture fixture = fixture(new CommandRules(true, List.of(Pattern.compile("home")), List.of()));
        fixture.runtime.players.put(TURN.name(), new CommandRuntime.OnlinePlayer(TURN.uuid(), TURN.name()));
        fixture.runtime.playerResult = runtimeResult;

        ToolExecution execution = fixture.executor.execute(
                request("home", "return home", TURN.name()), TURN).join();

        assertThat(execution.immediate().status()).isEqualTo(status);
        assertThat(execution.immediate().output().get("error_code").getAsString()).isEqualTo(code);
        assertThat(execution.immediate().output().get("execution_result").getAsString())
                .isEqualTo(executionResult);
        assertThat(execution.immediate().output().has("feedback")).isFalse();
    }

    private static Fixture fixture(CommandRules rules) {
        return fixture(() -> rules);
    }

    private static Fixture fixture(Supplier<CommandRules> rules) {
        FakeRuntime runtime = new FakeRuntime();
        ManualScheduler scheduler = new ManualScheduler();
        ApprovalManager approvals = new ApprovalManager(scheduler);
        Logger logger = Logger.getLogger("command-policy-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        CommandExecutor executor = new CommandExecutor(runtime, approvals, new AuditLogger(logger), rules);
        return new Fixture(runtime, scheduler, approvals, executor);
    }

    private static JsonObject request(String command, String intent, String player) {
        JsonObject result = new JsonObject();
        result.addProperty("command", command);
        result.addProperty("intent", intent);
        if (player == null) {
            result.addProperty("player", "");
        } else {
            result.addProperty("player", player);
        }
        return result;
    }

    private static String approvalToken(Fixture fixture) {
        Sent prompt = fixture.runtime.messages.stream()
                .filter(message -> message.key().equals("approve_prompt"))
                .findFirst()
                .orElseThrow();
        assertThat(prompt.values()).containsEntry("requester", TURN.name());
        String token = prompt.values().get("token");
        assertThat(token).isEqualTo(UUID.fromString(token).toString());
        return token;
    }

    private record Fixture(FakeRuntime runtime, ManualScheduler scheduler,
                           ApprovalManager approvals, CommandExecutor executor) {
    }

    private record Sent(UUID player, String key, Map<String, String> values) {
    }

    private static final class FakeRuntime implements CommandRuntime {
        private final Map<String, OnlinePlayer> players = new HashMap<>();
        private final List<String> consoleCommands = new ArrayList<>();
        private final List<String> playerCommands = new ArrayList<>();
        private final List<Sent> messages = new ArrayList<>();
        private CompletableFuture<Boolean> deferredPrompt;
        private CommandDispatchResult consoleResult = CommandDispatchResult.consoleDispatched("");
        private CommandDispatchResult playerResult = CommandDispatchResult.playerDispatched();
        private RuntimeException consoleFailure;

        @Override
        public CompletionStage<Optional<OnlinePlayer>> findOnlinePlayer(String nameOrUuid) {
            OnlinePlayer byName = players.get(nameOrUuid);
            if (byName != null) {
                return CompletableFuture.completedFuture(Optional.of(byName));
            }
            return CompletableFuture.completedFuture(players.values().stream()
                    .filter(player -> player.uuid().toString().equals(nameOrUuid)).findFirst());
        }

        @Override
        public CompletionStage<CommandDispatchResult> executeConsole(String command) {
            consoleCommands.add(command);
            if (consoleFailure != null) {
                return CompletableFuture.failedFuture(consoleFailure);
            }
            return CompletableFuture.completedFuture(consoleResult);
        }

        @Override
        public CompletionStage<CommandDispatchResult> executePlayer(OnlinePlayer player, String command) {
            playerCommands.add(player.name() + ":" + command);
            return CompletableFuture.completedFuture(playerResult);
        }

        @Override
        public CompletionStage<Boolean> send(OnlinePlayer player, String key, Map<String, String> values) {
            messages.add(new Sent(player.uuid(), key, Map.copyOf(values)));
            if (key.equals("approve_prompt") && deferredPrompt != null) {
                return deferredPrompt;
            }
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }

    private static final class ManualScheduler implements ApprovalManager.TimeoutScheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public ApprovalManager.Cancellable schedule(Duration delay, Runnable action) {
            Task task = new Task(action);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        private void runAll() {
            List.copyOf(tasks).forEach(Task::run);
        }
    }

    private static final class Task {
        private final Runnable action;
        private boolean cancelled;

        private Task(Runnable action) {
            this.action = action;
        }

        private void run() {
            if (!cancelled) {
                action.run();
            }
        }
    }
}
