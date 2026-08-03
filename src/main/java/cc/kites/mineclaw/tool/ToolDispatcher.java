package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.commandexec.CommandRules;
import cc.kites.mineclaw.function.FunctionCatalog;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.javascript.OperationHandle;
import cc.kites.mineclaw.javascript.OperationResult;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.workspace.ToolCatalog;
import cc.kites.mineclaw.workspace.ToolDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Binds a per-request Tool definition to one of Mineclaw's built-in handlers. Workspace routing is
 * deliberately limited to the read-only list/read/grep handlers; no filesystem mutation handler is
 * exposed.
 */
public final class ToolDispatcher {
    private final WorkspaceFileTools files;
    private final EnvironmentTools environment;
    private final CommandTool commands;
    private final Executor ioExecutor;
    private final CallFunctionHandler callFunctions;

    public ToolDispatcher(WorkspaceFileTools files, EnvironmentTools environment,
                          CommandTool commands, Executor ioExecutor) {
        this(files, environment, commands, ioExecutor, null, null, null);
    }

    public ToolDispatcher(WorkspaceFileTools files, EnvironmentTools environment,
                          CommandTool commands, Executor ioExecutor,
                          JavaScriptWorkflowRuntime javascript,
                          JavaScriptOperationRouter javascriptOperations) {
        this(files, environment, commands, ioExecutor, javascript, javascriptOperations, null);
    }

    public ToolDispatcher(WorkspaceFileTools files, EnvironmentTools environment,
                          CommandTool commands, Executor ioExecutor,
                          JavaScriptWorkflowRuntime javascript,
                          JavaScriptOperationRouter javascriptOperations,
                          AuditLogger audit) {
        this.files = Objects.requireNonNull(files, "files");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        if ((javascript == null) != (javascriptOperations == null)) {
            throw new IllegalArgumentException("JavaScript runtime and operation router must be supplied together");
        }
        this.callFunctions = new CallFunctionHandler(javascript, javascriptOperations, audit);
    }

    public CompletableFuture<ToolExecution> execute(ToolDefinition definition, String rawArguments,
                                                     TurnPlayer turnPlayer, MineclawConfig config) {
        return execute(new ToolCatalog(List.of(definition), List.of()), definition, rawArguments,
                turnPlayer, config);
    }

    /** Executes against the exact immutable catalog snapshot previously sent to the model. */
    public CompletableFuture<ToolExecution> execute(
            ToolCatalog catalog,
            ToolDefinition definition,
            String rawArguments,
            TurnPlayer turnPlayer,
            MineclawConfig config
    ) {
        return execute(catalog, null, definition, rawArguments, "", turnPlayer, config);
    }

    /** Executes against the exact immutable Tool and Function snapshots captured for this Turn. */
    public CompletableFuture<ToolExecution> execute(
            ToolCatalog catalog,
            FunctionCatalog functions,
            ToolDefinition definition,
            String rawArguments,
            String toolCallId,
            TurnPlayer turnPlayer,
            MineclawConfig config
    ) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(config, "config");
        boolean functionGateway = definition.registeredHandler().orElse(null)
                == ToolDefinition.Handler.CALL_FUNCTION;
        if (functionGateway) {
            if (!definition.available() || functions == null) {
                return executeUnavailableCallFunction(functions, rawArguments, toolCallId,
                        turnPlayer, config);
            }
            return callFunctions.execute(toolCallId, rawArguments, functions, turnPlayer, config,
                    (name, arguments, ownerActive) -> invokeNative(
                            catalog, name, arguments, turnPlayer, config, ownerActive));
        }
        if (!definition.available() || definition.registeredHandler().isEmpty()) {
            return completedInvalid("工具未启用或定义无效：" + definition.printableHandler());
        }
        JsonObject arguments;
        try {
            var parsed = JsonParser.parseString(rawArguments == null || rawArguments.isBlank()
                    ? "{}" : rawArguments);
            if (!parsed.isJsonObject()) {
                return completedInvalidArguments("$", "expected object but found non-object JSON");
            }
            arguments = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            return completedInvalidArguments("$", "工具参数不是合法的 JSON");
        }
        var violation = ToolArgumentValidator.validate(arguments, definition.parameters());
        if (violation.isPresent()) {
            ToolArgumentValidator.Violation problem = violation.orElseThrow();
            return completedInvalidArguments(problem.path(), problem.message());
        }

