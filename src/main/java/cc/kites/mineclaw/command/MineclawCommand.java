package cc.kites.mineclaw.command;

import cc.kites.mineclaw.commandexec.CommandExecutor;
import cc.kites.mineclaw.config.ControlPlaneStore;
import cc.kites.mineclaw.function.FunctionCatalog;
import cc.kites.mineclaw.function.FunctionCatalogLoader;
import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.session.ServerListenMode;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.support.AuditLogger;
import cc.kites.mineclaw.support.MessageService;
import cc.kites.mineclaw.support.PlayerChannel;
import cc.kites.mineclaw.turn.TurnCoordinator;
import cc.kites.mineclaw.workspace.ToolCatalog;
import cc.kites.mineclaw.workspace.ToolCatalogLoader;
import cc.kites.mineclaw.workspace.SkillFunctionReferenceValidator;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Paper command root for conversation, lifecycle and approval operations. */
public final class MineclawCommand implements BasicCommand {
    private final ControlPlaneStore config;
    private final TurnCoordinator turns;
    private final ServerListenMode listenMode;
    private final InteractionManager interactions;
    private final ToolCatalogLoader toolLoader;
    private final Path dataRoot;
    private final Path workspaceRoot;
    private final Path toolsFile;
    private final FunctionCatalogLoader functionLoader;
    private final Path functionsFile;
    private final SkillFunctionReferenceValidator skillReferences;
    private final MessageService messages;
    private final PlayerChannel channel;
    private final FoliaTasks tasks;
    private final Executor ioExecutor;
    private final Consumer<CommandSender> reload;
    private final JavaScriptWorkflowRuntime javascriptRuntime;
    private final AuditLogger audit;
    private final BooleanSupplier controlPlaneReady;

