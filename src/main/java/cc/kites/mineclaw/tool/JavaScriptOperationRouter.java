package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.commandexec.CommandRuntime;
import cc.kites.mineclaw.commandexec.ScriptCommandDispatcher;
import cc.kites.mineclaw.interaction.InteractionManager;
import cc.kites.mineclaw.javascript.OperationCall;
import cc.kites.mineclaw.javascript.OperationHandle;
import cc.kites.mineclaw.javascript.OperationHost;
import cc.kites.mineclaw.javascript.OperationResult;
import cc.kites.mineclaw.support.AuditLogger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Validates and executes Function Bundled API operations outside Graal contexts. */
public final class JavaScriptOperationRouter {
    private static final Pattern NATIVE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String CALL_FUNCTION_TOOL = "call_function";
    private static final long DEFAULT_APPROVAL_TIMEOUT_MILLIS = 60_000L;
    private static final long MIN_APPROVAL_TIMEOUT_MILLIS = 1_000L;
    private static final long MAX_APPROVAL_TIMEOUT_MILLIS = 300_000L;

    private final CommandRuntime commandRuntime;
    private final InteractionManager interactions;
    private final ScriptCommandDispatcher commands;
    private final AuditLogger audit;
    private final Predicate<String> invocationActive;

    public JavaScriptOperationRouter(
            CommandRuntime commandRuntime,
            InteractionManager interactions,
            ScriptCommandDispatcher commands,
            AuditLogger audit,
            Predicate<String> invocationActive
    ) {
        this.commandRuntime = Objects.requireNonNull(commandRuntime, "commandRuntime");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.invocationActive = Objects.requireNonNull(invocationActive, "invocationActive");
    }

