package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.workspace.ToolDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

    public ToolDispatcher(WorkspaceFileTools files, EnvironmentTools environment,
                          CommandTool commands, Executor ioExecutor) {
        this.files = Objects.requireNonNull(files, "files");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    public CompletableFuture<ToolExecution> execute(ToolDefinition definition, String rawArguments,
                                                     TurnPlayer turnPlayer, MineclawConfig config) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(config, "config");
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
        if (!definition.available() || definition.handler().isEmpty()) {
            return completedInvalid("工具未启用或定义无效：" + definition.printableName());
        }
        var violation = ToolArgumentValidator.validate(arguments, definition.parameters());
        if (violation.isPresent()) {
            ToolArgumentValidator.Violation problem = violation.orElseThrow();
            return completedInvalidArguments(problem.path(), problem.message());
        }

        return switch (definition.handler().orElseThrow()) {
            case LIST -> fileAsync(() -> files.list(arguments, limits(config)));
            case READ -> fileAsync(() -> files.read(arguments, limits(config)));
            case GREP -> fileAsync(() -> files.grep(arguments, limits(config)));
            case LOOK_BLOCK, FEET_BLOCK, INVENTORY, ONLINE_PLAYERS -> environment.execute(
                            definition.handler().orElseThrow().wireName(), turnPlayer.id(), turnPlayer.name(),
                            environment(config))
                    .handle((result, failure) -> failure == null
                            ? ToolExecution.completed(result)
                            : ToolExecution.completed(terminal(failure)));
            case RUN_COMMAND -> commands.execute(arguments, turnPlayer, config)
                    .exceptionally(failure -> ToolExecution.completed(terminal(failure)));
        };
    }

    private CompletableFuture<ToolExecution> fileAsync(java.util.function.Supplier<ToolResult> operation) {
        return CompletableFuture.supplyAsync(operation, ioExecutor)
                .handle((result, failure) -> failure == null
                        ? ToolExecution.completed(result)
                        : ToolExecution.completed(terminal(failure)));
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
    }

    public record TurnPlayer(UUID id, String name, Player player) {
        public TurnPlayer {
            Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            Objects.requireNonNull(player, "player");
        }
    }
}
