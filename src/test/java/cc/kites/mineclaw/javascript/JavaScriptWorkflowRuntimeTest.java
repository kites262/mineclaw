package cc.kites.mineclaw.javascript;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JavaScriptWorkflowRuntimeTest {
    @Test
    void sourceValidationAllowsAFullMinuteForColdEngineStartup() {
        assertThat(JavaScriptWorkflowRuntime.SOURCE_VALIDATION_TIMEOUT_MILLIS).isEqualTo(60_000L);
    }

    @Test
    void validatesSyntaxEntryVersionAndStableSourceHash() {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            SourceValidation first = runtime.validateSource("example", 1, """
                    async function onCall(ctx, api) {
                      return {status: "ok", output: {version: api.version}};
                    }
                    """);
            SourceValidation second = runtime.validateSource("other_name", 1, """
                    async function onCall(ctx, api) {
                      return {status: "ok", output: {version: api.version}};
                    }
                    """);

            assertThat(first.valid()).isTrue();
            assertThat(second.valid()).isTrue();
            assertThat(first.script().orElseThrow().functionName()).isEqualTo("example");
            assertThat(second.script().orElseThrow().functionName()).isEqualTo("other_name");
            assertThat(first.script().orElseThrow().scriptHash())
                    .isEqualTo(second.script().orElseThrow().scriptHash())
                    .startsWith("sha256:");
            assertThat(runtime.validateSource("LegacyTool", 1, "function onCall() {}").diagnostic()
                    .orElseThrow().code()).isEqualTo("invalid_function_name");
            assertThat(runtime.validateSource("bad", 1, "function onCall( {").diagnostic()
                    .orElseThrow().code()).isEqualTo("javascript_syntax_error");
            assertThat(runtime.validateSource("missing", 1, "function helper() {}").diagnostic()
                    .orElseThrow().code()).isEqualTo("missing_on_call");
            assertThat(runtime.validateSource("version", 2, "function onCall() {}").diagnostic()
                    .orElseThrow().code()).isEqualTo("unsupported_api_version");
        }
    }

    @Test
    void sourceValidationDoesNotReuseTheInvocationSyncSegmentBudget() {
        JavaScriptLimits limits = new JavaScriptLimits(
                65_536, 8, 2, 2, 1L, 5_000L, 32_768, 16, 2_048);
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(limits)) {
            SourceValidation validation = runtime.validateSource("cold.start", 1, """
                    async function onCall(ctx, api) {
                      return {status: "ok", output: {version: api.version}};
                    }
                    """);

            assertThat(validation.valid()).isTrue();
        }
    }

    @Test
    void exposesOnlyFrozenPlainContextAndApiWithoutHostOrIoGlobals() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            PreparedScript script = prepare(runtime, """
                    function onCall(ctx, api) {
                      let ctxMutation = "accepted";
                      let apiMutation = "accepted";
                      try { ctx.args.value = "changed"; } catch (_) { ctxMutation = "blocked"; }
                      try { api.version = 99; } catch (_) { apiMutation = "blocked"; }
                      return {
                        status: "ok",
                        output: {
                          player: ctx.player.name,
                          value: ctx.args.value,
                          ctx_mutation: ctxMutation,
                          api_mutation: apiMutation,
                          api_version: api.version,
                          function_name: ctx.invocation.function_name,
                          has_tool_name: "tool_name" in ctx.invocation,
                          java: typeof Java,
                          packages: typeof Packages,
                          polyglot: typeof Polyglot,
                          load: typeof load,
                          fetch: typeof fetch,
                          worker: typeof Worker,
                          proxy: typeof Proxy
                        }
                      };
                    }
                    """);
            JsonObject arguments = new JsonObject();
            arguments.addProperty("value", "original");

            ScriptResult result = runtime.execute(script,
                    request(arguments, Set.of()), call -> {
                        throw new AssertionError("host must not be called");
                    }).result().get(5, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.output().get("player").getAsString()).isEqualTo("ExactPlayer");
            assertThat(result.output().get("value").getAsString()).isEqualTo("original");
            assertThat(result.output().get("ctx_mutation").getAsString()).isEqualTo("blocked");
            assertThat(result.output().get("api_mutation").getAsString()).isEqualTo("blocked");
            assertThat(result.output().get("api_version").getAsInt()).isEqualTo(1);
            assertThat(result.output().get("function_name").getAsString())
                    .isEqualTo("test.function");
            assertThat(result.output().get("has_tool_name").getAsBoolean()).isFalse();
            for (String name : List.of("java", "packages", "polyglot", "load", "fetch",
                    "worker", "proxy")) {
                assertThat(result.output().get(name).getAsString()).isEqualTo("undefined");
            }
        }
    }

    @Test
    void rejectsForeignThenablesWithoutAssimilatingThem() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            PreparedScript script = prepare(runtime, """
                    function onCall() {
                      return {
                        then(resolve) {
                          resolve({status: "ok", output: {assimilated: true}});
                        }
                      };
                    }
                    """);

            ScriptResult result = execute(runtime, script);

            assertThat(result.status()).isEqualTo("invalid");
            assertThat(result.output().has("assimilated")).isFalse();
        }
    }

    @Test
    void mapsEveryInvalidFinalValueToTheStableInvalidScriptResult() throws Exception {
        List<String> invalidSources = List.of(
                "function onCall() { return undefined; }",
                "function onCall() { return {status: 'ok', output: {}, extra: true}; }",
                "function onCall() { return {status: 'ok', output: {function: 'spoof'}}; }",
                "function onCall() { return {status: 'denied', output: {message: 'missing code'}}; }",
                "function onCall() { return {status: 'invalid', output: {error_code: 'BAD', message: 'bad'}}; }",
                "function onCall() { return {status: 'invalid', output: {error_code: 'bad', message: '  '}}; }",
                "function onCall() { return {status: 'ok', output: {error_code: 'none'}}; }"
        );
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            for (String source : invalidSources) {
                ScriptResult result = execute(runtime, prepare(runtime, source));
                assertThat(result.status()).isEqualTo("invalid");
                assertThat(result.output().keySet()).containsExactly("error_code", "message");
                assertThat(result.output().get("error_code").getAsString())
                        .isEqualTo("invalid_script_result");
            }
        }
    }

    @Test
    void keepsExceptionsAndPromiseRejectionsTerminal() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            for (String source : List.of(
                    "function onCall() { throw new Error('sync failure'); }",
                    "async function onCall() { throw new Error('async failure'); }")) {
                ScriptResult result = execute(runtime, prepare(runtime, source));
                assertThat(result.status()).isEqualTo("terminal_error");
                assertThat(result.output().get("error_code").getAsString())
                        .isEqualTo("script_exception");
            }
        }
    }

    @Test
    void rejectsCallFunctionThroughNativeToolCallBeforeTheHostBoundary() throws Exception {
        AtomicInteger hostCalls = new AtomicInteger();
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            PreparedScript script = prepare(runtime, """
                    async function onCall(ctx, api) {
                      const attempted = await api.invoke({
                        action: "native_tool.call",
                        input: {
                          name: "call_function",
                          arguments: {function: "another.function", arguments: {}}
                        }
                      });
                      return {
                        status: "ok",
                        output: {
                          operation_status: attempted.status,
                          error_code: attempted.output.error_code
                        }
                      };
                    }
                    """);

            ScriptResult result = runtime.execute(script,
                    request(new JsonObject(), Set.of("native_tool.call.call_function")), call -> {
                        hostCalls.incrementAndGet();
                        return OperationHandle.completed(result("ok", "unexpected", true));
                    }).result().get(5, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.output().get("operation_status").getAsString()).isEqualTo("invalid");
            assertThat(result.output().get("error_code").getAsString())
                    .isEqualTo("invalid_action_input");
            assertThat(hostCalls).hasValue(0);
        }
    }

    @Test
    void capturedIntrinsicsSurvivePromiseAndObjectGlobalReplacement() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            PreparedScript script = prepare(runtime, """
                    globalThis.Object = undefined;
                    globalThis.Promise = undefined;
                    globalThis.Number = undefined;
                    globalThis.String = undefined;
                    globalThis.Array = undefined;
                    globalThis.WeakSet = undefined;
                    globalThis.JSON = undefined;
                    globalThis.Reflect = undefined;
                    async function onCall(ctx, api) {
                      const decision = await api.invoke({
                        action: "approval.request",
                        input: {
                          player: "ExactPlayer",
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        }
                      });
                      return {
                        status: "ok",
                        output: {decision: decision.status, player: ctx.player.name}
                      };
                    }
                    """);

            ScriptResult result = runtime.execute(script,
                    request(new JsonObject(), Set.of("approval.request")), call ->
                            OperationHandle.completed(result("approved", "value", true)))
                    .result().get(5, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.output().get("decision").getAsString()).isEqualTo("approved");
            assertThat(result.output().get("player").getAsString()).isEqualTo("ExactPlayer");
        }
    }

    @Test
    void bridgesConcurrentPromisesBackThroughTheSerializedInvocationQueue() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 8_000L)) {
            PreparedScript script = prepare(runtime, """
                    async function onCall(ctx, api) {
                      const results = await Promise.all(["A", "B"].map(label => api.invoke({
                        action: "approval.request",
                        input: {
                          player: "ExactPlayer",
                          interaction: {type: "confirm", title: "Confirm", message: label}
                        }
                      })));
                      return {
                        status: "ok",
                        output: {values: results.map(result => result.output.value)}
                      };
                    }
                    """);
            List<CompletableFuture<OperationResult>> completions = new ArrayList<>();
            CountDownLatch registered = new CountDownLatch(2);
            OperationHost host = call -> {
                CompletableFuture<OperationResult> completion = new CompletableFuture<>();
                synchronized (completions) {
                    completions.add(completion);
                }
                registered.countDown();
                return new OperationHandle(completion, () -> { });
            };
            InvocationHandle invocation = runtime.execute(script,
                    request(new JsonObject(), Set.of("approval.request")), host);

            assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(invocation.result()).isNotDone();
            Thread first = Thread.ofPlatform().start(() -> completions.get(0).complete(
                    result("approved", "value", "A")));
            Thread second = Thread.ofPlatform().start(() -> completions.get(1).complete(
                    result("approved", "value", "B")));
            first.join();
            second.join();

            ScriptResult result = invocation.result().get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.output().getAsJsonArray("values"))
                    .extracting(element -> element.getAsString())
                    .containsExactly("A", "B");
            assertThat(runtime.isActive(invocation.invocationId())).isFalse();
        }
    }

    @Test
    void mapsOversizedHostOperationResultsToTheResourceLimitCode() throws Exception {
        JavaScriptLimits limits = new JavaScriptLimits(
                65_536, 8, 2, 2, 3_000L, 5_000L, 32_768, 16, 16);
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(limits)) {
            PreparedScript script = prepare(runtime, """
                    async function onCall(ctx, api) {
                      await api.invoke({
                        action: "approval.request",
                        input: {
                          player: ctx.player.name,
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        }
                      });
                      return {status: "ok", output: {unexpected: true}};
                    }
                    """);
            ScriptResult outcome = runtime.execute(script,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        JsonObject output = new JsonObject();
                        for (int index = 0; index < 20; index++) {
                            output.addProperty("field_" + index, index);
                        }
                        return OperationHandle.completed(new OperationResult("approved", output));
                    }).result().get(5, TimeUnit.SECONDS);

            assertThat(outcome.status()).isEqualTo("terminal_error");
            assertThat(outcome.output().get("error_code").getAsString())
                    .isEqualTo("script_resource_limit");
        }
    }

    @Test
    void rejectsUnknownActionsButResolvesKnownInvalidAndDeniedRequests() throws Exception {
        AtomicInteger hostCalls = new AtomicInteger();
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            PreparedScript script = prepare(runtime, """
                    async function onCall(ctx, api) {
                      let unknown;
                      try {
                        await api.invoke({action: "unknown.action", input: {}});
                      } catch (failure) {
                        unknown = {code: failure.code, keys: Object.keys(failure).sort()};
                      }
                      const malformed = await api.invoke({
                        action: "approval.request",
                        input: {
                          player: "ExactPlayer",
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        },
                        extra: true
                      });
                      const invalid = await api.invoke({action: "approval.request", input: {}});
                      const denied = await api.invoke({
                        action: "approval.request",
                        input: {
                          player: "ExactPlayer",
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        }
                      });
                      return {status: "ok", output: {unknown, malformed, invalid, denied}};
                    }
                    """);

            ScriptResult result = runtime.execute(script,
                    request(new JsonObject(), Set.of()), call -> {
                        hostCalls.incrementAndGet();
                        return OperationHandle.completed(result("approved", "value", true));
                    }).result().get(5, TimeUnit.SECONDS);

            assertThat(hostCalls).hasValue(0);
            JsonObject output = result.output();
            assertThat(output.getAsJsonObject("unknown").get("code").getAsString())
                    .isEqualTo("unknown_api_action");
            assertThat(output.getAsJsonObject("unknown").getAsJsonArray("keys"))
                    .extracting(element -> element.getAsString())
                    .containsExactly("code", "message");
            assertThat(output.getAsJsonObject("malformed").get("status").getAsString())
                    .isEqualTo("invalid");
            assertThat(output.getAsJsonObject("invalid").get("status").getAsString())
                    .isEqualTo("invalid");
            assertThat(output.getAsJsonObject("denied").get("status").getAsString())
                    .isEqualTo("denied");
        }
    }

    @Test
    void tinyResultBudgetsStillAllowRuntimeGeneratedInvalidOperationEnvelopes() throws Exception {
        JavaScriptLimits limits = new JavaScriptLimits(
                65_536, 1, 1, 1, 3_000L, 5_000L, 32_768, 1, 1);
        AtomicInteger hostCalls = new AtomicInteger();
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(limits)) {
            PreparedScript script = prepare(runtime, """
                    async function onCall(ctx, api) {
                      const operation = await api.invoke({
                        action: "approval.request",
                        input: {}
                      });
                      return {status: "ok", output: {operation_status: operation.status}};
                    }
                    """);
            ScriptResult outcome = runtime.execute(script,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        hostCalls.incrementAndGet();
                        return OperationHandle.completed(result("approved", "value", true));
                    }).result().get(5, TimeUnit.SECONDS);

            assertThat(outcome.status()).isEqualTo("ok");
            assertThat(outcome.output().get("operation_status").getAsString()).isEqualTo("invalid");
            assertThat(hostCalls).hasValue(0);
        }
    }

    @Test
    void isolatesGlobalsAndRejectsAccessorsWithoutInvokingThem() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 5_000L)) {
            PreparedScript isolated = prepare(runtime, """
                    function onCall() {
                      globalThis.mineclawCounter = (globalThis.mineclawCounter || 0) + 1;
                      return {status: "ok", output: {counter: globalThis.mineclawCounter}};
                    }
                    """);
            ScriptResult first = execute(runtime, isolated);
            ScriptResult second = execute(runtime, isolated);
            assertThat(first.output().get("counter").getAsInt()).isEqualTo(1);
            assertThat(second.output().get("counter").getAsInt()).isEqualTo(1);

            PreparedScript accessor = prepare(runtime, """
                    function onCall() {
                      const output = {};
                      Object.defineProperty(output, "danger", {
                        enumerable: true,
                        get() { while (true) {} }
                      });
                      return {status: "ok", output};
                    }
                    """);
            ScriptResult invalid = execute(runtime, accessor);
            assertThat(invalid.status()).isEqualTo("invalid");
            assertThat(invalid.output().get("error_code").getAsString())
                    .isEqualTo("invalid_script_result");
        }
    }

    @Test
    void enforcesSynchronousAndWorkflowTimeoutsAndCancelsOwnedOperations() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(100L, 350L)) {
            PreparedScript loop = prepare(runtime, """
                    function onCall() { while (true) {} }
                    """);
            ScriptResult timedOut = runtime.execute(loop,
                    request(new JsonObject(), Set.of()), call -> {
                        throw new AssertionError("host must not be called");
                    }).result().get(5, TimeUnit.SECONDS);
            assertThat(timedOut.status()).isEqualTo("terminal_error");
            assertThat(timedOut.output().get("error_code").getAsString())
                    .isEqualTo("script_resource_limit");

            PreparedScript waits = prepare(runtime, """
                    async function onCall(ctx, api) {
                      await api.invoke({
                        action: "approval.request",
                        input: {
                          player: "ExactPlayer",
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        }
                      });
                      return {status: "ok", output: {unexpected: true}};
                    }
                    """);
            AtomicInteger cancellations = new AtomicInteger();
            InvocationHandle waiting = runtime.execute(waits,
                    request(new JsonObject(), Set.of("approval.request")), call ->
                            new OperationHandle(new CompletableFuture<>(), cancellations::incrementAndGet));
            ScriptResult workflowTimeout = waiting.result().get(5, TimeUnit.SECONDS);
            assertThat(workflowTimeout.status()).isEqualTo("terminal_error");
            assertThat(workflowTimeout.output().get("error_code").getAsString())
                    .isEqualTo("script_resource_limit");
            awaitValue(cancellations, 1, Duration.ofSeconds(2));
        }
    }

    @Test
    void mapsFinalResultSizeDepthAndMemberLimitsToTerminalResourceErrors() throws Exception {
        assertResultResourceLimit(new JavaScriptLimits(
                65_536, 8, 2, 2, 3_000L, 5_000L, 180, 16, 2_048), """
                function onCall() {
                  return {status: "ok", output: {text: "x".repeat(1_000)}};
                }
                """);
        assertResultResourceLimit(new JavaScriptLimits(
                65_536, 8, 2, 2, 3_000L, 5_000L, 32_768, 2, 2_048), """
                function onCall() {
                  return {status: "ok", output: {a: {b: {c: true}}}};
                }
                """);
        assertResultResourceLimit(new JavaScriptLimits(
                65_536, 8, 2, 2, 3_000L, 5_000L, 32_768, 16, 16), """
                function onCall() {
                  return {status: "ok", output: {
                    a: 1, b: 2, c: 3, d: 4, e: 5, f: 6, g: 7, h: 8, i: 9,
                    j: 10, k: 11, l: 12, m: 13, n: 14, o: 15, p: 16, q: 17
                  }};
                }
                """);
    }

    @Test
    void appliesResourceLimitsToArgumentsWithoutChargingForTheFixedContextEnvelope() throws Exception {
        JavaScriptLimits limits = new JavaScriptLimits(
                65_536, 8, 2, 2, 3_000L, 5_000L, 32_768, 2, 4);
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(limits)) {
            PreparedScript script = prepare(runtime, """
                    function onCall(ctx) {
                      return {status: "ok", output: {value: ctx.args.d}};
                    }
                    """);
            JsonObject exact = new JsonObject();
            exact.addProperty("a", 1);
            exact.addProperty("b", 2);
            exact.addProperty("c", 3);
            exact.addProperty("d", 4);
            ScriptResult accepted = runtime.execute(script, request(exact, Set.of()), call -> {
                throw new AssertionError("host must not be called");
            }).result().get(5, TimeUnit.SECONDS);
            assertThat(accepted.status()).isEqualTo("ok");
            assertThat(accepted.output().get("value").getAsInt()).isEqualTo(4);

            JsonObject tooMany = exact.deepCopy();
            tooMany.addProperty("e", 5);
            assertArgumentResourceLimit(runtime, script, tooMany);

            JsonObject tooDeep = new JsonObject();
            JsonObject first = new JsonObject();
            JsonObject second = new JsonObject();
            JsonObject third = new JsonObject();
            third.addProperty("value", true);
            second.add("third", third);
            first.add("second", second);
            tooDeep.add("first", first);
            assertArgumentResourceLimit(runtime, script, tooDeep);
        }
    }

    @Test
    void finalSettlementClosesAdmissionAndCancelsUnfinishedRaceParticipants() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = runtime(3_000L, 8_000L)) {
            PreparedScript microtask = prepare(runtime, """
                    function onCall(ctx, api) {
                      const finished = Promise.resolve({status: "ok", output: {done: true}});
                      finished.then(() => Promise.resolve().then(() => api.invoke({
                        action: "approval.request",
                        input: {
                          player: ctx.player.name,
                          interaction: {type: "confirm", title: "Late", message: "Must not run"}
                        }
                      }).catch(() => {})));
                      return finished;
                    }
                    """);
            AtomicInteger lateHostCalls = new AtomicInteger();
            ScriptResult completed = runtime.execute(microtask,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        lateHostCalls.incrementAndGet();
                        return OperationHandle.completed(result("approved", "value", true));
                    }).result().get(5, TimeUnit.SECONDS);
            assertThat(completed.status()).isEqualTo("ok");
            assertThat(lateHostCalls).hasValue(0);

            PreparedScript race = prepare(runtime, """
                    function onCall(ctx, api) {
                      const unfinished = api.invoke({
                        action: "approval.request",
                        input: {
                          player: ctx.player.name,
                          interaction: {type: "confirm", title: "Race", message: "Cancel loser"}
                        }
                      });
                      return Promise.race([
                        Promise.resolve({status: "ok", output: {winner: "immediate"}}),
                        unfinished.then(() => ({status: "ok", output: {winner: "operation"}}))
                      ]);
                    }
                    """);
            AtomicInteger cancellations = new AtomicInteger();
            CountDownLatch registered = new CountDownLatch(1);
            ScriptResult winner = runtime.execute(race,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        registered.countDown();
                        return new OperationHandle(new CompletableFuture<>(),
                                cancellations::incrementAndGet);
                    }).result().get(5, TimeUnit.SECONDS);
            assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(winner.status()).isEqualTo("ok");
            assertThat(winner.output().get("winner").getAsString()).isEqualTo("immediate");
            awaitValue(cancellations, 1, Duration.ofSeconds(2));
        }
    }

    @Test
    void cancellationImmediatelyClosesTheSideEffectGateAndReconfigureUsesNewLimits() throws Exception {
        JavaScriptWorkflowRuntime runtime = runtime(3_000L, 8_000L);
        try (runtime) {
            PreparedScript waits = prepare(runtime, """
                    async function onCall(ctx, api) {
                      await api.invoke({
                        action: "approval.request",
                        input: {
                          player: "ExactPlayer",
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        }
                      });
                      return {status: "ok", output: {unexpected: true}};
                    }
                    """);
            CountDownLatch registered = new CountDownLatch(1);
            AtomicInteger cancellations = new AtomicInteger();
            InvocationHandle invocation = runtime.execute(waits,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        registered.countDown();
                        return new OperationHandle(new CompletableFuture<>(), cancellations::incrementAndGet);
                    });
            assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.isActive(invocation.invocationId())).isTrue();
            invocation.cancel();
            assertThat(runtime.isActive(invocation.invocationId())).isFalse();
            assertThat(invocation.result().get(5, TimeUnit.SECONDS).status()).isEqualTo("cancelled");
            awaitValue(cancellations, 1, Duration.ofSeconds(2));

            runtime.suspendForReload();
            assertThat(runtime.validateSource("suspended", 1, "function onCall() {}").diagnostic()
                    .orElseThrow().code()).isEqualTo("javascript_runtime_suspended");
            assertThatCode(() -> runtime.execute(waits,
                    request(new JsonObject(), Set.of("approval.request")), call ->
                            OperationHandle.completed(result("approved", "value", true))))
                    .isInstanceOf(IllegalStateException.class);

            JavaScriptLimits tiny = new JavaScriptLimits(8, 4, 2, 2,
                    3_000L, 5_000L, 1_024, 8, 64);
            runtime.reconfigure(tiny);
            assertThat(runtime.validateSource("large", 1, "function onCall() {}").diagnostic()
                    .orElseThrow().code()).isEqualTo("source_too_large");
        }
        assertThatCode(() -> runtime.reconfigure(JavaScriptLimits.defaults()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void auditsSerializedResumeAndDiscardedLateCompletionEvents() throws Exception {
        List<JavaScriptWorkflowRuntime.RuntimeEvent> events = new CopyOnWriteArrayList<>();
        JavaScriptLimits limits = new JavaScriptLimits(
                65_536, 8, 2, 2, 3_000L, 8_000L, 32_768, 16, 2_048);
        try (JavaScriptWorkflowRuntime runtime =
                     new JavaScriptWorkflowRuntime(limits, events::add)) {
            PreparedScript waits = prepare(runtime, """
                    async function onCall(ctx, api) {
                      const decision = await api.invoke({
                        action: "approval.request",
                        input: {
                          player: ctx.player.name,
                          interaction: {type: "confirm", title: "Confirm", message: "Continue?"}
                        }
                      });
                      return {status: "ok", output: {decision: decision.status}};
                    }
                    """);

            CompletableFuture<OperationResult> firstHost = new CompletableFuture<>();
            CountDownLatch firstRegistered = new CountDownLatch(1);
            InvocationHandle resumed = runtime.execute(waits,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        firstRegistered.countDown();
                        return new OperationHandle(firstHost, () -> { });
                    });
            assertThat(firstRegistered.await(5, TimeUnit.SECONDS)).isTrue();
            firstHost.complete(result("approved", "value", true));
            assertThat(resumed.result().get(5, TimeUnit.SECONDS).status()).isEqualTo("ok");
            awaitEvent(events, "resumed");

            CompletableFuture<OperationResult> lateHost = new CompletableFuture<>();
            CountDownLatch lateRegistered = new CountDownLatch(1);
            InvocationHandle cancelled = runtime.execute(waits,
                    request(new JsonObject(), Set.of("approval.request")), call -> {
                        lateRegistered.countDown();
                        return new OperationHandle(lateHost, () -> { });
                    });
            assertThat(lateRegistered.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.cancel();
            assertThat(cancelled.result().get(5, TimeUnit.SECONDS).status()).isEqualTo("cancelled");
            lateHost.complete(result("approved", "value", true));
            awaitEvent(events, "late_completion");
            assertThat(events).anySatisfy(event -> {
                assertThat(event.functionName()).isEqualTo("test.function");
                assertThat(event.phase()).isEqualTo("late_completion");
                assertThat(event.status()).isEqualTo("discarded");
                assertThat(event.reason()).isNotBlank();
            });
        }
    }

    private static JavaScriptWorkflowRuntime runtime(long syncMillis, long workflowMillis) {
        return new JavaScriptWorkflowRuntime(new JavaScriptLimits(
                65_536, 64, 16, 16, syncMillis, workflowMillis,
                32_768, 16, 2_048));
    }

    private static void assertResultResourceLimit(JavaScriptLimits limits, String source) throws Exception {
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(limits)) {
            ScriptResult result = execute(runtime, prepare(runtime, source));
            assertThat(result.status()).isEqualTo("terminal_error");
            assertThat(result.output().get("error_code").getAsString())
                    .isEqualTo("script_resource_limit");
        }
    }

    private static void assertArgumentResourceLimit(
            JavaScriptWorkflowRuntime runtime,
            PreparedScript script,
            JsonObject arguments
    ) throws Exception {
        AtomicInteger hostCalls = new AtomicInteger();
        ScriptResult result = runtime.execute(script, request(arguments, Set.of()), call -> {
            hostCalls.incrementAndGet();
            return OperationHandle.completed(result("invalid", "unexpected", true));
        }).result().get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo("terminal_error");
        assertThat(result.output().get("error_code").getAsString())
                .isEqualTo("script_resource_limit");
        assertThat(hostCalls).hasValue(0);
    }

    private static PreparedScript prepare(JavaScriptWorkflowRuntime runtime, String source) {
        SourceValidation validation = runtime.validateSource("test.function", 1, source);
        assertThat(validation.diagnostic()).isEmpty();
        return validation.script().orElseThrow();
    }

    private static ScriptResult execute(JavaScriptWorkflowRuntime runtime, PreparedScript script)
            throws Exception {
        return runtime.execute(script, request(new JsonObject(), Set.of()), call -> {
            throw new AssertionError("host must not be called");
        }).result().get(5, TimeUnit.SECONDS);
    }

    private static InvocationRequest request(JsonObject arguments, Set<String> capabilities) {
        return new InvocationRequest(UUID.randomUUID().toString(), "ExactPlayer", arguments, capabilities);
    }

    private static OperationResult result(String status, String name, String value) {
        JsonObject output = new JsonObject();
        output.addProperty(name, value);
        return new OperationResult(status, output);
    }

    private static OperationResult result(String status, String name, boolean value) {
        JsonObject output = new JsonObject();
        output.addProperty(name, value);
        return new OperationResult(status, output);
    }

    private static void awaitValue(AtomicInteger value, int expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (value.get() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(value).hasValue(expected);
    }

    private static void awaitEvent(
            List<JavaScriptWorkflowRuntime.RuntimeEvent> events,
            String phase
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (events.stream().noneMatch(event -> event.phase().equals(phase))
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(events).anyMatch(event -> event.phase().equals(phase));
    }
}