    public OperationHost host(InvocationActor actor, NativeInvoker nativeInvoker) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(nativeInvoker, "nativeInvoker");
        return call -> switch (call.action()) {
            case "approval.request" -> approval(call, actor);
            case "command.dispatch" -> command(call, actor);
            case "native_tool.call" -> nativeTool(call, actor, nativeInvoker);
            default -> new OperationHandle(
                    CompletableFuture.failedFuture(new IllegalArgumentException("unknown Bundled API action")),
                    () -> { });
        };
    }

    private OperationHandle approval(OperationCall call, InvocationActor actor) {
        final ApprovalInput input;
        try {
            input = ApprovalInput.parse(call.input());
        } catch (IllegalArgumentException exception) {
            auditStart(call, actor, "approval.request", "<invalid>");
            auditResult(call, actor, "approval.request", "invalid");
            return OperationHandle.completed(invalid("invalid_approval_request",
                    safeInputMessage(exception), true));
        }
        auditStart(call, actor, "approval.request", input.player());
        if (!invocationActive.test(call.invocationId())) {
            auditResult(call, actor, "approval.request", "cancelled");
            return OperationHandle.completed(cancelled(true));
        }

        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        AtomicReference<InteractionManager.Registration> registration = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        long expectedGeneration = interactions.generation();

        commandRuntime.findOnlinePlayerExact(input.player()).whenComplete((found, lookupFailure) -> {
            if (lookupFailure != null) {
                completeApprovalFailure(completion, call, actor, lookupFailure);
                return;
            }
            if (cancelled.get() || !invocationActive.test(call.invocationId())) {
                completeApproval(completion, call, actor, cancelled(true));
                return;
            }
            Optional<CommandRuntime.OnlinePlayer> target = found == null ? Optional.empty() : found;
            if (target.isEmpty()) {
                completeApproval(completion, call, actor, failure("player_offline", "player_offline",
                        "target player is not online", true));
                return;
            }

            String token = UUID.randomUUID().toString();
            InteractionManager.Request request = new InteractionManager.Request(
                    target.orElseThrow().uuid(), target.orElseThrow().name(), token,
                    call.invocationId(), call.invocationId() + ':' + call.sequence(), input.interaction(),
                    Duration.ofMillis(input.timeoutMillis()), expectedGeneration);
            InteractionManager.Registration reserved = interactions.reserve(request);
            registration.set(reserved);
            if (!reserved.accepted()) {
                reserved.result().whenComplete((result, failure) -> completeInteraction(
                        completion, call, actor, result, failure));
                return;
            }
            if (cancelled.get() || !invocationActive.test(call.invocationId())) {
                reserved.cancel(InteractionManager.Result.cancelled(
                        "invocation_cancelled", "Function invocation was cancelled"));
                completeApproval(completion, call, actor, cancelled(true));
                return;
            }

            reserved.result().whenComplete((result, failure) -> completeInteraction(
                    completion, call, actor, result, failure));
            commandRuntime.sendInteractionPromptGuarded(
                            target.orElseThrow(), token, input.interaction(),
                            () -> !cancelled.get() && invocationActive.test(call.invocationId()))
                    .whenComplete((sent, promptFailure) -> {
                        if (cancelled.get() || !invocationActive.test(call.invocationId())) {
                            reserved.cancel(InteractionManager.Result.cancelled(
                                    "invocation_cancelled", "Function invocation was cancelled"));
                        } else if (promptFailure != null) {
                            reserved.abort(InteractionManager.Result.playerOffline());
                        } else if (!Boolean.TRUE.equals(sent)) {
                            reserved.abort(InteractionManager.Result.playerOffline());
                        } else if (!reserved.activate()) {
                            reserved.cancel(InteractionManager.Result.cancelled(
                                    "interaction_invalidated", "interaction was invalidated before activation"));
                        }
                    });
        });

        return new OperationHandle(completion, () -> {
            cancelled.set(true);
            InteractionManager.Registration current = registration.get();
            if (current != null) {
                current.cancel(InteractionManager.Result.cancelled(
                        "invocation_cancelled", "Function invocation was cancelled"));
            }
            if (completion.complete(cancelled(true))) {
                auditResult(call, actor, "approval.request", "cancelled");
            }
        });
    }

    private OperationHandle command(OperationCall call, InvocationActor actor) {
        auditStart(call, actor, "command.dispatch", commandTarget(call.input()));
        CompletionStage<ScriptCommandDispatcher.Result> dispatched = commands.dispatch(
                call.input(), () -> invocationActive.test(call.invocationId()),
                new ScriptCommandDispatcher.AuditContext(call.invocationId(), call.functionName(),
                        call.scriptHash(), actor.playerId(), actor.playerName()));
        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        dispatched.whenComplete((result, failure) -> {
            if (failure != null) {
                if (completion.completeExceptionally(failure)) {
                    auditResult(call, actor, "command.dispatch", "terminal_error");
                }
            } else if (result == null) {
                if (completion.completeExceptionally(
                        new IllegalStateException("command dispatcher returned no result"))) {
                    auditResult(call, actor, "command.dispatch", "terminal_error");
                }
            } else {
                OperationResult operation = new OperationResult(result.status(), result.output());
                if (completion.complete(operation)) {
                    auditResult(call, actor, "command.dispatch", result.status());
                }
            }
        });
        return new OperationHandle(completion, () -> {
            if (completion.complete(cancelled(false))) {
                auditResult(call, actor, "command.dispatch", "cancelled");
            }
        });
    }

    private OperationHandle nativeTool(OperationCall call, InvocationActor actor, NativeInvoker nativeInvoker) {
        JsonObject input = call.input();
        try {
            exactMembers(input, Set.of("name", "arguments"));
            String name = text(input, "name", 1, 64, false);
            if (!NATIVE_TOOL_NAME.matcher(name).matches()) {
                throw invalidInput("name must match " + NATIVE_TOOL_NAME.pattern());
            }
            if (CALL_FUNCTION_TOOL.equals(name)) {
                throw invalidInput("call_function cannot be invoked through native_tool.call");
            }
            JsonElement arguments = input.get("arguments");
            if (arguments == null || !arguments.isJsonObject()) {
                throw invalidInput("arguments must be an object");
            }
            auditStart(call, actor, "native_tool.call", name);
            OperationHandle delegated = nativeInvoker.invoke(name, arguments.getAsJsonObject());
            CompletableFuture<OperationResult> completion = new CompletableFuture<>();
            delegated.completion().whenComplete((result, failure) -> {
                if (failure != null) {
                    if (completion.completeExceptionally(failure)) {
                        auditResult(call, actor, "native_tool.call", "terminal_error");
                    }
                } else {
                    if (completion.complete(result)) {
                        auditResult(call, actor, "native_tool.call",
                                result == null ? "terminal_error" : result.status());
                    }
                }
            });
            return new OperationHandle(completion, () -> {
                try {
                    delegated.cancel();
                } finally {
                    if (completion.complete(cancelled(false))) {
                        auditResult(call, actor, "native_tool.call", "cancelled");
                    }
                }
            });
        } catch (InvalidInput exception) {
            auditStart(call, actor, "native_tool.call", "<invalid>");
            auditResult(call, actor, "native_tool.call", "invalid");
            return OperationHandle.completed(invalid("invalid_native_tool_request", exception.getMessage(), false));
        }
    }

    private void completeInteraction(CompletableFuture<OperationResult> completion,
                                     OperationCall call, InvocationActor actor,
                                     InteractionManager.Result result, Throwable failure) {
        if (failure != null) {
            if (completion.completeExceptionally(failure)) {
                auditResult(call, actor, "approval.request", "terminal_error");
            }
            return;
        }
        if (result == null) {
            if (completion.completeExceptionally(
                    new IllegalStateException("interaction returned no result"))) {
                auditResult(call, actor, "approval.request", "terminal_error");
            }
            return;
        }
        JsonObject output = new JsonObject();
        if (result.status() == InteractionManager.Status.APPROVED) {
            if (result.value() instanceof Boolean bool) {
                output.addProperty("value", bool);
            } else if (result.value() instanceof String string) {
                output.addProperty("value", string);
            } else {
                if (completion.completeExceptionally(
                        new IllegalStateException("approved interaction returned an invalid value"))) {
                    auditResult(call, actor, "approval.request", "terminal_error");
                }
                return;
            }
        } else {
            output.add("value", JsonNull.INSTANCE);
            output.addProperty("error_code", result.errorCode());
            output.addProperty("message", result.message());
        }
        OperationResult operation = new OperationResult(result.status().wireName(), output);
        if (completion.complete(operation)) {
            auditResult(call, actor, "approval.request", operation.status());
        }
    }

    private void completeApproval(
            CompletableFuture<OperationResult> completion,
            OperationCall call,
            InvocationActor actor,
            OperationResult result
    ) {
        if (completion.complete(result)) {
            auditResult(call, actor, "approval.request", result.status());
        }
    }

    private void completeApprovalFailure(
            CompletableFuture<OperationResult> completion,
            OperationCall call,
            InvocationActor actor,
            Throwable failure
    ) {
        if (completion.completeExceptionally(failure)) {
            auditResult(call, actor, "approval.request", "terminal_error");
        }
    }

    private void auditStart(OperationCall call, InvocationActor actor, String action, String target) {
        LinkedHashMap<String, Object> fields = commonAudit(call, actor);
        fields.put("operation", call.sequence());
        fields.put("bundled_action", action);
        fields.put("capability", capabilitySummary(call));
        fields.put("target", target);
        if (action.equals("approval.request")) {
            fields.put("interaction_type", interactionType(call.input()));
        }
        fields.put("result", "started");
        audit.log("javascript.operation", fields);
    }

    private void auditResult(OperationCall call, InvocationActor actor, String action, String status) {
        LinkedHashMap<String, Object> fields = commonAudit(call, actor);
        fields.put("operation", call.sequence());
        fields.put("bundled_action", action);
        fields.put("capability", capabilitySummary(call));
        if (action.equals("approval.request")) {
            fields.put("interaction_type", interactionType(call.input()));
        }
        fields.put("result", status);
        audit.log("javascript.operation", fields);
    }

    private static LinkedHashMap<String, Object> commonAudit(OperationCall call, InvocationActor actor) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("invocation_id", call.invocationId());
        fields.put("function", call.functionName());
        fields.put("script_hash", call.scriptHash());
        fields.put("turn_player", actor.playerName() + "(" + actor.playerId() + ")");
        return fields;
    }

    private static String commandTarget(JsonObject input) {
        JsonElement executor = input.get("executor");
        if (executor != null && executor.isJsonObject()) {
            JsonElement type = executor.getAsJsonObject().get("type");
            if (type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()) {
                String value = type.getAsString();
                return value.length() <= 32 ? value : value.substring(0, 32);
            }
        }
        return "<invalid>";
    }

    private static String capabilitySummary(OperationCall call) {
        return switch (call.action()) {
            case "approval.request" -> "approval.request";
            case "command.dispatch" -> {
                String target = commandTarget(call.input());
                yield target.equals("console") || target.equals("player")
                        ? "command.dispatch." + target : "<invalid>";
            }
            case "native_tool.call" -> {
                JsonElement name = call.input().get("name");
                yield name instanceof JsonPrimitive primitive && primitive.isString()
                        && NATIVE_TOOL_NAME.matcher(primitive.getAsString()).matches()
                        && !CALL_FUNCTION_TOOL.equals(primitive.getAsString())
                        ? "native_tool.call." + primitive.getAsString() : "<invalid>";
            }
            default -> "<invalid>";
        };
    }

    private static String interactionType(JsonObject input) {
        JsonElement interaction = input.get("interaction");
        if (interaction != null && interaction.isJsonObject()) {
            JsonElement type = interaction.getAsJsonObject().get("type");
            if (type instanceof JsonPrimitive primitive && primitive.isString()) {
                String value = primitive.getAsString();
                return value.equals("confirm") || value.equals("select") ? value : "<invalid>";
            }
        }
        return "<invalid>";
    }

    private static OperationResult invalid(String code, String message, boolean approval) {
        JsonObject output = failureOutput(code, message, approval);
        return new OperationResult("invalid", output);
    }

    private static OperationResult cancelled(boolean approval) {
        return new OperationResult("cancelled", failureOutput(
                "invocation_cancelled", "Function invocation was cancelled", approval));
    }

    private static OperationResult failure(String status, String code, String message, boolean approval) {
        return new OperationResult(status, failureOutput(code, message, approval));
    }

    private static JsonObject failureOutput(String code, String message, boolean approval) {
        JsonObject output = new JsonObject();
        if (approval) {
            output.add("value", JsonNull.INSTANCE);
        }
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        return output;
    }

    public record InvocationActor(UUID playerId, String playerName) {
        public InvocationActor {
            Objects.requireNonNull(playerId, "playerId");
            playerName = Objects.requireNonNull(playerName, "playerName");
        }
    }

    @FunctionalInterface
    public interface NativeInvoker {
        /**
         * Resolves only an enabled, non-Function native Tool. Implementations must reject every
         * name or alias whose handler is {@code call_function}; the router also rejects the
         * canonical name before this boundary as defense in depth.
         */
        OperationHandle invoke(String name, JsonObject arguments);
    }

    private record ApprovalInput(
            String player,
            InteractionManager.Interaction interaction,
            long timeoutMillis
    ) {
        private static ApprovalInput parse(JsonObject input) {
            exactMembers(input, Set.of("player", "interaction", "timeout_ms"),
                    Set.of("player", "interaction"));
            String player = text(input, "player", 1, 64, false);
            if (player.codePoints().anyMatch(JavaScriptOperationRouter::space)) {
                throw invalidInput("player must not contain whitespace");
            }
            JsonElement rawInteraction = input.get("interaction");
            if (rawInteraction == null || !rawInteraction.isJsonObject()) {
                throw invalidInput("interaction must be an object");
            }
            JsonObject object = rawInteraction.getAsJsonObject();
            String type = text(object, "type", 1, 16, false);
            String title = text(object, "title", 1, 64, false);
            String message = text(object, "message", 1, 512, false);
            InteractionManager.Interaction interaction;
            if (type.equals("confirm")) {
                exactMembers(object, Set.of("type", "title", "message"));
                interaction = new InteractionManager.Confirm(title, message);
            } else if (type.equals("select")) {
                exactMembers(object, Set.of("type", "title", "message", "options"));
                JsonElement rawOptions = object.get("options");
                if (rawOptions == null || !rawOptions.isJsonArray()) {
                    throw invalidInput("interaction.options must be an array");
                }
                JsonArray array = rawOptions.getAsJsonArray();
                List<InteractionManager.Option> options = new ArrayList<>(array.size());
                for (int index = 0; index < array.size(); index++) {
                    JsonElement rawOption = array.get(index);
                    if (!rawOption.isJsonObject()) {
                        throw invalidInput("interaction.options[" + index + "] must be an object");
                    }
                    JsonObject option = rawOption.getAsJsonObject();
                    exactMembers(option, Set.of("id", "label"));
                    try {
                        options.add(new InteractionManager.Option(
                                text(option, "id", 1, 64, false),
                                text(option, "label", 1, 128, false)));
                    } catch (IllegalArgumentException exception) {
                        throw invalidInput(exception.getMessage());
                    }
                }
                try {
                    interaction = new InteractionManager.Select(title, message, options);
                } catch (IllegalArgumentException exception) {
                    throw invalidInput(exception.getMessage());
                }
            } else {
                throw invalidInput("interaction.type must be confirm or select");
            }
            long timeout = input.has("timeout_ms")
                    ? integer(input.get("timeout_ms"), "timeout_ms") : DEFAULT_APPROVAL_TIMEOUT_MILLIS;
            if (timeout < MIN_APPROVAL_TIMEOUT_MILLIS || timeout > MAX_APPROVAL_TIMEOUT_MILLIS) {
                throw invalidInput("timeout_ms must be between 1000 and 300000");
            }
            return new ApprovalInput(player, interaction, timeout);
        }
    }

    private static void exactMembers(JsonObject object, Set<String> allowed) {
        exactMembers(object, allowed, allowed);
    }

    private static void exactMembers(JsonObject object, Set<String> allowed, Set<String> required) {
        for (String name : required) {
            if (!object.has(name)) {
                throw invalidInput("missing required field: " + name);
            }
        }
        for (String name : object.keySet()) {
            if (!allowed.contains(name)) {
                throw invalidInput("unknown field: " + name);
            }
        }
    }

    private static String text(JsonObject object, String name, int minimum, int maximum,
                               boolean allowWhitespace) {
        JsonElement element = object.get(name);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw invalidInput(name + " must be a string");
        }
        String value = primitive.getAsString();
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw invalidInput(name + " must contain " + minimum + '-' + maximum + " code points");
        }
        if (value.codePoints().anyMatch(JavaScriptOperationRouter::control)
                || !allowWhitespace && name.equals("player")
                && value.codePoints().anyMatch(JavaScriptOperationRouter::space)) {
            throw invalidInput(name + " contains disallowed whitespace or control characters");
        }
        return value;
    }

    private static long integer(JsonElement element, String name) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw invalidInput(name + " must be an integer");
        }
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalidInput(name + " must be an integer");
        }
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean control(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static InvalidInput invalidInput(String message) {
        return new InvalidInput(message == null || message.isBlank() ? "invalid operation input" : message);
    }

    private static String safeInputMessage(IllegalArgumentException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "invalid operation input";
        }
        String oneLine = message.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240);
    }

    private static final class InvalidInput extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidInput(String message) {
            super(message);
        }
    }
}
