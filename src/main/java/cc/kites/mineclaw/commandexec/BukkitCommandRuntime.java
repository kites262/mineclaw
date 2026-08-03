package cc.kites.mineclaw.commandexec;

import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.support.MessageService;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Production command boundary. All server and player work is transferred to its Folia owner. */
public final class BukkitCommandRuntime implements CommandRuntime {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Server server;
    private final FoliaTasks tasks;
    private final MessageService messages;
    private final Executor ioExecutor;
    private final CommandRootIndex commandRoots;

    public BukkitCommandRuntime(Server server, FoliaTasks tasks, MessageService messages, Executor ioExecutor,
                                CommandRootIndex commandRoots) {
        this.server = Objects.requireNonNull(server, "server");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.commandRoots = Objects.requireNonNull(commandRoots, "commandRoots");
    }

    @Override
    public CompletionStage<Optional<OnlinePlayer>> findOnlinePlayer(String nameOrUuid) {
        Objects.requireNonNull(nameOrUuid, "nameOrUuid");
        return tasks.global(() -> player(nameOrUuid)).thenCompose(player -> {
            if (player == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            CompletionStage<Optional<OnlinePlayer>> scheduled = tasks.entity(player, () -> player.isOnline()
                    ? Optional.of(new OnlinePlayer(player.getUniqueId(), player.getName()))
                    : Optional.empty());
            return scheduled.handle((result, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(result);
                }
                return tasks.global(() -> player(nameOrUuid) != null)
                        .thenApply(stillOnline -> playerLookupFailure(stillOnline, error));
            }).thenCompose(stage -> stage);
        });
    }

    @Override
    public CompletionStage<CommandDispatchResult> executeConsole(String command) {
        return executeConsoleGuarded(command, () -> true);
    }

