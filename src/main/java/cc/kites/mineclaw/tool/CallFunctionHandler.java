package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.function.FunctionCatalog;
import cc.kites.mineclaw.function.FunctionDefinition;
import cc.kites.mineclaw.javascript.InvocationHandle;
import cc.kites.mineclaw.javascript.InvocationRequest;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.javascript.OperationHandle;
import cc.kites.mineclaw.javascript.PreparedScript;
import cc.kites.mineclaw.schema.SchemaViolation;
import cc.kites.mineclaw.schema.ValidationResult;
import cc.kites.mineclaw.support.AuditLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Native {@code call_function} handler over one immutable per-Turn FunctionCatalog snapshot. */
public final class CallFunctionHandler {
    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Set<String> OUTER_FIELDS = Set.of("function", "arguments");

    private final JavaScriptWorkflowRuntime runtime;
    private final JavaScriptOperationRouter operations;
    private final AuditLogger audit;

    public CallFunctionHandler(
            JavaScriptWorkflowRuntime runtime,
            JavaScriptOperationRouter operations,
            AuditLogger audit
    ) {
        if ((runtime == null) != (operations == null)) {
            throw new IllegalArgumentException(
                    "JavaScript runtime and operation router must be supplied together");
        }
        this.runtime = runtime;
        this.operations = operations;
        this.audit = audit;
    }

    public CompletableFuture<ToolExecution> execute(
            String toolCallId,
            String rawArguments,
            FunctionCatalog catalog,
            ToolDispatcher.TurnPlayer turnPlayer,
            MineclawConfig config,
            NativeToolInvoker nativeTools
    ) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(nativeTools, "nativeTools");
        long startedNanos = System.nanoTime();
        String callId = toolCallId == null ? "" : toolCallId;
        String raw = rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments;
        if (raw.codePointCount(0, raw.length()) > config.functions().maxArgumentChars()) {
            ToolResult result = CallFunctionResultFactory.invalidCall(null);
            audit(callId, null, catalog, null, turnPlayer, "arguments_rejected", result, "rejected",
                    auditViolation("$", "maxChars"), startedNanos);
            return completed(result);
        }

