package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.commandexec.CommandRuntime;
import cc.kites.mineclaw.commandexec.ScriptCommandDispatcher;
import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.function.FunctionCatalog;
import cc.kites.mineclaw.function.FunctionCatalogLoader;
import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.javascript.JavaScriptLimits;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.workspace.ToolCatalog;
import cc.kites.mineclaw.workspace.ToolDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDispatcherTest {
    @TempDir
    Path workspace;

    @Test
    void preservesWorkspaceEscapeDenialWhenReadIsDispatchedThroughCatalogDefinition() throws IOException {
        Files.writeString(workspace.resolve(".env"), "MINECLAW_API_KEY=secret");
        ToolDispatcher dispatcher = dispatcher(new AtomicInteger());

        ToolExecution execution = dispatcher.execute(readDefinition(), "{\"path\":\"../.env\"}", turnPlayer(),
                MineclawConfig.defaults()).join();

        assertThat(execution.pending()).isFalse();
        assertThat(execution.immediate().status()).isEqualTo("denied");
        assertThat(execution.immediate().output())
                .extracting(
                        output -> output.get("status").getAsString(),
                        output -> output.get("error_code").getAsString())
                .containsExactly("denied", "path_escape");
    }

    @Test
    void returnsStructuredInvalidResultWithoutInvokingHandlerOnTypeMismatch() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ToolDispatcher dispatcher = dispatcher(calls);

        ToolExecution execution = dispatcher.execute(definition(), "{\"command\":42}", turnPlayer(),
                MineclawConfig.defaults()).join();

        assertThat(calls).hasValue(0);
        assertThat(execution.pending()).isFalse();
        assertThat(execution.immediate().status()).isEqualTo("invalid");
        assertThat(execution.immediate().output())
                .extracting(
                        output -> output.get("error_code").getAsString(),
                        output -> output.get("path").getAsString(),
                        output -> output.get("message").getAsString())
                .containsExactly("invalid_arguments", "$.command", "expected string but found integer");

        dispatcher.execute(definition(), "{\"command\":\"say hello\"}", turnPlayer(),
                MineclawConfig.defaults()).join();
        assertThat(calls).hasValue(1);
    }

    @Test
    void runtimeUnavailableStillEnforcesTheStrictCallFunctionProtocol() throws IOException {
        ToolDispatcher dispatcher = dispatcher(new AtomicInteger());
        ToolDefinition callFunction = callFunctionDefinition();
        ToolCatalog tools = new ToolCatalog(List.of(callFunction), List.of());
        FunctionCatalog noFunctions = FunctionCatalog.empty(1L, null);

        ToolResult unavailable = dispatcher.execute(tools, noFunctions, callFunction,
                call("known.function", "{}"), "call-unavailable", turnPlayer(),
                MineclawConfig.defaults()).join().immediate();
        assertCallEnvelope(unavailable, "invalid", "known.function", "function_unavailable");

        ToolResult extraField = dispatcher.execute(tools, noFunctions, callFunction,
                "{\"function\":\"known.function\",\"arguments\":{},\"script\":\"ignored\"}",
                "call-extra", turnPlayer(), MineclawConfig.defaults()).join().immediate();
        assertCallEnvelope(extraField, "invalid", "known.function", "invalid_call_arguments");

        ToolResult nonObjectArguments = dispatcher.execute(tools, noFunctions, callFunction,
                "{\"function\":\"known.function\",\"arguments\":\"{}\"}",
                "call-non-object", turnPlayer(), MineclawConfig.defaults()).join().immediate();
        assertCallEnvelope(nonObjectArguments, "invalid", "known.function", "invalid_call_arguments");

        String oversized = "{\"function\":\"known.function\",\"arguments\":{\"value\":\""
                + "x".repeat(MineclawConfig.defaults().functions().maxArgumentChars()) + "\"}}";
        ToolResult tooLarge = dispatcher.execute(tools, noFunctions, callFunction, oversized,
                "call-large", turnPlayer(), MineclawConfig.defaults()).join().immediate();
        assertCallEnvelope(tooLarge, "invalid", null, "invalid_call_arguments");
    }

    @Test
    void reservedCallFunctionNameCannotExecuteAnotherNativeHandler() throws IOException {
        ToolDispatcher dispatcher = dispatcher(new AtomicInteger());
        ToolDefinition malicious = new ToolDefinition(1, "call_function",
                readDefinition().payload(), true,
                ToolDefinition.Status.ENABLED, Optional.empty());

        ToolResult result = dispatcher.execute(new ToolCatalog(List.of(malicious), List.of()),
                FunctionCatalog.empty(9L, null), malicious, call("known.function", "{}"),
                "call-reserved", turnPlayer(), MineclawConfig.defaults()).join().immediate();

        assertCallEnvelope(result, "invalid", "known.function", "function_unavailable");
    }

    @Test
    void auditsCallFunctionCorrelationValidationAndElapsedTime() {
        java.util.ArrayList<String> records = new java.util.ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        CallFunctionHandler handler = new CallFunctionHandler(null, null, new AuditLogger(logger));
        FunctionCatalog catalog = FunctionCatalog.empty(41L, null);

        ToolExecution execution = handler.execute("tool-call-7", call("missing.function", "{}"), catalog,
                turnPlayer(), MineclawConfig.defaults(), (name, arguments, active) -> {
                    throw new AssertionError("unavailable Function must not reach native tools");
                }).join();

        assertCallEnvelope(execution.immediate(), "invalid", "missing.function", "function_unavailable");
        assertThat(records).singleElement().satisfies(line -> assertThat(line)
                .contains("action=\"function.invocation\"", "tool_call_id=\"tool-call-7\"",
                        "function=\"missing.function\"", "catalog_generation=\"41\"",
                        "turn_player=\"Tester(", "phase=\"unavailable\"",
                        "argument_validation=\"not_run\"", "violation_count=\"0\"",
                        "elapsed_ms=\"", "error_code=\"function_unavailable\""));
    }

    @Test
    void validatesFunctionArgumentsBeforeExecutingJavaScriptOrHostOperations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (JavaScriptWorkflowRuntime runtime = javascriptRuntime()) {
            ToolDispatcher dispatcher = javascriptDispatcher(calls, runtime);
            FunctionCatalog functions = functionCatalog(runtime, "workflow", """
                    async function onCall(ctx, api) {
                      await api.invoke({action: "native_tool.call", input: {
                        name: "run_command", arguments: {command: "say unexpected"}
                      }});
                      return {status: "ok", output: {mode: ctx.args.mode}};
                    }
                    """, List.of("native_tool.call.run_command"), enumSchema(), Set.of("run_command"));
            ToolDefinition callFunction = callFunctionDefinition();
            ToolCatalog catalog = new ToolCatalog(List.of(callFunction, definition()), List.of());

            ToolResult invalidArguments = completed(dispatcher.execute(
                    catalog, functions, callFunction,
                    call("workflow", "{\"mode\":\"unsafe\"}"), "call-1",
                    turnPlayer(), MineclawConfig.defaults())
                    .get(5, TimeUnit.SECONDS));

            assertThat(invalidArguments.status()).isEqualTo("invalid");
            JsonObject output = invalidArguments.output().getAsJsonObject("output");
            assertThat(output.get("error_code").getAsString()).isEqualTo("invalid_arguments");
            assertThat(output.getAsJsonArray("violations").get(0).getAsJsonObject()
                    .get("path").getAsString()).isEqualTo("$.mode");
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void unknownDisabledAndInvalidFunctionsAreIndistinguishableExceptForEchoedName() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = javascriptRuntime()) {
            ToolDispatcher dispatcher = javascriptDispatcher(new AtomicInteger(), runtime);
            FunctionCatalog functions = new FunctionCatalogLoader(ignored -> { }, runtime::validateSource,
                    Set.of(), FunctionCatalogLoader.Limits.defaults()).parse("""
                    schema: 1
                    api_version: 1
                    functions:
                      - name: disabled.function
                        description: valid but disabled
                        enabled: false
                        capabilities: []
                        parameters: {type: object, properties: {}, additionalProperties: false}
                        on_call: 'function onCall() { return {status: "ok", output: {}}; }'
                      - name: invalid.function
                        description: invalid schema
                        enabled: true
                        capabilities: []
                        parameters: {type: string}
                        on_call: 'function onCall() { return {status: "ok", output: {}}; }'
                    """);
            ToolDefinition callFunction = callFunctionDefinition();
            ToolCatalog tools = new ToolCatalog(List.of(callFunction), List.of());

            for (String name : List.of("unknown.function", "disabled.function", "invalid.function")) {
                ToolResult result = completed(dispatcher.execute(tools, functions, callFunction,
                        call(name, "{}"), "call-" + name, turnPlayer(), MineclawConfig.defaults())
                        .get(5, TimeUnit.SECONDS));
                assertCallEnvelope(result, "invalid", name, "function_unavailable");
                assertThat(result.output().getAsJsonObject("output").keySet())
                        .containsExactlyInAnyOrder("error_code", "message");
            }
        }
    }

    @Test
    void nativeCallsResolveOnlyAgainstTheSuppliedCatalogSnapshot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (JavaScriptWorkflowRuntime runtime = javascriptRuntime()) {
            ToolDispatcher dispatcher = javascriptDispatcher(calls, runtime);
            String source = """
                    async function onCall(ctx, api) {
                      const result = await api.invoke({
                        action: "native_tool.call",
                        input: {name: "run_command", arguments: {command: "say snapshot"}}
                      });
                      return {status: "ok", output: {
                        nativeStatus: result.status,
                        errorCode: result.output.error_code || "none"
                      }};
                    }
                    """;
            FunctionCatalog functions = functionCatalog(runtime, "workflow", source,
                    List.of("native_tool.call.run_command"), emptySchema(), Set.of("run_command"));
            ToolDefinition callFunction = callFunctionDefinition();
            ToolDefinition nativeTool = definition();

            ToolResult present = completed(dispatcher.execute(
                    new ToolCatalog(List.of(callFunction, nativeTool), List.of()), functions,
                    callFunction, call("workflow", "{}"), "call-present",
                    turnPlayer(), MineclawConfig.defaults()).get(5, TimeUnit.SECONDS));
            assertThat(present.output().getAsJsonObject("output"))
                    .extracting(
                            output -> output.get("nativeStatus").getAsString(),
                            output -> output.get("errorCode").getAsString())
                    .containsExactly("ok", "none");
            assertThat(calls).hasValue(1);

            ToolResult absent = completed(dispatcher.execute(
                    new ToolCatalog(List.of(callFunction), List.of()), functions,
                    callFunction, call("workflow", "{}"), "call-absent",
                    turnPlayer(), MineclawConfig.defaults()).get(5, TimeUnit.SECONDS));
            assertThat(absent.output().getAsJsonObject("output"))
                    .extracting(
                            output -> output.get("nativeStatus").getAsString(),
                            output -> output.get("errorCode").getAsString())
                    .containsExactly("invalid", "native_tool_unavailable");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void validToolDefinitionCannotRepresentAHandlerAlias() {
        assertThatThrownBy(() -> new ToolDefinition(2, "nested",
                callFunctionDefinition().payload(), true,
                ToolDefinition.Status.ENABLED, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered handler");
    }

    @Test
    void nativeToolCallCannotReadProtectedFunctionsCatalog() throws Exception {
        Files.writeString(workspace.resolve("functions.yml"), "TOP_SECRET_FUNCTION_SOURCE");
        try (JavaScriptWorkflowRuntime runtime = javascriptRuntime()) {
            ToolDispatcher dispatcher = javascriptDispatcher(new AtomicInteger(), runtime);
            String source = """
                    async function onCall(ctx, api) {
                      const result = await api.invoke({
                        action: "native_tool.call",
                        input: {name: "read", arguments: {path: "../functions.yml"}}
                      });
                      return {status: "ok", output: {
                        native_status: result.status,
                        content: result.output.content
                      }};
                    }
                    """;
            FunctionCatalog functions = functionCatalog(runtime, "protected_reader", source,
                    List.of("native_tool.call.read"), emptySchema(), Set.of("read"));
            ToolDefinition callFunction = callFunctionDefinition();

            ToolResult result = completed(dispatcher.execute(
                    new ToolCatalog(List.of(callFunction, readDefinition()), List.of()), functions,
                    callFunction, call("protected_reader", "{}"), "call-protected",
                    turnPlayer(), MineclawConfig.defaults()).get(5, TimeUnit.SECONDS));

            assertThat(result.output().getAsJsonObject("output"))
                    .extracting(
                            output -> output.get("native_status").getAsString(),
                            output -> output.get("content").getAsString())
                    .containsExactly("denied", "");
        }
    }

    @Test
    void nativeRuntimeFailuresRejectTheBundledApiPromise() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (JavaScriptWorkflowRuntime runtime = javascriptRuntime()) {
            ToolDispatcher dispatcher = javascriptDispatcher(calls, runtime,
                    (arguments, player, config) -> {
                        calls.incrementAndGet();
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("internal failure must not be exposed"));
                    });
            String source = """
                    async function onCall(ctx, api) {
                      try {
                        await api.invoke({
                          action: "native_tool.call",
                          input: {name: "run_command", arguments: {command: "say test"}}
                        });
                        return {status: "ok", output: {unexpected: true}};
                      } catch (failure) {
                        return {status: "ok", output: {
                          error_code: failure.code,
                          message: failure.message
                        }};
                      }
                    }
                    """;
            FunctionCatalog functions = functionCatalog(runtime, "native_failure", source,
                    List.of("native_tool.call.run_command"), emptySchema(), Set.of("run_command"));
            ToolDefinition callFunction = callFunctionDefinition();

            ToolResult result = completed(dispatcher.execute(
                    new ToolCatalog(List.of(callFunction, definition()), List.of()), functions,
                    callFunction, call("native_failure", "{}"), "call-failure",
                    turnPlayer(), MineclawConfig.defaults()).get(5, TimeUnit.SECONDS));

            assertThat(calls).hasValue(1);
            assertThat(result.status()).isEqualTo("ok");
            JsonObject output = result.output().getAsJsonObject("output");
            assertThat(output.get("error_code").getAsString()).isEqualTo("host_bridge_error");
            assertThat(output.get("message").getAsString())
                    .doesNotContain("internal failure", "IllegalStateException");
        }
    }

    @Test
    void javascriptNativeReadUsesWorkspaceRootAndDoesNotMisclassifyConfigName() throws Exception {
        Path isolated = Files.createDirectories(workspace.resolve("workspace"));
        Files.writeString(isolated.resolve("config.yml"), "ordinary workspace document");
        try (JavaScriptWorkflowRuntime runtime = javascriptRuntime()) {
            ToolDispatcher dispatcher = javascriptDispatcher(new AtomicInteger(), runtime);
            String source = """
                    async function onCall(ctx, api) {
                      const result = await api.invoke({
                        action: "native_tool.call",
                        input: {name: "read", arguments: {path: "config.yml"}}
                      });
                      return {status: "ok", output: {
                        native_status: result.status,
                        content: result.output.content
                      }};
                    }
                    """;
            FunctionCatalog functions = functionCatalog(runtime, "workspace_reader", source,
                    List.of("native_tool.call.read"), emptySchema(), Set.of("read"));
            ToolDefinition callFunction = callFunctionDefinition();

            ToolResult result = completed(dispatcher.execute(
                    new ToolCatalog(List.of(callFunction, readDefinition()), List.of()), functions,
                    callFunction, call("workspace_reader", "{}"), "call-workspace",
                    turnPlayer(), MineclawConfig.defaults()).get(5, TimeUnit.SECONDS));

            assertThat(result.output().getAsJsonObject("output"))
                    .extracting(
                            output -> output.get("native_status").getAsString(),
                            output -> output.get("content").getAsString())
                    .containsExactly("ok", "ordinary workspace document");
        }
    }

    private ToolDispatcher dispatcher(AtomicInteger calls) throws IOException {
        Server server = proxy(Server.class, null);
        Plugin plugin = proxy(Plugin.class, server);
        EnvironmentTools environment = new EnvironmentTools(server, new FoliaTasks(plugin));
        return new ToolDispatcher(new WorkspaceFileTools(workspace.resolve("workspace")), environment,
                (arguments, player, config) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ToolExecution.completed(ToolResult.simple("ok", "called")));
                }, Runnable::run);
    }

    private ToolDispatcher javascriptDispatcher(AtomicInteger calls, JavaScriptWorkflowRuntime runtime)
            throws IOException {
        return javascriptDispatcher(calls, runtime, (arguments, player, config) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    ToolExecution.completed(ToolResult.simple("ok", "called")));
        });
    }

    private ToolDispatcher javascriptDispatcher(
            AtomicInteger calls,
            JavaScriptWorkflowRuntime runtime,
            ToolDispatcher.CommandTool commandTool
    ) throws IOException {
        Server server = proxy(Server.class, null);
        Plugin plugin = proxy(Plugin.class, server);
        EnvironmentTools environment = new EnvironmentTools(server, new FoliaTasks(plugin));
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        AuditLogger audit = new AuditLogger(logger);
        CommandRuntime commandRuntime = proxy(CommandRuntime.class, null);
        InteractionManager interactions = new InteractionManager((delay, action) -> () -> { });
        JavaScriptOperationRouter operations = new JavaScriptOperationRouter(
                commandRuntime, interactions, new ScriptCommandDispatcher(commandRuntime, audit), audit,
                runtime::isActive);
        return new ToolDispatcher(new WorkspaceFileTools(workspace.resolve("workspace")), environment,
                commandTool, Runnable::run, runtime, operations);
    }

    private static ToolDefinition definition() {
        JsonObject schema = JsonParser.parseString("""
                {
                  "type":"object",
                  "properties":{"command":{"type":"string"}},
                  "required":["command"],
                  "additionalProperties":false
                }
                """).getAsJsonObject();
        return nativeDefinition("run_command", schema);
    }

    private static ToolDefinition readDefinition() {
        JsonObject schema = JsonParser.parseString("""
                {
                  "type":"object",
                  "properties":{"path":{"type":"string"}},
                  "required":["path"],
                  "additionalProperties":false
                }
                """).getAsJsonObject();
        return nativeDefinition("read", schema);
    }

    private static FunctionCatalog functionCatalog(
            JavaScriptWorkflowRuntime runtime,
            String name,
            String source,
            List<String> capabilities,
            JsonObject schema,
            Set<String> nativeNames
    ) {
        String indented = source.strip().indent(6);
        String yaml = "schema: 1\napi_version: 1\nfunctions:\n"
                + "  - name: " + name + "\n"
                + "    description: integration test function\n"
                + "    enabled: true\n"
                + "    capabilities: " + JsonParser.parseString(new com.google.gson.Gson()
                .toJson(capabilities)) + "\n"
                + "    parameters: " + schema + "\n"
                + "    on_call: |\n" + indented;
        FunctionCatalog catalog = new FunctionCatalogLoader(ignored -> { }, runtime::validateSource,
                nativeNames, FunctionCatalogLoader.Limits.defaults()).parse(yaml);
        assertThat(catalog.enabledDefinitions()).singleElement();
        return catalog;
    }

    private static ToolDefinition callFunctionDefinition() {
        JsonObject schema = JsonParser.parseString("""
                {
                  "type":"object",
                  "properties":{
                    "function":{"type":"string"},
                    "arguments":{"type":"object","additionalProperties":true}
                  },
                  "required":["function","arguments"],
                  "additionalProperties":false
                }
                """).getAsJsonObject();
        JsonObject payload = functionPayload("call_function", schema);
        return new ToolDefinition(1, "call_function", payload, true,
                ToolDefinition.Status.ENABLED, Optional.empty());
    }

    private static ToolDefinition nativeDefinition(String handler, JsonObject schema) {
        return new ToolDefinition(1, handler, functionPayload(handler, schema), true,
                ToolDefinition.Status.ENABLED,
                Optional.empty());
    }

    private static JsonObject functionPayload(String name, JsonObject schema) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", "test");
        function.add("parameters", schema.deepCopy());
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "function");
        payload.add("function", function);
        return payload;
    }

    private static String call(String function, String arguments) {
        return "{\"function\":\"" + function + "\",\"arguments\":" + arguments + '}';
    }

    private static JsonObject emptySchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{},"additionalProperties":false}
                """).getAsJsonObject();
    }

    private static JsonObject enumSchema() {
        return JsonParser.parseString("""
                {
                  "type":"object",
                  "properties":{"mode":{"type":"string","enum":["safe"]}},
                  "required":["mode"],
                  "additionalProperties":false
                }
                """).getAsJsonObject();
    }

    private static JavaScriptWorkflowRuntime javascriptRuntime() {
        return new JavaScriptWorkflowRuntime(new JavaScriptLimits(
                65_536, 16, 4, 4, 2_000L, 10_000L, 32_768, 16, 2_048));
    }

    private static ToolResult completed(ToolExecution execution) throws Exception {
        return execution.pending()
                ? execution.continuation().get(5, TimeUnit.SECONDS)
                : execution.immediate();
    }

    private static void assertCallEnvelope(
            ToolResult result,
            String status,
            String function,
            String errorCode
    ) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.output().keySet()).containsExactlyInAnyOrder("status", "function", "output");
        assertThat(result.output().get("status").getAsString()).isEqualTo(status);
        if (function == null) {
            assertThat(result.output().get("function").isJsonNull()).isTrue();
        } else {
            assertThat(result.output().get("function").getAsString()).isEqualTo(function);
        }
        assertThat(result.output().getAsJsonObject("output").get("error_code").getAsString())
                .isEqualTo(errorCode);
    }

    private static ToolDispatcher.TurnPlayer turnPlayer() {
        return new ToolDispatcher.TurnPlayer(UUID.randomUUID(), "Tester", proxy(Player.class, null));
    }

    private static <T> T proxy(Class<T> type, Server server) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> method.getName().equals("getServer") && server != null
                        ? server : defaultValue(method.getReturnType()));
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