    public MineclawCommand(ControlPlaneStore config, TurnCoordinator turns,
                           ServerListenMode listenMode, CommandExecutor commands,
                           ToolCatalogLoader toolLoader, Path dataRoot, Path workspaceRoot,
                           Path toolsFile,
                           FunctionCatalogLoader functionLoader, Path functionsFile,
                           SkillFunctionReferenceValidator skillReferences,
                           MessageService messages,
                           PlayerChannel channel, FoliaTasks tasks, Executor ioExecutor,
                           Consumer<CommandSender> reload,
                           JavaScriptWorkflowRuntime javascriptRuntime,
                           AuditLogger audit,
                           BooleanSupplier controlPlaneReady) {
        this.config = Objects.requireNonNull(config, "config");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.listenMode = Objects.requireNonNull(listenMode, "listenMode");
        this.interactions = Objects.requireNonNull(commands, "commands").interactions();
        this.toolLoader = Objects.requireNonNull(toolLoader, "toolLoader");
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        this.toolsFile = Objects.requireNonNull(toolsFile, "toolsFile");
        this.functionLoader = Objects.requireNonNull(functionLoader, "functionLoader");
        this.functionsFile = Objects.requireNonNull(functionsFile, "functionsFile");
        this.skillReferences = Objects.requireNonNull(skillReferences, "skillReferences");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.reload = Objects.requireNonNull(reload, "reload");
        this.javascriptRuntime = javascriptRuntime;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.controlPlaneReady = Objects.requireNonNull(controlPlaneReady, "controlPlaneReady");
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
            case "compact" -> unary(sender, args, this::compact);
            case "listen" -> listen(sender, args);
            case "approve" -> decide(sender, args, true);
            case "reject" -> decide(sender, args, false);
            case "select" -> select(sender, args);
            case "reload" -> unary(sender, args, this::reload);
            case "tools" -> tools(sender, args);
            case "functions" -> functions(sender, args);
            case "model" -> model(sender, args);
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

    private void listen(CommandSender sender, String[] args) {
        if (!permission(sender, "mineclaw.command.listen")) {
            return;
        }
        if (args.length == 1) {
            renderAndSend(sender, listenMode.isEnabled()
                    ? "listen_status_on" : "listen_status_off");
            return;
        }
        if (args.length != 2) {
            renderAndSend(sender, "usage");
            return;
        }
        if (args[1].equalsIgnoreCase("on")) {
            listenMode.enable();
            renderAndSend(sender, "listen_enabled");
            return;
        }
        if (args[1].equalsIgnoreCase("off")) {
            listenMode.disable();
            renderAndSend(sender, "listen_disabled", Map.of(
                    "prefix", config.get().config().chat().publicPrefix()));
            return;
        }
        renderAndSend(sender, "usage");
    }

    private void compact(CommandSender sender) {
        if (!permission(sender, "mineclaw.command.compact")) {
            return;
        }
        if (!controlPlaneReady.getAsBoolean()) {
            renderAndSend(sender, "control_plane_unavailable");
            return;
        }
        TurnCoordinator.ManualCompactionRequest request = turns.compactSession();
        String admissionMessage = switch (request.admission()) {
            case STARTED -> "compact_started";
            case QUEUED -> "compact_queued";
            case ALREADY_PENDING -> "compact_already_pending";
        };
        send(sender, messages.render(admissionMessage));
        audit.log("context.compact.request", Map.of(
                "admission", request.admission().name().toLowerCase(Locale.ROOT)));
        request.completion().thenAccept(result -> {
            String outcomeMessage = switch (result.status()) {
                case SUCCESS -> "compact_success";
                case NO_HISTORY -> "compact_no_history";
                case FAILED -> "compact_failure";
                case CANCELLED -> "compact_cancelled";
            };
            send(sender, messages.render(outcomeMessage, Map.of(
                    "model", result.model().isBlank() ? "-" : result.model(),
                    "compacted_turns", Integer.toString(result.compactedTurns()),
                    "retained_turns", Integer.toString(result.retainedTurns()))));
        });
    }

    private void decide(CommandSender sender, String[] args, boolean approve) {
        if (args.length != 2 && !(approve && args.length == 1)) {
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
        InteractionManager.Outcome result = completeDecision(
                interactions, player.getUniqueId(), args, approve);
        if (result == InteractionManager.Outcome.NONE) {
            renderAndSend(sender, "approve_none");
        }
    }

    static InteractionManager.Outcome completeDecision(
            InteractionManager interactions, UUID playerId, String[] args, boolean approve) {
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(args, "args");
        if (approve && args.length == 1) {
            return interactions.approveCurrentConfirm(playerId);
        }
        if (args.length != 2) {
            return InteractionManager.Outcome.NONE;
        }
        return approve
                ? interactions.approve(playerId, args[1])
                : interactions.reject(playerId, args[1]);
    }

    private void select(CommandSender sender, String[] args) {
        if (args.length != 3) {
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
        if (interactions.select(player.getUniqueId(), args[1], args[2])
                == InteractionManager.Outcome.NONE) {
            renderAndSend(sender, "approve_none");
        }
    }

    private void reload(CommandSender sender) {
        if (!permission(sender, "mineclaw.command.reload")) {
            return;
        }
        reload.accept(sender);
    }

    private void tools(CommandSender sender, String[] args) {
        boolean validateOnly = args.length == 2 && args[1].equalsIgnoreCase("validate");
        if (args.length != 1 && !validateOnly) {
            renderAndSend(sender, "usage");
            return;
        }
        if (!permission(sender, "mineclaw.command.tools")) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                var snapshot = config.get().config();
                return toolLoader.load(dataRoot, toolsFile, snapshot.tools());
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, ioExecutor).whenComplete((catalog, failure) -> {
            if (failure != null) {
                send(sender, messages.render("tools_failure"));
                return;
            }
            showTools(sender, catalog);
            if (validateOnly) {
                send(sender, messages.render("tools_validate_success"));
            }
        });
    }

    private void showTools(CommandSender sender, ToolCatalog catalog) {
        send(sender, messages.render("tools_header", Map.of(
                "valid", Integer.toString(catalog.enabledDefinitions().size()),
                "total", Integer.toString(catalog.definitions().size()))));
        catalog.definitions().forEach(tool -> {
            send(sender, messages.render("tools_entry", Map.of(
                    "handler", tool.printableHandler(),
                    "payload", tool.payloadType().isBlank() ? "-" : tool.payloadType(),
                    "status", tool.status().name().toLowerCase(Locale.ROOT),
                    "diagnostic", tool.diagnostic().orElse("-"))));
        });
    }

    private void functions(CommandSender sender, String[] args) {
        boolean validateOnly = args.length == 2 && args[1].equalsIgnoreCase("validate");
        if (args.length != 1 && !validateOnly) {
            renderAndSend(sender, "usage");
            return;
        }
        if (!permission(sender, "mineclaw.command.functions")) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                var snapshot = config.get().config();
                ToolCatalog tools = toolLoader.load(dataRoot, toolsFile, snapshot.tools());
                FunctionCatalog catalog = functionLoader.load(dataRoot, functionsFile,
                        FunctionCatalogLoader.nativeCapabilityAllowlist(tools));
                SkillFunctionReferenceValidator.Report references = validateOnly
                        ? skillReferences.validate(workspaceRoot.resolve("skills"), catalog) : null;
                return new FunctionDiagnostics(catalog, references);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, ioExecutor).whenComplete((diagnostics, failure) -> {
            if (failure != null) {
                send(sender, messages.render("functions_failure"));
                return;
            }
            showFunctions(sender, diagnostics.catalog());
            if (diagnostics.references() != null) {
                diagnostics.references().diagnostics().forEach(entry -> send(sender,
                        Component.text("[" + entry.code() + "] "
                                + (entry.skillPath() == null ? "" : entry.skillPath() + ": ")
                                + (entry.functionName() == null ? "" : entry.functionName() + " ")
                                + entry.message())));
                send(sender, messages.render("functions_validate_success"));
            }
        });
    }

    private void showFunctions(CommandSender sender, FunctionCatalog catalog) {
        send(sender, messages.render("functions_header", Map.of(
                "valid", Integer.toString(catalog.enabledDefinitions().size()),
                "total", Integer.toString(catalog.definitions().size()))));
        catalog.definitions().forEach(function -> {
            String capabilities = function.capabilities().isEmpty()
                    ? "-" : String.join(",", function.capabilities());
            long running = javascriptRuntime == null ? 0L
                    : javascriptRuntime.activeInvocationCount(function.name());
            send(sender, messages.render("functions_entry", Map.of(
                    "name", function.printableName(),
                    "status", function.status().name().toLowerCase(Locale.ROOT),
                    "schema", function.compiledParameters().isPresent() ? "compiled" : "invalid",
                    "hash", function.shortScriptHash().orElse("-"),
                    "capabilities", capabilities,
                    "diagnostic", function.diagnostic().orElse("-"),
                    "running", Long.toString(running))));
        });
    }

    private void model(CommandSender sender, String[] args) {
        if (!permission(sender, "mineclaw.command.model")) {
            return;
        }
        if (!controlPlaneReady.getAsBoolean()) {
            renderAndSend(sender, "control_plane_unavailable");
            return;
        }
        if (args.length == 1) {
            send(sender, messages.render("model_current", Map.of(
                    "model", turns.currentModelReference(),
                    "source", turns.modelIsOverride() ? "runtime override" : "providers.yml default",
                    "active", turns.hasActiveTurn() ? "；当前活动 Turn 继续使用其创建时的模型" : "")));
            return;
        }
        if (args.length != 2) {
            renderAndSend(sender, "usage");
            return;
        }
        if (args[1].equalsIgnoreCase("list")) {
            send(sender, messages.render("model_list_header"));
            String current = turns.currentModelReference();
            String fallback = turns.defaultModelReference();
            turns.modelReferences().forEach(reference -> send(sender, messages.render("model_list_entry", Map.of(
                    "model", reference,
                    "current", reference.equals(current) ? " current" : "",
                    "default", reference.equals(fallback) ? " default" : ""))));
            return;
        }
        String before = turns.currentModelReference();
        if (args[1].equalsIgnoreCase("default")) {
            turns.resetModelOverride();
            String after = turns.currentModelReference();
            auditModel(sender, before, after);
            send(sender, messages.render("model_default", Map.of("model", after)));
            return;
        }
        if (!turns.selectModel(args[1])) {
            send(sender, messages.render("model_unknown", Map.of("model", args[1])));
            return;
        }
        String after = turns.currentModelReference();
        auditModel(sender, before, after);
        send(sender, messages.render("model_selected", Map.of("model", after)));
    }

    private void auditModel(CommandSender sender, String before, String after) {
        audit.log("model.switch", Map.of("actor", sender.getName(), "old_model", before,
                "new_model", after));
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
        CommandSender sender = source.getSender();
        if (args.length == 2 && args[0].equalsIgnoreCase("listen")
                && sender.hasPermission("mineclaw.command.listen")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tools")
                && sender.hasPermission("mineclaw.command.tools")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return "validate".startsWith(prefix) ? List.of("validate") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("functions")
                && sender.hasPermission("mineclaw.command.functions")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return "validate".startsWith(prefix) ? List.of("validate") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("model")
                && sender.hasPermission("mineclaw.command.model")) {
            String prefix = args[1];
            ArrayList<String> values = new ArrayList<>(List.of("list", "default"));
            values.addAll(turns.modelReferences());
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length > 1) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        add(values, "listen", sender.hasPermission("mineclaw.command.listen"));
        add(values, "clear", sender.hasPermission("mineclaw.command.clear"));
        add(values, "compact", sender.hasPermission("mineclaw.command.compact"));
        add(values, "reload", sender.hasPermission("mineclaw.command.reload"));
        add(values, "tools", sender.hasPermission("mineclaw.command.tools"));
        add(values, "functions", sender.hasPermission("mineclaw.command.functions"));
        add(values, "model", sender.hasPermission("mineclaw.command.model"));
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("mineclaw.command.listen")
                || sender.hasPermission("mineclaw.command.clear")
                || sender.hasPermission("mineclaw.command.compact")
                || sender.hasPermission("mineclaw.command.approve")
                || sender.hasPermission("mineclaw.command.reload")
                || sender.hasPermission("mineclaw.command.tools")
                || sender.hasPermission("mineclaw.command.functions")
                || sender.hasPermission("mineclaw.command.model");
    }

    private static void add(List<String> values, String value, boolean visible) {
        if (visible) {
            values.add(value);
        }
    }

    private record FunctionDiagnostics(
            FunctionCatalog catalog,
            SkillFunctionReferenceValidator.Report references
    ) { }
}