    @Override
    public CompletionStage<CommandDispatchResult> executeConsoleGuarded(String command, BooleanSupplier guard) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(guard, "guard");
        return tasks.global(() -> {
            if (!guard.getAsBoolean()) {
                return CommandDispatchResult.dispatchRejected();
            }
            CommandRootIndex.Resolution root = commandRoots.resolve(command);
            BoundedFeedback feedback = new BoundedFeedback();
            // Paper's forwarding sender has the console's effective permissions while routing
            // synchronous messages to this consumer. It intentionally is not ConsoleCommandSender.
            CommandSender sender = server.createCommandSender(component -> {
                String plain = PLAIN.serialize(component);
                feedback.accept(plain);
            });
            try {
                if (!guard.getAsBoolean()) {
                    return CommandDispatchResult.dispatchRejected();
                }
                boolean accepted = server.dispatchCommand(sender, command);
                String captured = feedback.closeAndGet();
                return accepted
                        ? CommandDispatchResult.consoleDispatched(captured)
                        : failedDispatch(root, captured);
            } catch (RuntimeException exception) {
                return CommandDispatchResult.executionException(
                        safeMessage(exception), feedback.closeAndGet());
            }
        });
    }

    @Override
    public CompletionStage<CommandDispatchResult> executePlayer(OnlinePlayer target, String command) {
        return executePlayerGuarded(target, command, () -> true);
    }

    @Override
    public CompletionStage<CommandDispatchResult> executePlayerGuarded(OnlinePlayer target, String command,
                                                                       BooleanSupplier guard) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(guard, "guard");
        return tasks.global(() -> new PlayerDispatchLookup(
                server.getPlayer(target.uuid()), commandRoots.resolve(command))).thenCompose(lookup -> {
            if (lookup.player() == null) {
                return CompletableFuture.completedFuture(CommandDispatchResult.playerOffline());
            }
            CompletionStage<CommandDispatchResult> scheduled = tasks.entity(lookup.player(), () -> {
                if (!guard.getAsBoolean()) {
                    return CommandDispatchResult.dispatchRejected();
                }
                if (!lookup.player().isOnline()) {
                    return CommandDispatchResult.playerOffline();
                }
                try {
                    if (!guard.getAsBoolean()) {
                        return CommandDispatchResult.dispatchRejected();
                    }
                    boolean accepted = lookup.player().performCommand(command);
                    // performCommand only exposes dispatch acceptance. Player-visible command
                    // feedback and the command's actual effect are intentionally not inferred.
                    return accepted
                            ? CommandDispatchResult.playerDispatched()
                            : failedDispatch(lookup.root(), "");
                } catch (RuntimeException exception) {
                    return CommandDispatchResult.executionException(safeMessage(exception));
                }
            });
            return scheduled.handle((result, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(result);
                }
                // A player may retire between the global lookup and the entity-owned task. Resolve
                // the UUID once more on the global owner so that this race is reported as offline,
                // while a live player with a scheduler failure remains an unknown dispatch result.
                return tasks.global(() -> server.getPlayer(target.uuid()) != null)
                        .handle((stillOnline, lookupError) -> lookupError == null
                                ? playerScheduleFailure(Boolean.TRUE.equals(stillOnline), error)
                                : CommandDispatchResult.resultUnknown(
                                        "player dispatch scheduling failed: " + safeMessage(error)
                                                + "; online recheck failed: " + safeMessage(lookupError)));
            }).thenCompose(stage -> stage);
        });
    }

    @Override
    public CompletionStage<Boolean> send(OnlinePlayer target, String messageKey, Map<String, String> values) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(values, "values");
        return CompletableFuture.supplyAsync(() -> messages.render(messageKey, values), ioExecutor)
                .thenCompose(message -> send(target, message));
    }

    @Override
    public CompletionStage<Boolean> sendApprovalPrompt(OnlinePlayer target, Map<String, String> values) {
        return sendApprovalPromptGuarded(target, values, () -> true);
    }

    @Override
    public CompletionStage<Boolean> sendApprovalPromptGuarded(
            OnlinePlayer target,
            Map<String, String> values,
            BooleanSupplier guard
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(guard, "guard");
        return CompletableFuture.supplyAsync(() -> messages.renderApprovalPrompt(values), ioExecutor)
                .thenCompose(message -> send(target, message, true, guard));
    }

    @Override
    public CompletionStage<Boolean> sendInteractionPrompt(
            OnlinePlayer target, String token, InteractionManager.Interaction interaction) {
        return sendInteractionPromptGuarded(target, token, interaction, () -> true);
    }

    @Override
    public CompletionStage<Boolean> sendInteractionPromptGuarded(
            OnlinePlayer target,
            String token,
            InteractionManager.Interaction interaction,
            BooleanSupplier guard
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(guard, "guard");
        return CompletableFuture.supplyAsync(
                        () -> messages.renderInteractionPrompt(token, interaction), ioExecutor)
                .thenCompose(message -> send(target, message, true, guard));
    }

    private CompletionStage<Boolean> send(OnlinePlayer target, Component message) {
        return send(target, message, false);
    }

    private CompletionStage<Boolean> send(OnlinePlayer target, Component message,
                                          boolean requireApprovalPermission) {
        return send(target, message, requireApprovalPermission, () -> true);
    }

    private CompletionStage<Boolean> send(OnlinePlayer target, Component message,
                                          boolean requireApprovalPermission, BooleanSupplier guard) {
        Objects.requireNonNull(guard, "guard");
        return tasks.global(() -> server.getPlayer(target.uuid())).thenCompose(player -> {
            if (player == null) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            return tasks.entity(player, () -> {
                if (!guard.getAsBoolean() || !player.isOnline() || requireApprovalPermission
                        && !player.hasPermission("mineclaw.command.approve")) {
                    return Boolean.FALSE;
                }
                player.sendMessage(message);
                return Boolean.TRUE;
            });
        });
    }

    private Player player(String identifier) {
        try {
            Player byUuid = server.getPlayer(UUID.fromString(identifier));
            if (byUuid != null) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // A non-UUID identifier is resolved only as an exact player name.
        }
        return server.getPlayerExact(identifier);
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    static CommandDispatchResult playerScheduleFailure(boolean stillOnline, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return stillOnline
                ? CommandDispatchResult.resultUnknown(
                        "player dispatch scheduling failed: " + safeMessage(failure))
                : CommandDispatchResult.playerOffline();
    }

    static Optional<OnlinePlayer> playerLookupFailure(boolean stillOnline, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!stillOnline) {
            return Optional.empty();
        }
        throw new java.util.concurrent.CompletionException(failure);
    }

    static CommandDispatchResult failedDispatch(CommandRootIndex.Resolution root, String feedback) {
        return switch (Objects.requireNonNull(root, "root")) {
            case FOUND -> CommandDispatchResult.dispatchRejected(feedback);
            case MISSING -> CommandDispatchResult.commandNotFound(feedback);
            case UNKNOWN -> CommandDispatchResult.resultUnknown(
                    "command dispatcher root was unavailable while classifying a rejected dispatch", feedback);
        };
    }

    private record PlayerDispatchLookup(Player player, CommandRootIndex.Resolution root) {
        private PlayerDispatchLookup {
            Objects.requireNonNull(root, "root");
        }
    }

    /** Captures only output produced before dispatch returns, with a strict aggregate size bound. */
    static final class BoundedFeedback {
        private final StringBuilder text = new StringBuilder();
        private boolean capturing = true;

        synchronized void accept(String line) {
            if (!capturing || line == null || line.isBlank()
                    || text.length() > CommandDispatchResult.MAX_FEEDBACK_CHARS) {
                return;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            int limit = CommandDispatchResult.MAX_FEEDBACK_CHARS + 1;
            int remaining = limit - text.length();
            if (remaining > 0) {
                text.append(line, 0, Math.min(line.length(), remaining));
            }
        }

        synchronized String closeAndGet() {
            capturing = false;
            return text.toString();
        }
    }
}