        return executeNative(definition.registeredHandler().orElseThrow(), arguments, turnPlayer, config);
    }

    /** Strict fixed-envelope rejection when the model names an unavailable call_function Tool. */
    public CompletableFuture<ToolExecution> executeUnavailableCallFunction(
            FunctionCatalog functions,
            String rawArguments,
            String toolCallId,
            TurnPlayer turnPlayer,
            MineclawConfig config
    ) {
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(config, "config");
        long generation = functions == null ? 1L : functions.generation();
        FunctionCatalog unavailable = FunctionCatalog.empty(generation, null);
        return callFunctions.execute(toolCallId, rawArguments, unavailable, turnPlayer, config,
                (name, arguments, ownerActive) -> OperationHandle.completed(operationFailure(
                        "invalid", "native_tool_unavailable", "Function catalog is unavailable")));
    }

    private CompletableFuture<ToolExecution> executeNative(
            ToolDefinition.Handler handler,
            JsonObject arguments,
            TurnPlayer turnPlayer,
            MineclawConfig config
    ) {
        return executeNative(handler, arguments, turnPlayer, config,
                () -> true, ignored -> { }, false);
    }

    private CompletableFuture<ToolExecution> executeNative(
            ToolDefinition.Handler handler,
            JsonObject arguments,
            TurnPlayer turnPlayer,
            MineclawConfig config,
            BooleanSupplier ownerActive
    ) {
        return executeNative(handler, arguments, turnPlayer, config,
                ownerActive, ignored -> { }, false);
    }

    private CompletableFuture<ToolExecution> executeNative(
            ToolDefinition.Handler handler,
            JsonObject arguments,
            TurnPlayer turnPlayer,
            MineclawConfig config,
            BooleanSupplier ownerActive,
            Consumer<Runnable> cancellationRegistrar
    ) {
        return executeNative(handler, arguments, turnPlayer, config,
                ownerActive, cancellationRegistrar, false);
    }

    private CompletableFuture<ToolExecution> executeNative(
            ToolDefinition.Handler handler,
            JsonObject arguments,
            TurnPlayer turnPlayer,
            MineclawConfig config,
            BooleanSupplier ownerActive,
            Consumer<Runnable> cancellationRegistrar,
            boolean preserveFailures
    ) {
        return switch (handler) {
            case LIST -> fileAsync(() -> files.list(arguments, limits(config)), preserveFailures);
            case READ -> fileAsync(() -> files.read(arguments, limits(config)), preserveFailures);
            case GREP -> fileAsync(() -> files.grep(arguments, limits(config)), preserveFailures);
            case LOOK_BLOCK, FEET_BLOCK, INVENTORY, ONLINE_PLAYERS -> preserveFailures
                    ? environment.execute(handler.wireName(), turnPlayer.id(), turnPlayer.name(),
                            environment(config)).thenApply(ToolExecution::completed)
                    : environment.execute(
                            handler.wireName(), turnPlayer.id(), turnPlayer.name(),
                            environment(config))
                    .handle((result, failure) -> failure == null
                            ? ToolExecution.completed(result)
                            : ToolExecution.completed(terminal(failure)));
            case RUN_COMMAND -> {
                CompletableFuture<ToolExecution> execution = commands.executeGuarded(
                        arguments, turnPlayer, config, ownerActive, cancellationRegistrar);
                yield preserveFailures ? execution
                        : execution.exceptionally(failure -> ToolExecution.completed(terminal(failure)));
            }
            case CALL_FUNCTION -> throw new IllegalStateException(
                    "call_function requires a FunctionCatalog and cannot be invoked as a native operation");
        };
    }

    private OperationHandle invokeNative(
            ToolCatalog catalog,
            String name,
            JsonObject arguments,
            TurnPlayer turnPlayer,
            MineclawConfig config,
            BooleanSupplier ownerActive
    ) {
        Optional<ToolDefinition> target = catalog.findEnabled(name)
                .filter(definition -> definition.registeredHandler().orElse(null)
                        != ToolDefinition.Handler.CALL_FUNCTION);
        if (target.isEmpty()) {
            return OperationHandle.completed(operationFailure("invalid", "native_tool_unavailable",
                    "native tool is not enabled in this catalog snapshot"));
        }
        ToolDefinition definition = target.orElseThrow();
        var violation = ToolArgumentValidator.validate(arguments, definition.parameters());
        if (violation.isPresent()) {
            ToolArgumentValidator.Violation problem = violation.orElseThrow();
            JsonObject output = new JsonObject();
            output.addProperty("error_code", "invalid_arguments");
            output.addProperty("path", problem.path());
            output.addProperty("message", problem.message());
            return OperationHandle.completed(new OperationResult("invalid", output));
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Runnable> preparationCancellation = new AtomicReference<>(() -> { });
        Consumer<Runnable> cancellationRegistrar = cancellation -> {
            Runnable exact = Objects.requireNonNull(cancellation, "cancellation");
            preparationCancellation.set(exact);
            if (cancelled.get()) {
                exact.run();
            }
        };
        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        AtomicReference<ToolExecution> activeExecution = new AtomicReference<>();
        CompletableFuture<ToolExecution> dispatched = executeNative(
                definition.registeredHandler().orElseThrow(), arguments.deepCopy(), turnPlayer, config,
                ownerActive, cancellationRegistrar, true);
        dispatched.whenComplete((execution, dispatchFailure) -> {
            if (dispatchFailure != null) {
                completion.completeExceptionally(dispatchFailure);
                return;
            }
            activeExecution.set(execution);
            if (cancelled.get()) {
                execution.cancel();
                completion.complete(operationFailure("cancelled", "invocation_cancelled",
                        "JavaScript invocation was cancelled"));
                return;
            }
            CompletableFuture<ToolResult> result = execution.pending()
                    ? execution.continuation() : CompletableFuture.completedFuture(execution.immediate());
            result.whenComplete((toolResult, failure) -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else {
                    try {
                        completion.complete(normalizeNative(toolResult));
                    } catch (RuntimeException exception) {
                        completion.completeExceptionally(exception);
                    }
                }
            });
        });
        return new OperationHandle(completion, () -> {
            cancelled.set(true);
            try {
                preparationCancellation.get().run();
            } catch (RuntimeException ignored) {
                // Invocation cancellation remains terminal even if an adapter cleanup hook fails.
            }
            ToolExecution execution = activeExecution.get();
            if (execution != null) {
                try {
                    execution.cancel();
                } catch (RuntimeException ignored) {
                    // The cancelled operation result below is authoritative for the script boundary.
                }
            }
            completion.complete(operationFailure("cancelled", "invocation_cancelled",
                    "JavaScript invocation was cancelled"));
        });
    }

    private static OperationResult normalizeNative(ToolResult result) {
        Objects.requireNonNull(result, "result");
        Set<String> allowed = Set.of("ok", "recoverable_error", "denied", "invalid", "dispatched",
                "timeout", "terminal_error", "cancelled");
        if (!allowed.contains(result.status()) || result.status().equals("pending_approval")) {
            throw new IllegalStateException("native tool returned an unsupported final status");
        }
        JsonObject output = result.output().deepCopy();
        JsonElement embedded = output.remove("status");
        if (embedded != null && (!embedded.isJsonPrimitive()
                || !embedded.getAsJsonPrimitive().isString()
                || !embedded.getAsString().equals(result.status()))) {
            throw new IllegalStateException("native tool output status disagrees with ToolResult status");
        }
        if ((result.status().equals("ok") || result.status().equals("dispatched"))
                && output.has("error_code") && output.get("error_code").isJsonPrimitive()
                && output.get("error_code").getAsJsonPrimitive().isString()
                && output.get("error_code").getAsString().equals("none")) {
            output.remove("error_code");
        }
        return new OperationResult(result.status(), output);
    }

    private static OperationResult operationFailure(String status, String code, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        return new OperationResult(status, output);
    }


    private CompletableFuture<ToolExecution> fileAsync(
            java.util.function.Supplier<ToolResult> operation,
            boolean preserveFailures
    ) {
        CompletableFuture<ToolExecution> execution = CompletableFuture
                .supplyAsync(operation, ioExecutor)
                .thenApply(ToolExecution::completed);
        return preserveFailures ? execution
                : execution.exceptionally(failure -> ToolExecution.completed(terminal(failure)));
    }

    private static WorkspaceFileTools.Limits limits(MineclawConfig config) {
        MineclawConfig.FileTools values = config.fileTools();
        return new WorkspaceFileTools.Limits(values.maxReadChars(), values.maxResults(), values.maxDepth(),
                values.timeoutMillis());
    }

    private static EnvironmentTools.Settings environment(MineclawConfig config) {
        MineclawConfig.Environment values = config.environment();
        return new EnvironmentTools.Settings(values.lookDistance(), values.toolCooldownMillis(),
                values.inventory().includeEquipment(), values.inventory().maxSlots());
    }

    private static CompletableFuture<ToolExecution> completedInvalid(String message) {
        return CompletableFuture.completedFuture(ToolExecution.completed(ToolResult.simple("invalid", message)));
    }

    private static CompletableFuture<ToolExecution> completedInvalidArguments(String path, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("status", "invalid");
        output.addProperty("error_code", "invalid_arguments");
        output.addProperty("path", path);
        output.addProperty("message", message);
        return CompletableFuture.completedFuture(ToolExecution.completed(new ToolResult("invalid", output)));
    }

    private static ToolResult terminal(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return ToolResult.simple("terminal_error", cause.getClass().getSimpleName());
    }

    @FunctionalInterface
    public interface CommandTool {
        CompletableFuture<ToolExecution> execute(JsonObject arguments, TurnPlayer turnPlayer,
                                                  MineclawConfig config);

        default CompletableFuture<ToolExecution> executeGuarded(
                JsonObject arguments,
                TurnPlayer turnPlayer,
                MineclawConfig config,
                BooleanSupplier ownerActive
        ) {
            Objects.requireNonNull(ownerActive, "ownerActive");
            if (!ownerActive.getAsBoolean()) {
                return CompletableFuture.completedFuture(ToolExecution.completed(
                        ToolResult.simple("cancelled", "tool owner was cancelled")));
            }
            return execute(arguments, turnPlayer, config);
        }

        default CompletableFuture<ToolExecution> executeGuarded(
                JsonObject arguments,
                TurnPlayer turnPlayer,
                MineclawConfig config,
                BooleanSupplier ownerActive,
                Consumer<Runnable> cancellationRegistrar
        ) {
            Objects.requireNonNull(cancellationRegistrar, "cancellationRegistrar");
            return executeGuarded(arguments, turnPlayer, config, ownerActive);
        }
    }

    public record TurnPlayer(UUID id, String name, Player player, CommandRules commandRules) {
        public TurnPlayer {
            Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(commandRules, "commandRules");
        }

        public TurnPlayer(UUID id, String name, Player player) {
            this(id, name, player, new CommandRules(false, List.of(), List.of()));
        }
    }
}
