package cc.kites.mineclaw;

import cc.kites.mineclaw.api.ChatCompletionsClient;
import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.command.MineclawCommand;
import cc.kites.mineclaw.commandexec.BukkitCommandRuntime;
import cc.kites.mineclaw.commandexec.CommandExecutor;
import cc.kites.mineclaw.commandexec.CommandRootIndex;
import cc.kites.mineclaw.commandexec.CommandRules;
import cc.kites.mineclaw.commandexec.ScriptCommandDispatcher;
import cc.kites.mineclaw.config.ConfigException;
import cc.kites.mineclaw.config.ControlPlaneSnapshot;
import cc.kites.mineclaw.config.ControlPlaneStore;
import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.function.FunctionCatalogLoader;
import cc.kites.mineclaw.function.FunctionSourcePreparer;
import cc.kites.mineclaw.javascript.JavaScriptLimits;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.listener.ApprovalGestureListener;
import cc.kites.mineclaw.listener.InteractionLifecycleListener;
import cc.kites.mineclaw.listener.PublicChatListener;
import cc.kites.mineclaw.session.PublicSession;
import cc.kites.mineclaw.session.RateLimiter;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.support.MessageService;
import cc.kites.mineclaw.support.PlayerChannel;
import cc.kites.mineclaw.tool.EnvironmentTools;
import cc.kites.mineclaw.tool.JavaScriptOperationRouter;
import cc.kites.mineclaw.tool.ToolDispatcher;
import cc.kites.mineclaw.tool.WorkspaceFileTools;
import cc.kites.mineclaw.turn.TurnCoordinator;
import cc.kites.mineclaw.workspace.ToolCatalogLoader;
import cc.kites.mineclaw.workspace.SkillFunctionReferenceValidator;
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
import java.util.LinkedHashMap;
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
    private ControlPlaneStore controlPlane;
    private PublicSession session;
    private RateLimiter rateLimiter;
    private FoliaTasks tasks;
    private MessageService messages;
    private PlayerChannel channel;
    private ApprovalManager approvals;
    private EnvironmentTools environmentTools;
    private JavaScriptWorkflowRuntime javascriptRuntime;
    private FunctionCatalogLoader functionCatalogLoader;
    private TurnCoordinator turns;
    private ExecutorService ioExecutor;
    private final AtomicBoolean reloading = new AtomicBoolean();
    private final AtomicBoolean controlPlaneReady = new AtomicBoolean();

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
            seedResource("providers.yml");
            seedResource("whitelist.yml");
            messages = new MessageService(this);
            messages.seed();
        } catch (IOException | RuntimeException exception) {
            rejectEnable("Cannot seed Mineclaw data files", exception);
            return;
        }

        controlPlane = new ControlPlaneStore(root);
        MineclawConfig config;
        try {
            config = controlPlane.loadInitial().config();
            controlPlaneReady.set(true);
        } catch (ConfigException exception) {
            config = controlPlane.initializeUnavailable().config();
            getLogger().severe("Mineclaw control plane rejected; AI conversation is disabled until a "
                    + "successful /mineclaw reload: " + safe(exception.getMessage()));
        }
        getLogger().setLevel(config.logging().level());

        try {
            Path workspaceRoot = root.resolve("workspace");
            seedResource("tools.yml");
            seedResource("functions.yml");
            seedResource("workspace/skills/locate-structure.md");
            seedResource("workspace/skills/self-potion-effect.md");
            if (config.workspace().seedDefaults()) {
                seedResource("workspace/AGENTS.md");
            }

            AuditLogger audit = new AuditLogger(getLogger());
            WorkspaceService workspace = new WorkspaceService(workspaceRoot, getLogger());
            javascriptRuntime = createJavaScriptRuntime(config.javascript(), audit);
            JavaScriptWorkflowRuntime availableJavaScript = javascriptRuntime;
            ToolCatalogLoader toolCatalog = new ToolCatalogLoader(getLogger()::warning);
            Path toolsFile = root.resolve("tools.yml");
            Path functionsFile = root.resolve(FunctionCatalogLoader.FUNCTIONS_FILE_NAME);
            FunctionSourcePreparer functionSourcePreparer = availableJavaScript == null
                    ? FunctionSourcePreparer.unavailable() : availableJavaScript::validateSource;
            functionCatalogLoader = new FunctionCatalogLoader(getLogger()::warning,
                    functionSourcePreparer, Set.of(), functionLimits(config));
            WorkspaceFileTools fileTools = new WorkspaceFileTools(workspaceRoot);
            environmentTools = new EnvironmentTools(getServer(), tasks);
            approvals = new ApprovalManager(tasks);
            CommandRootIndex commandRoots = new CommandRootIndex();
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                    event -> commandRoots.publish(event.registrar().getDispatcher()));
            BukkitCommandRuntime commandRuntime = new BukkitCommandRuntime(
                    getServer(), tasks, messages, ioExecutor, commandRoots);
            CommandExecutor commandExecutor = new CommandExecutor(
                    commandRuntime, approvals, audit, this::currentRules);
            JavaScriptOperationRouter operationRouter = availableJavaScript == null ? null
                    : new JavaScriptOperationRouter(commandRuntime, approvals.interactions(),
                    new ScriptCommandDispatcher(commandRuntime, audit), audit,
                    availableJavaScript::isActive);
            ToolDispatcher dispatcher = new ToolDispatcher(fileTools, environmentTools, commandExecutor,
                    ioExecutor, availableJavaScript, operationRouter, audit);
            turns = new TurnCoordinator(controlPlane, root, workspace, toolCatalog, toolsFile,
                    functionCatalogLoader, functionsFile,
                    new ChatCompletionsClient(), dispatcher, session, rateLimiter, messages, channel, tasks,
                    ioExecutor, getLogger(), () -> isEnabled() && controlPlaneReady.get());

            getServer().getPluginManager().registerEvents(
                    new PublicChatListener(controlPlane, turns, messages, channel, tasks, ioExecutor), this);
            getServer().getPluginManager().registerEvents(new ApprovalGestureListener(commandExecutor), this);
            getServer().getPluginManager().registerEvents(new InteractionLifecycleListener(approvals), this);
            registerCommand("mineclaw", "Manage Mineclaw", List.of(),
                    new MineclawCommand(controlPlane, turns, commandExecutor, toolCatalog,
                            root, workspaceRoot, toolsFile,
                            functionCatalogLoader, functionsFile, new SkillFunctionReferenceValidator(),
                            messages, channel, tasks, ioExecutor, this::reloadFromCommand,
                            availableJavaScript, audit, controlPlaneReady::get));
        } catch (IOException | RuntimeException exception) {
            rejectEnable("Mineclaw runtime initialization failed", exception);
            return;
        }
        getLogger().info("Mineclaw " + getPluginMeta().getVersion() + " enabled for Paper/Folia 26.2");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        // Close script admission and side-effect gates before any interaction completion can resume them.
        if (javascriptRuntime != null) {
            javascriptRuntime.close();
        }
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
        CompletableFuture.supplyAsync(() -> {
            try {
                return controlPlane.reload();
            } catch (ConfigException exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor).whenComplete((snapshot, failure) -> {
            if (!isEnabled()) {
                return;
            }
            if (failure == null) {
                MineclawConfig config = snapshot.config();
                getLogger().setLevel(config.logging().level());
                if (functionCatalogLoader != null) {
                    functionCatalogLoader.reconfigure(functionLimits(config));
                }
                if (javascriptRuntime != null) {
                    javascriptRuntime.reconfigure(javascriptLimits(config.javascript()));
                }
                turns.resetModelOverride();
                controlPlaneReady.set(true);
                reloading.set(false);
                send(sender, messages.render("reload_success"));
                return;
            }
            Throwable cause = unwrap(failure);
            getLogger().severe("Mineclaw control-plane reload rejected; keeping the previous snapshot: "
                    + safe(cause.getMessage()));
            send(sender, messages.render("reload_failure", Map.of("reason", safe(cause.getMessage()))));
            reloading.set(false);
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

    private JavaScriptWorkflowRuntime createJavaScriptRuntime(
            MineclawConfig.JavaScript settings,
            AuditLogger audit
    ) {
        try {
            return new JavaScriptWorkflowRuntime(javascriptLimits(settings), event -> {
                LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
                fields.put("invocation_id", event.invocationId());
                fields.put("function", event.functionName());
                fields.put("script_hash", event.scriptHash());
                fields.put("api_version", event.apiVersion());
                fields.put("turn_player", event.playerName());
                fields.put("operation", event.operation());
                fields.put("bundled_action", event.action());
                fields.put("phase", event.phase());
                fields.put("result", event.status());
                if (!event.reason().isBlank()) {
                    fields.put("reason", event.reason());
                }
                audit.log("javascript.runtime", fields);
            });
        } catch (RuntimeException | LinkageError failure) {
            getLogger().severe("GraalJS runtime is unavailable; JavaScript Functions will be unavailable: "
                    + safe(failure.getMessage()));
            return null;
        }
    }

    private static JavaScriptLimits javascriptLimits(MineclawConfig.JavaScript settings) {
        return new JavaScriptLimits(settings.maxSourceChars(), settings.maxOperationsPerInvocation(),
                settings.maxConcurrentOperations(), settings.maxPendingApprovals(),
                settings.maxSyncSegmentMillis(), settings.maxWorkflowMillis(), settings.maxResultChars(),
                settings.maxResultDepth(), settings.maxResultMembers());
    }

    private static FunctionCatalogLoader.Limits functionLimits(MineclawConfig config) {
        MineclawConfig.Functions functions = config.functions();
        return new FunctionCatalogLoader.Limits(functions.maxFileChars(), functions.maxEntries(),
                functions.maxDescriptionChars(), functions.maxArgumentChars(),
                functions.maxArgumentDepth(), functions.maxArgumentMembers(),
                functions.maxValidationViolations(), config.javascript().maxSourceChars());
    }

    private CommandRules currentRules() {
        return reloading.get() || !controlPlaneReady.get()
                ? new CommandRules(false, List.of(), List.of())
                : controlPlane.get().whitelist().rules();
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
