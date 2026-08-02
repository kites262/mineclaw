package cc.kites.mineclaw.command;

import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.commandexec.CommandExecutor;
import cc.kites.mineclaw.config.ConfigStore;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.support.MessageService;
import cc.kites.mineclaw.support.PlayerChannel;
import cc.kites.mineclaw.turn.TurnCoordinator;
import cc.kites.mineclaw.workspace.ToolCatalog;
import cc.kites.mineclaw.workspace.ToolCatalogLoader;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Paper command root for conversation, lifecycle and approval operations. */
public final class MineclawCommand implements BasicCommand {
    private final ConfigStore config;
    private final TurnCoordinator turns;
    private final CommandExecutor commands;
    private final ToolCatalogLoader toolLoader;
    private final Path workspaceRoot;
    private final Path toolsFile;
    private final MessageService messages;
    private final PlayerChannel channel;
    private final FoliaTasks tasks;
    private final Executor ioExecutor;
    private final Consumer<CommandSender> reload;

    public MineclawCommand(ConfigStore config, TurnCoordinator turns, CommandExecutor commands,
                           ToolCatalogLoader toolLoader, Path workspaceRoot, Path toolsFile,
                           MessageService messages,
                           PlayerChannel channel, FoliaTasks tasks, Executor ioExecutor,
                           Consumer<CommandSender> reload) {
        this.config = Objects.requireNonNull(config, "config");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.toolLoader = Objects.requireNonNull(toolLoader, "toolLoader");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.toolsFile = Objects.requireNonNull(toolsFile, "toolsFile");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            renderAndSend(sender, "usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> unary(sender, args, this::clear);
            case "approve" -> decide(sender, args, true);
            case "reject" -> decide(sender, args, false);
            case "reload" -> unary(sender, args, this::reload);
            case "tools" -> unary(sender, args, this::tools);
            default -> renderAndSend(sender, "usage");
        }
    }

    private void unary(CommandSender sender, String[] args, Consumer<CommandSender> action) {
        if (args.length != 1) {
            renderAndSend(sender, "usage");
            return;
        }
        action.accept(sender);
    }

    private void clear(CommandSender sender) {
        if (!permission(sender, "mineclaw.command.clear")) {
            return;
        }
        turns.clearSession();
        renderAndSend(sender, "clear_success");
    }

    private void decide(CommandSender sender, String[] args, boolean approve) {
        if (args.length != 2) {
            renderAndSend(sender, "approve_none");
            return;
        }
        if (!permission(sender, "mineclaw.command.approve")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            renderAndSend(sender, "player_only");
            return;
        }
        ApprovalManager.ApprovalOutcome result = approve
                ? commands.approve(player.getUniqueId(), args[1])
                : commands.reject(player.getUniqueId(), args[1]);
        if (result == ApprovalManager.ApprovalOutcome.NONE) {
            renderAndSend(sender, "approve_none");
        }
    }

    private void reload(CommandSender sender) {
        if (!permission(sender, "mineclaw.command.reload")) {
            return;
        }
        reload.accept(sender);
    }

    private void tools(CommandSender sender) {
        if (!permission(sender, "mineclaw.command.tools")) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return toolLoader.load(workspaceRoot, toolsFile, config.get().tools());
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, ioExecutor).whenComplete((catalog, failure) -> {
            if (failure != null) {
                send(sender, messages.render("tools_failure"));
                return;
            }
            showTools(sender, catalog);
        });
    }

    private void showTools(CommandSender sender, ToolCatalog catalog) {
        send(sender, messages.render("tools_header", Map.of(
                "valid", Integer.toString(catalog.enabledDefinitions().size()),
                "total", Integer.toString(catalog.definitions().size()))));
        catalog.definitions().forEach(tool -> send(sender, messages.render("tools_entry", Map.of(
                "name", tool.printableName(),
                "handler", tool.handlerName().isBlank() ? "-" : tool.handlerName(),
                "status", tool.status().name().toLowerCase(Locale.ROOT)))));
    }

    private boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        renderAndSend(sender, "no_permission");
        return false;
    }

    private void renderAndSend(CommandSender sender, String key) {
        renderAndSend(sender, key, Map.of());
    }

    private void renderAndSend(CommandSender sender, String key, Map<String, String> values) {
        CompletableFuture.supplyAsync(() -> messages.render(key, values), ioExecutor)
                .thenAccept(message -> send(sender, message));
    }

    private void send(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            channel.send(player, message);
        } else {
            tasks.global(() -> sender.sendMessage(message));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        CommandSender sender = source.getSender();
        ArrayList<String> values = new ArrayList<>();
        add(values, "clear", sender.hasPermission("mineclaw.command.clear"));
        add(values, "reload", sender.hasPermission("mineclaw.command.reload"));
        add(values, "tools", sender.hasPermission("mineclaw.command.tools"));
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("mineclaw.command.clear")
                || sender.hasPermission("mineclaw.command.approve")
                || sender.hasPermission("mineclaw.command.reload")
                || sender.hasPermission("mineclaw.command.tools");
    }

    private static void add(List<String> values, String value, boolean visible) {
        if (visible) {
            values.add(value);
        }
    }
}
