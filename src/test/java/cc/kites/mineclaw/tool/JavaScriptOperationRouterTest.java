package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.commandexec.CommandDispatchResult;
import cc.kites.mineclaw.commandexec.CommandRuntime;
import cc.kites.mineclaw.commandexec.ScriptCommandDispatcher;
import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.javascript.OperationCall;
import cc.kites.mineclaw.javascript.OperationHandle;
import cc.kites.mineclaw.javascript.OperationResult;
import cc.kites.mineclaw.support.AuditLogger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JavaScriptOperationRouterTest {
    private static final UUID TURN_PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final CommandRuntime.OnlinePlayer TARGET =
            new CommandRuntime.OnlinePlayer(TARGET_PLAYER, "Alice");

    @Test
    void confirmResolvesApprovedBooleanAndBindsTheGeneratedTokenToResolvedUuid() {
        Harness harness = new Harness();

        OperationHandle handle = harness.invoke("inv-confirm", 1, json("""
                {
                  "player":"Alice",
                  "interaction":{
                    "type":"confirm",
                    "title":"Confirm",
                    "message":"Proceed?"
                  },
                  "timeout_ms":1000
                }
                """));

        assertThat(harness.runtime.promptCalls).hasValue(1);
        assertThat(harness.runtime.promptTarget).isEqualTo(TARGET);
        assertThat(harness.runtime.promptToken).isNotNull();
        assertThat(UUID.fromString(harness.runtime.promptToken).toString())
                .isEqualTo(harness.runtime.promptToken);
        assertThat(harness.runtime.promptInteraction)
                .isEqualTo(new InteractionManager.Confirm("Confirm", "Proceed?"));
        assertThat(harness.interactions.approve(TURN_PLAYER, harness.runtime.promptToken))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(harness.interactions.select(TARGET_PLAYER, harness.runtime.promptToken, "anything"))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(harness.interactions.approve(TARGET_PLAYER, harness.runtime.promptToken))
                .isEqualTo(InteractionManager.Outcome.APPROVED);

        OperationResult result = handle.completion().toCompletableFuture().join();
        assertThat(result.status()).isEqualTo("approved");
        assertThat(result.output().keySet()).containsExactly("value");
        assertThat(result.output().get("value").getAsBoolean()).isTrue();
        assertThat(harness.interactions.pendingCount()).isZero();
    }

    @Test
    void selectAcceptsOnlyRegisteredOptionAndGestureCannotCompleteIt() {
        Harness harness = new Harness();

        OperationHandle handle = harness.invoke("inv-select", 1, json("""
                {
                  "player":"Alice",
                  "interaction":{
                    "type":"select",
                    "title":"Choose",
                    "message":"Pick one",
                    "options":[
                      {"id":"a","label":"Plan A"},
                      {"id":"plan-b","label":"Plan B"}
                    ]
                  }
                }
                """));

        String token = harness.runtime.promptToken;
        assertThat(harness.interactions.approveCurrentConfirm(TARGET_PLAYER))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(harness.interactions.approve(TARGET_PLAYER, token))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(harness.interactions.select(TARGET_PLAYER, token, "missing"))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(handle.completion().toCompletableFuture()).isNotDone();
        assertThat(harness.interactions.select(TARGET_PLAYER, token, "plan-b"))
                .isEqualTo(InteractionManager.Outcome.SELECTED);

        OperationResult result = handle.completion().toCompletableFuture().join();
        assertThat(result.status()).isEqualTo("approved");
        assertThat(result.output().keySet()).containsExactly("value");
        assertThat(result.output().get("value").getAsString()).isEqualTo("plan-b");
    }

    @Test
    void secondInteractionForTheSamePlayerResolvesBusyWithoutSendingAnotherPrompt() {
        Harness harness = new Harness();
        OperationHandle first = harness.invoke("inv-first", 1, confirmInput());

        OperationResult busy = harness.invoke("inv-second", 1, confirmInput())
                .completion().toCompletableFuture().join();

        assertThat(busy.status()).isEqualTo("busy");
        assertThat(busy.output().keySet()).containsExactly("value", "error_code", "message");
        assertThat(busy.output().get("value").isJsonNull()).isTrue();
        assertThat(busy.output().get("error_code").getAsString()).isEqualTo("interaction_busy");
        assertThat(harness.runtime.promptCalls).hasValue(1);
        assertThat(harness.interactions.pendingCount()).isOne();

        first.cancel();
        assertThat(harness.interactions.pendingCount()).isZero();
    }

    @Test
    void missingPlayerOrFailedPromptDeliveryResolvePlayerOffline() {
        Harness missing = new Harness();
        missing.runtime.player = Optional.empty();

        OperationResult lookupResult = missing.invoke("inv-missing", 1, confirmInput())
                .completion().toCompletableFuture().join();

        assertPlayerOffline(lookupResult);
        assertThat(missing.runtime.promptCalls).hasValue(0);
        assertThat(missing.interactions.pendingCount()).isZero();

        Harness retired = new Harness();
        retired.runtime.promptSent = false;

        OperationResult deliveryResult = retired.invoke("inv-retired", 1, confirmInput())
                .completion().toCompletableFuture().join();

        assertPlayerOffline(deliveryResult);
        assertThat(retired.runtime.promptCalls).hasValue(1);
        assertThat(retired.interactions.pendingCount()).isZero();
    }

    @Test
    void cancellingTheOperationWithdrawsItsExactInteractionAndLateClicksDoNothing() {
        Harness harness = new Harness();
        OperationHandle handle = harness.invoke("inv-cancel", 1, confirmInput());
        String token = harness.runtime.promptToken;

        handle.cancel();

        OperationResult result = handle.completion().toCompletableFuture().join();
        assertThat(result.status()).isEqualTo("cancelled");
        assertThat(result.output().keySet()).containsExactly("value", "error_code", "message");
        assertThat(result.output().get("value").isJsonNull()).isTrue();
        assertThat(result.output().get("error_code").getAsString()).isEqualTo("invocation_cancelled");
        assertThat(harness.interactions.pendingCount()).isZero();
        assertThat(harness.interactions.approve(TARGET_PLAYER, token))
                .isEqualTo(InteractionManager.Outcome.NONE);
        handle.cancel();
        assertThat(harness.interactions.pendingCount()).isZero();
    }

    @Test
    void strictApprovalSchemaViolationsResolveInvalidBeforePlayerLookup() {
        Harness harness = new Harness();
        JsonObject[] invalidInputs = {
                json("""
                        {"player":"Alice","interaction":{"type":"confirm","title":"T","message":"M"},
                         "extra":true}
                        """),
                json("""
                        {"player":"Alice","interaction":{"type":"confirm","title":"T","message":"M",
                         "options":[]}}
                        """),
                json("""
                        {"player":"Alice","interaction":{"type":"select","title":"T","message":"M",
                         "options":[{"id":"a","label":"A","extra":true},{"id":"b","label":"B"}]}}
                        """),
                json("""
                        {"player":"Alice","interaction":{"type":"select","title":"T","message":"M",
                         "options":[{"id":"bad value","label":"A"},{"id":"b","label":"B"}]}}
                        """),
                json("""
                        {"player":"Alice","interaction":{"type":"select","title":"T","message":"M",
                         "options":[{"id":"same","label":"A"},{"id":"same","label":"B"}]}}
                        """),
                json("""
                        {"player":"Alice","interaction":{"type":"confirm","title":"T","message":"M"},
                         "timeout_ms":1.5}
                        """),
                json("""
                        {"player":"Alice","interaction":{"type":"confirm","title":"T","message":"M"},
                         "timeout_ms":999}
                        """)
        };

        for (int index = 0; index < invalidInputs.length; index++) {
            OperationResult result = harness.invoke("inv-invalid-" + index, 1, invalidInputs[index])
                    .completion().toCompletableFuture().join();
            assertThat(result.status()).isEqualTo("invalid");
            assertThat(result.output().keySet()).containsExactly("value", "error_code", "message");
            assertThat(result.output().get("value").isJsonNull()).isTrue();
            assertThat(result.output().get("error_code").getAsString())
                    .isEqualTo("invalid_approval_request");
        }
        assertThat(harness.runtime.lookupCalls).hasValue(0);
        assertThat(harness.runtime.promptCalls).hasValue(0);
        assertThat(harness.interactions.pendingCount()).isZero();
    }

    @Test
    void rejectsCallFunctionAtTheNativeRouterBoundaryWithoutDelegating() {
        Harness harness = new Harness();
        AtomicInteger nativeCalls = new AtomicInteger();
        JsonObject input = json("""
                {
                  "name":"call_function",
                  "arguments":{"function":"another.function","arguments":{}}
                }
                """);

        OperationResult result = harness.invokeNative("inv-recursive", 1, input,
                        (name, arguments) -> {
                            nativeCalls.incrementAndGet();
                            return OperationHandle.completed(new OperationResult("ok", new JsonObject()));
                        })
                .completion().toCompletableFuture().join();

        assertThat(result.status()).isEqualTo("invalid");
        assertThat(result.output().get("error_code").getAsString())
                .isEqualTo("invalid_native_tool_request");
        assertThat(nativeCalls).hasValue(0);
    }

    @Test
    void operationAuditUsesFunctionIdentityInsteadOfTheLegacyToolField() {
        List<String> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        Harness harness = new Harness(new AuditLogger(logger));

        harness.invokeNative("inv-audit", 1,
                        json("{\"name\":\"call_function\",\"arguments\":{}}"),
                        (name, arguments) -> OperationHandle.completed(
                                new OperationResult("ok", new JsonObject())))
                .completion().toCompletableFuture().join();

        assertThat(records).isNotEmpty().allSatisfy(record -> {
            assertThat(record).contains("function=\"player.effect.give\"");
            assertThat(record).doesNotContain(" tool=");
        });
    }

    private static JsonObject confirmInput() {
        return json("""
                {"player":"Alice","interaction":{"type":"confirm","title":"T","message":"M"}}
                """);
    }

    private static void assertPlayerOffline(OperationResult result) {
        assertThat(result.status()).isEqualTo("player_offline");
        assertThat(result.output().keySet()).containsExactly("value", "error_code", "message");
        assertThat(result.output().get("value").isJsonNull()).isTrue();
        assertThat(result.output().get("error_code").getAsString()).isEqualTo("player_offline");
    }

    private static JsonObject json(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    private static AuditLogger quietAudit() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new AuditLogger(logger);
    }

    private static final class Harness {
        private final FakeRuntime runtime = new FakeRuntime();
        private final InteractionManager interactions =
                new InteractionManager((delay, action) -> () -> { });
        private final JavaScriptOperationRouter router;

        private Harness() {
            this(quietAudit());
        }

        private Harness(AuditLogger audit) {
            router = new JavaScriptOperationRouter(runtime, interactions,
                    new ScriptCommandDispatcher(runtime, audit), audit, ignored -> true);
        }

        private OperationHandle invoke(String invocationId, int sequence, JsonObject input) {
            OperationCall call = new OperationCall(invocationId, "player.effect.give",
                    "sha256:" + "0".repeat(64), sequence, "approval.request", input);
            return router.host(new JavaScriptOperationRouter.InvocationActor(TURN_PLAYER, "TurnPlayer"),
                    (name, arguments) -> OperationHandle.completed(
                            new OperationResult("ok", new JsonObject())))
                    .invoke(call);
        }

        private OperationHandle invokeNative(
                String invocationId,
                int sequence,
                JsonObject input,
                JavaScriptOperationRouter.NativeInvoker nativeInvoker
        ) {
            OperationCall call = new OperationCall(invocationId, "player.effect.give",
                    "sha256:" + "0".repeat(64), sequence, "native_tool.call", input);
            return router.host(new JavaScriptOperationRouter.InvocationActor(TURN_PLAYER, "TurnPlayer"),
                    nativeInvoker).invoke(call);
        }
    }

    private static final class FakeRuntime implements CommandRuntime {
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private final AtomicInteger promptCalls = new AtomicInteger();
        private Optional<OnlinePlayer> player = Optional.of(TARGET);
        private boolean promptSent = true;
        private OnlinePlayer promptTarget;
        private String promptToken;
        private InteractionManager.Interaction promptInteraction;

        @Override
        public CompletionStage<Optional<OnlinePlayer>> findOnlinePlayer(String nameOrUuid) {
            lookupCalls.incrementAndGet();
            return CompletableFuture.completedFuture(player);
        }

        @Override
        public CompletionStage<CommandDispatchResult> executeConsole(String command) {
            return CompletableFuture.completedFuture(CommandDispatchResult.dispatchRejected());
        }

        @Override
        public CompletionStage<CommandDispatchResult> executePlayer(OnlinePlayer target, String command) {
            return CompletableFuture.completedFuture(CommandDispatchResult.dispatchRejected());
        }

        @Override
        public CompletionStage<Boolean> send(OnlinePlayer target, String messageKey,
                                             Map<String, String> values) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }

        @Override
        public CompletionStage<Boolean> sendInteractionPrompt(
                OnlinePlayer target, String token, InteractionManager.Interaction interaction) {
            promptCalls.incrementAndGet();
            promptTarget = target;
            promptToken = token;
            promptInteraction = interaction;
            return CompletableFuture.completedFuture(promptSent);
        }
    }
}
