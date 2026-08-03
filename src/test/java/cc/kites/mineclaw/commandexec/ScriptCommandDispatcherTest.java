package cc.kites.mineclaw.commandexec;

import cc.kites.mineclaw.support.AuditLogger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptCommandDispatcherTest {
    private static final UUID TURN_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ScriptCommandDispatcher.AuditContext AUDIT =
            new ScriptCommandDispatcher.AuditContext("inv-1", "script", "sha256:" + "0".repeat(64),
                    TURN_PLAYER, "Tester");

    @Test
    void dispatchesTrustedConsoleCommandWithoutModelWhitelistAndPreservesFeedback() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.consoleResult = CommandDispatchResult.consoleDispatched("warp-a\nwarp-b");
        ScriptCommandDispatcher dispatcher = dispatcher(runtime);

        ScriptCommandDispatcher.Result result = dispatcher.dispatch(json("""
                {
                  "executor":{"type":"console"},
                  "command":"kp   warp list",
                  "intent":"list exact warps"
                }
                """), () -> true, AUDIT).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo("dispatched");
        assertThat(result.output().get("dispatch_status").getAsString()).isEqualTo("accepted");
        assertThat(result.output().get("execution_result").getAsString()).isEqualTo("unknown");
        assertThat(result.output().get("feedback").getAsString()).isEqualTo("warp-a\nwarp-b");
        assertThat(runtime.lastCommand).isEqualTo("kp warp list");
        assertThat(runtime.consoleCalls).hasValue(1);
    }

    @Test
    void rejectsInvalidShapeAndLeadingSlashBeforeAnyDispatch() {
        FakeRuntime runtime = new FakeRuntime();
        ScriptCommandDispatcher dispatcher = dispatcher(runtime);

        ScriptCommandDispatcher.Result leadingSlash = dispatcher.dispatch(json("""
                {"executor":{"type":"console"},"command":"/say hello","intent":"test"}
                """), () -> true, AUDIT).toCompletableFuture().join();
        ScriptCommandDispatcher.Result unknown = dispatcher.dispatch(json("""
                {"executor":{"type":"console"},"command":"say hello","intent":"test","extra":true}
                """), () -> true, AUDIT).toCompletableFuture().join();

        assertThat(leadingSlash.status()).isEqualTo("invalid");
        assertThat(unknown.status()).isEqualTo("invalid");
        assertThat(runtime.consoleCalls).hasValue(0);
        assertThat(runtime.playerCalls).hasValue(0);
    }

    @Test
    void requiresTheResolvedOnlineAccountNameToMatchExactly() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.player = Optional.of(new CommandRuntime.OnlinePlayer(UUID.randomUUID(), "Alice"));
        ScriptCommandDispatcher dispatcher = dispatcher(runtime);

        ScriptCommandDispatcher.Result result = dispatcher.dispatch(json("""
                {
                  "executor":{"type":"player","player":"alice"},
                  "command":"kp warp list",
                  "intent":"list warps"
                }
                """), () -> true, AUDIT).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo("denied");
        assertThat(result.output().get("error_code").getAsString()).isEqualTo("player_offline");
        assertThat(runtime.playerCalls).hasValue(0);
    }

    @Test
    void guardPreventsAQueuedSideEffectAfterInvocationCancellation() {
        FakeRuntime runtime = new FakeRuntime();
        ScriptCommandDispatcher dispatcher = dispatcher(runtime);

        ScriptCommandDispatcher.Result result = dispatcher.dispatch(json("""
                {"executor":{"type":"console"},"command":"say hello","intent":"test"}
                """), () -> false, AUDIT).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo("cancelled");
        assertThat(runtime.consoleCalls).hasValue(0);
    }

    @Test
    void dispatchesArbitraryReviewedFunctionCommandsWithoutAWhitelistSnapshot() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.player = Optional.of(new CommandRuntime.OnlinePlayer(UUID.randomUUID(), "Alice"));
        ScriptCommandDispatcher dispatcher = dispatcher(runtime);

        ScriptCommandDispatcher.Result console = dispatcher.dispatch(json("""
                {"executor":{"type":"console"},"command":"op NotWhitelisted","intent":"reviewed workflow"}
                """), () -> true, AUDIT).toCompletableFuture().join();
        ScriptCommandDispatcher.Result player = dispatcher.dispatch(json("""
                {"executor":{"type":"player","player":"Alice"},"command":"stop","intent":"reviewed workflow"}
                """), () -> true, AUDIT).toCompletableFuture().join();

        assertThat(console.status()).isEqualTo("dispatched");
        assertThat(player.status()).isEqualTo("dispatched");
        assertThat(runtime.consoleCalls).hasValue(1);
        assertThat(runtime.playerCalls).hasValue(1);
    }

    private static ScriptCommandDispatcher dispatcher(FakeRuntime runtime) {
        return new ScriptCommandDispatcher(runtime,
                new AuditLogger(Logger.getLogger(ScriptCommandDispatcherTest.class.getName())));
    }

    private static JsonObject json(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    private static final class FakeRuntime implements CommandRuntime {
        private final AtomicInteger consoleCalls = new AtomicInteger();
        private final AtomicInteger playerCalls = new AtomicInteger();
        private Optional<OnlinePlayer> player = Optional.empty();
        private CommandDispatchResult consoleResult = CommandDispatchResult.consoleDispatched("");
        private CommandDispatchResult playerResult = CommandDispatchResult.playerDispatched();
        private String lastCommand;

        @Override
        public CompletionStage<Optional<OnlinePlayer>> findOnlinePlayer(String nameOrUuid) {
            return CompletableFuture.completedFuture(player);
        }

        @Override
        public CompletionStage<CommandDispatchResult> executeConsole(String command) {
            consoleCalls.incrementAndGet();
            lastCommand = command;
            return CompletableFuture.completedFuture(consoleResult);
        }

        @Override
        public CompletionStage<CommandDispatchResult> executePlayer(OnlinePlayer target, String command) {
            playerCalls.incrementAndGet();
            lastCommand = command;
            return CompletableFuture.completedFuture(playerResult);
        }

        @Override
        public CompletionStage<Boolean> send(OnlinePlayer target, String messageKey,
                                             Map<String, String> values) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }
}
