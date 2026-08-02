package cc.kites.mineclaw;

import cc.kites.mineclaw.api.ChatCompletionsClient;
import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.command.MineclawCommand;
import cc.kites.mineclaw.commandexec.CommandExecutor;
import cc.kites.mineclaw.commandexec.CommandRootIndex;
import cc.kites.mineclaw.commandexec.CommandRules;
import cc.kites.mineclaw.config.ConfigException;
import cc.kites.mineclaw.config.ConfigStore;
import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.listener.ApprovalGestureListener;
import cc.kites.mineclaw.listener.PublicChatListener;
import cc.kites.mineclaw.session.PublicSession;
import cc.kites.mineclaw.session.RateLimiter;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.support.MessageService;
import cc.kites.mineclaw.support.PlayerChannel;
import cc.kites.mineclaw.tool.EnvironmentTools;
import cc.kites.mineclaw.tool.ToolDispatcher;
import cc.kites.mineclaw.tool.WorkspaceFileTools;
import cc.kites.mineclaw.turn.TurnCoordinator;
import cc.kites.mineclaw.workspace.ToolCatalogLoader;
import cc.kites.mineclaw.workspace.WorkspaceService;
import net.kyori.adventure.text.Component;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent Paper/Folia entry point for Mineclaw. */
public final class MineclawPlugin extends JavaPlugin {
    private ConfigStore configStore;
    private PublicSession session;
    private RateLimiter rateLimiter;
    private FoliaTasks tasks;
    private MessageService messages;
    private PlayerChannel channel;
    private ApprovalManager approvals;
    private EnvironmentTools environmentTools;
    private TurnCoordinator turns;
    private ExecutorService ioExecutor;
    private final AtomicBoolean reloading = new AtomicBoolean();

    @Override
    public void onEnable() {
        session = new PublicSession();
        rateLimiter = new RateLimiter();
        tasks = new FoliaTasks(this);
        channel = new PlayerChannel(getServer(), tasks);
        ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Path root = getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            ensureSecretFile(root.resolve(".env"));
            seedResource("config.yml");
            messages = new MessageService(this);
            messages.seed();
        } catch (IOException | RuntimeException exception) {
            rejectEnable("Cannot seed Mineclaw data files", exception);
            return;
        }

        configStore = new ConfigStore(root.resolve("config.yml"));
        MineclawConfig config;
        try {
            config = configStore.loadInitial();
        } catch (ConfigException exception) {
            rejectEnable("Mineclaw configuration rejected", exception);
            return;
        }
        getLogger().setLevel(config.logging().level());

        try {
            seedResource("tools.yml");
            seedResource("skills/guide.md");
            seedResource("skills/command-safety.md");
            seedResource("skills/locate-structure.md");
            if (config.workspace().seedDefaults()) {
                seedResource("AGENTS.md");
            }

            WorkspaceService workspace = new WorkspaceService(root, getLogger());
            ToolCatalogLoader toolCatalog = new ToolCatalogLoader(getLogger()::warning);
            Path toolsFile = root.resolve("tools.yml");
            WorkspaceFileTools fileTools = new WorkspaceFileTools(root);
            environmentTools = new EnvironmentTools(getServer(), tasks);
            approvals = new ApprovalManager(tasks);
            AuditLogger audit = new AuditLogger(getLogger());
            CommandRootIndex commandRoots = new CommandRootIndex();
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                    event -> commandRoots.publish(event.registrar().getDispatcher()));
            CommandExecutor commandExecutor = new CommandExecutor(getServer(), tasks, approvals, messages, audit,
                    this::currentRules, ioExecutor, commandRoots);
            ToolDispatcher dispatcher = new ToolDispatcher(fileTools, environmentTools, commandExecutor, ioExecutor);
            turns = new TurnCoordinator(configStore, workspace, toolCatalog, toolsFile,
                    new ChatCompletionsClient(), dispatcher, session, rateLimiter, messages, channel, tasks,
                    ioExecutor, getLogger(), this::isEnabled);

            getServer().getPluginManager().registerEvents(
                    new PublicChatListener(configStore, turns, messages, channel, tasks, ioExecutor), this);
            getServer().getPluginManager().registerEvents(new ApprovalGestureListener(commandExecutor), this);
            registerCommand("mineclaw", "Manage Mineclaw", List.of(),
                    new MineclawCommand(configStore, turns, commandExecutor, toolCatalog, root, toolsFile,
                            messages, channel, tasks, ioExecutor, this::reloadFromCommand));
        } catch (IOException | RuntimeException exception) {
            rejectEnable("Mineclaw runtime initialization failed", exception);
            return;
        }
        getLogger().info("Mineclaw " + getPluginMeta().getVersion() + " enabled for Paper/Folia 26.2");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (turns != null) {
            turns.cancelAll();
        } else if (session != null) {
            session.clear();
        }
        if (approvals != null) {
            approvals.cancelAll();
        }
        if (environmentTools != null) {
            environmentTools.clear();
        }
        if (tasks != null) {
            tasks.cancelAll();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
    }

    private void reloadFromCommand(CommandSender sender) {
        if (!reloading.compareAndSet(false, true)) {
            CompletableFuture.supplyAsync(() -> messages.render("reload_in_progress"), ioExecutor)
                    .thenAccept(message -> send(sender, message));
            return;
        }
        // Freeze command dispatch for the entire parse/publish window and retire old approvals.
        approvals.invalidatePending();
        CompletableFuture.supplyAsync(() -> {
            try {
                return configStore.reload();
            } catch (ConfigException exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor).whenComplete((config, failure) -> {
            if (!isEnabled()) {
                return;
            }
            if (failure == null) {
                getLogger().setLevel(config.logging().level());
                approvals.invalidatePending();
                reloading.set(false);
                send(sender, messages.render("reload_success"));
                return;
            }
            Throwable cause = unwrap(failure);
            getLogger().severe("Mineclaw configuration snapshot reload rejected; disabling plugin: "
                    + safe(cause.getMessage()));
            send(sender, messages.render("reload_failure", Map.of("reason", safe(cause.getMessage()))));
            if (turns != null) {
                turns.cancelAll();
            }
            if (approvals != null) {
                approvals.cancelAll();
            }
            tasks.global(() -> getServer().getPluginManager().disablePlugin(this));
        });
    }

    private void seedResource(String resource) throws IOException {
        Path target = getDataFolder().toPath().resolve(resource);
        if (Files.notExists(target)) {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            saveResource(resource, false);
        }
    }

    private void ensureSecretFile(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(path, "MINECLAW_API_KEY=\n", StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException exception) {
            getLogger().warning("Could not restrict .env permissions: " + safe(exception.getMessage()));
        }
    }

    private void rejectEnable(String context, Exception exception) {
        getLogger().severe(context + "; plugin will not enable: " + safe(exception.getMessage()));
        if (session != null) {
            session.clear();
        }
        getServer().getPluginManager().disablePlugin(this);
    }

    private void send(CommandSender sender, Component message) {
        if (sender instanceof Player player && channel != null) {
            channel.send(player, message);
        } else if (tasks != null) {
            tasks.global(() -> sender.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    private static CommandRules rules(MineclawConfig config) {
        return new CommandRules(config.commands().runEnabled(), config.commands().playerWhitelist(),
                config.commands().consoleWhitelist());
    }

    private CommandRules currentRules() {
        return reloading.get()
                ? new CommandRules(false, List.of(), List.of())
                : rules(configStore.get());
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown error";
        }
        String oneLine = value.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