        JsonObject outer;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                ToolResult result = CallFunctionResultFactory.invalidCall(null);
                audit(callId, null, catalog, null, turnPlayer, "arguments_rejected", result, "rejected",
                        auditViolation("$", "type"), startedNanos);
                return completed(result);
            }
            outer = parsed.getAsJsonObject();
        } catch (RuntimeException | StackOverflowError failure) {
            ToolResult result = CallFunctionResultFactory.invalidCall(null);
            audit(callId, null, catalog, null, turnPlayer, "arguments_rejected", result, "rejected",
                    auditViolation("$", "json"), startedNanos);
            return completed(result);
        }

        String functionName = legalFunctionName(outer.get("function"));
        if (outer.size() != 2 || !outer.keySet().equals(OUTER_FIELDS)
                || functionName == null || !outer.get("arguments").isJsonObject()) {
            ToolResult result = CallFunctionResultFactory.invalidCall(functionName);
            audit(callId, null, catalog, functionName, turnPlayer,
                    "arguments_rejected", result, "rejected",
                    auditViolation("$", "outer_arguments"), startedNanos);
            return completed(result);
        }

        FunctionDefinition definition = catalog.findEnabled(functionName).orElse(null);
        if (definition == null || definition.compiledParameters().isEmpty()
                || definition.preparedSource().isEmpty() || runtime == null || operations == null) {
            ToolResult result = CallFunctionResultFactory.unavailable(functionName);
            audit(callId, null, catalog, functionName, turnPlayer, "unavailable", result, "not_run",
                    java.util.List.of(), startedNanos);
            return completed(result);
        }

        JsonObject arguments = outer.getAsJsonObject("arguments");
        ValidationResult validation = definition.compiledParameters().orElseThrow().validate(arguments);
        if (!validation.valid()) {
            JsonArray violations = violations(validation);
            ToolResult result = CallFunctionResultFactory.invalidArguments(functionName, violations);
            audit(callId, null, catalog, functionName, turnPlayer, "arguments_rejected", result,
                    "rejected", validation.violations(), startedNanos);
            return completed(result);
        }

        PreparedScript script = definition.preparedSource().orElseThrow();
        if (!script.functionName().equals(functionName)
                || definition.scriptHash().isEmpty()
                || !script.scriptHash().equals(definition.scriptHash().orElseThrow())) {
            ToolResult result = CallFunctionResultFactory.unavailable(functionName);
            audit(callId, null, catalog, functionName, turnPlayer, "unavailable", result, "valid",
                    java.util.List.of(), startedNanos);
            return completed(result);
        }

        String invocationId = UUID.randomUUID().toString();
        InvocationRequest request = new InvocationRequest(invocationId, turnPlayer.name(),
                arguments, Set.copyOf(definition.capabilities()));
        audit(callId, invocationId, catalog, functionName, turnPlayer, "started", null, "valid",
                java.util.List.of(), startedNanos);
        JavaScriptOperationRouter.NativeInvoker nativeInvoker = (name, nativeArguments) ->
                nativeTools.invoke(name, nativeArguments, () -> runtime.isActive(invocationId));
        InvocationHandle invocation;
        try {
            invocation = runtime.execute(script, request, operations.host(
                    new JavaScriptOperationRouter.InvocationActor(turnPlayer.id(), turnPlayer.name()),
                    nativeInvoker));
        } catch (RuntimeException | LinkageError failure) {
            ToolResult result = CallFunctionResultFactory.failure("terminal_error", functionName,
                    "javascript_runtime_error", "Function 无法启动");
            audit(callId, invocationId, catalog, functionName, turnPlayer, "ended", result, "valid",
                    java.util.List.of(), startedNanos);
            return completed(result);
        }

        CompletableFuture<ToolResult> completion = invocation.result().handle((scriptResult, failure) -> {
            ToolResult result = failure == null
                    ? CallFunctionResultFactory.fromScript(functionName, scriptResult)
                    : CallFunctionResultFactory.failure("terminal_error", functionName,
                    "javascript_runtime_error", "Function 执行失败");
            audit(callId, invocationId, catalog, functionName, turnPlayer, "ended", result, "valid",
                    java.util.List.of(), startedNanos);
            return result;
        });
        if (completion.isDone()) {
            try {
                return completed(completion.join());
            } catch (CompletionException ignored) {
                // The handle stage above is total; preserve async behavior if an implementation changes.
            }
        }

        JsonObject waiting = new JsonObject();
        waiting.addProperty("status", "pending_approval");
        waiting.addProperty("message", "Function workflow is still running");
        audit(callId, invocationId, catalog, functionName, turnPlayer, "waiting", null, "valid",
                java.util.List.of(), startedNanos);
        return CompletableFuture.completedFuture(ToolExecution.pending(
                new ToolResult("pending_approval", waiting), completion, invocation::cancel));
    }

    private static CompletableFuture<ToolExecution> completed(ToolResult result) {
        return CompletableFuture.completedFuture(ToolExecution.completed(result));
    }

    private static String legalFunctionName(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String name = value.getAsString();
        return name.length() <= 96 && FUNCTION_NAME.matcher(name).matches() ? name : null;
    }

    private static JsonArray violations(ValidationResult validation) {
        JsonArray result = new JsonArray();
        for (SchemaViolation violation : validation.violations()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", violation.path());
            entry.addProperty("keyword", violation.keyword());
            entry.addProperty("message", violation.message());
            result.add(entry);
        }
        return result;
    }

    private void audit(
            String toolCallId,
            String invocationId,
            FunctionCatalog catalog,
            String functionName,
            ToolDispatcher.TurnPlayer player,
            String phase,
            ToolResult result,
            String argumentValidation,
            java.util.List<SchemaViolation> validationViolations,
            long startedNanos
    ) {
        if (audit == null) {
            return;
        }
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool_call_id", toolCallId);
        if (invocationId != null) {
            fields.put("invocation_id", invocationId);
        }
        fields.put("function", functionName == null ? "null" : functionName);
        fields.put("catalog_generation", catalog.generation());
        catalog.find(functionName == null ? "" : functionName).ifPresent(definition -> {
            fields.put("script_hash", definition.scriptHash().orElse("-"));
            fields.put("api_version", definition.apiVersion());
            fields.put("capabilities", definition.capabilities());
        });
        fields.put("turn_player", player.name() + "(" + player.id() + ")");
        fields.put("phase", phase);
        fields.put("argument_validation", argumentValidation);
        fields.put("violation_count", validationViolations.size());
        if (!validationViolations.isEmpty()) {
            fields.put("violation_paths", validationViolations.stream()
                    .map(SchemaViolation::path).toList());
            fields.put("violation_keywords", validationViolations.stream()
                    .map(SchemaViolation::keyword).toList());
        }
        fields.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedNanos)));
        if (result != null) {
            fields.put("result", result.status());
            JsonObject output = result.output().has("output")
                    && result.output().get("output").isJsonObject()
                    ? result.output().getAsJsonObject("output") : new JsonObject();
            if (text(output.get("error_code"))) {
                fields.put("error_code", output.get("error_code").getAsString());
            }
        }
        audit.log("function.invocation", fields);
    }

    private static java.util.List<SchemaViolation> auditViolation(String path, String keyword) {
        return java.util.List.of(new SchemaViolation(path, keyword, "call arguments were rejected"));
    }

    private static boolean text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    @FunctionalInterface
    public interface NativeToolInvoker {
        OperationHandle invoke(String name, JsonObject arguments, BooleanSupplier ownerActive);
    }
}
