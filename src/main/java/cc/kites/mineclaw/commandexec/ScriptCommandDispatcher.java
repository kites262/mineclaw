package cc.kites.mineclaw.commandexec;

import cc.kites.mineclaw.support.AuditLogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Trusted command boundary for reviewed JavaScript Functions, independent of model command policy. */
public final class ScriptCommandDispatcher {
    private static final int MAX_COMMAND_CODE_POINTS = 512;
    private static final int MAX_INTENT_CODE_POINTS = 1_024;
    private static final int MAX_PLAYER_CODE_POINTS = 64;

    private final CommandRuntime runtime;
    private final AuditLogger audit;

    public ScriptCommandDispatcher(CommandRuntime runtime, AuditLogger audit) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public CompletionStage<Result> dispatch(
            JsonObject input,
            BooleanSupplier invocationActive,
            AuditContext context
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(invocationActive, "invocationActive");
        Objects.requireNonNull(context, "context");
        final Request request;
        try {
            request = Request.parse(input);
        } catch (InvalidRequest exception) {
            Result result = invalid(exception.getMessage());
            audit(context, null, result);
            return CompletableFuture.completedFuture(result);
        }
        if (!invocationActive.getAsBoolean()) {
            Result result = cancelled();
            audit(context, request, result);
            return CompletableFuture.completedFuture(result);
        }
        if (request.console()) {
            return runtime.executeConsoleGuarded(request.command(), invocationActive)
                    .thenApply(dispatch -> present(dispatch, invocationActive, request, context));
        }
        String requestedName = request.player().orElseThrow();
        return runtime.findOnlinePlayer(requestedName).thenCompose(found -> {
            if (found.isEmpty() || !found.orElseThrow().name().equals(requestedName)) {
                Result result = failure("denied", "player_offline",
                        "target player is not online under that exact account name");
                audit(context, request, result);
                return CompletableFuture.completedFuture(result);
            }
            if (!invocationActive.getAsBoolean()) {
                Result result = cancelled();
                audit(context, request, result);
                return CompletableFuture.completedFuture(result);
            }
            return runtime.executePlayerGuarded(found.orElseThrow(), request.command(), invocationActive)
                    .thenApply(dispatch -> present(dispatch, invocationActive, request, context));
        });
    }

    private Result present(CommandDispatchResult dispatch, BooleanSupplier active,
                           Request request, AuditContext context) {
        CommandDispatchResult observed = dispatch == null
                ? CommandDispatchResult.resultUnknown("command runtime returned no dispatch result")
                : dispatch;
        Result result;
        if (!active.getAsBoolean()
                && observed.outcome() == CommandDispatchResult.Outcome.DISPATCH_REJECTED) {
            result = cancelled();
        } else {
            result = switch (observed.outcome()) {
                case CONSOLE_DISPATCHED -> request.console()
                        ? dispatched(observed.feedback())
                        : terminal("result_unknown", "command runtime returned a console outcome for player dispatch",
                                "unknown", "unknown", "");
                case PLAYER_DISPATCHED -> !request.console()
                        ? dispatched("")
                        : terminal("result_unknown", "command runtime returned a player outcome for console dispatch",
                                "unknown", "unknown", "");
                case PLAYER_OFFLINE -> terminal("player_offline", "target player is no longer online",
                        "player_offline", "not_started", "");
                case COMMAND_NOT_FOUND -> terminal("command_not_found", "command was not found",
                        "command_not_found", "not_started", observed.feedback());
                case DISPATCH_REJECTED -> terminal("dispatch_rejected", "command dispatch was rejected",
                        "rejected", "not_started", observed.feedback());
                case EXECUTION_EXCEPTION -> terminal("execution_exception",
                        detailOr(observed, "command dispatch threw an exception"),
                        "exception", "failed", observed.feedback());
                case RESULT_UNKNOWN -> terminal("result_unknown",
                        detailOr(observed, "command dispatch result is unknown"),
                        "unknown", "unknown", observed.feedback());
            };
        }
        audit(context, request, result);
        return result;
    }

    private void audit(AuditContext context, Request request, Result result) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("invocation_id", context.invocationId());
        fields.put("function", context.functionName());
        fields.put("script_hash", context.scriptHash());
        fields.put("trust_source", "reviewed_function");
        fields.put("model_whitelist", "not_applicable");
        fields.put("turn_player", context.playerName() + "(" + context.playerId() + ")");
        fields.put("executor", request == null ? "<invalid>" : request.executorLabel());
        fields.put("command", request == null ? "<invalid>" : request.command());
        fields.put("intent", request == null ? "<invalid>" : request.intent());
        fields.put("result", result.status());
        fields.put("error_code", result.output().has("error_code")
                ? result.output().get("error_code").getAsString() : "none");
        fields.put("dispatch_status", textOutput(result.output(), "dispatch_status", "not_started"));
        fields.put("execution_result", textOutput(result.output(), "execution_result", "not_started"));
        audit.log("javascript.command.dispatch", fields);
    }

    private static String textOutput(JsonObject output, String name, String fallback) {
        JsonElement value = output.get(name);
        return value instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString() : fallback;
    }

    private static Result dispatched(String feedback) {
        JsonObject output = new JsonObject();
        output.addProperty("message", "command was dispatched; actual effects are unknown");
        output.addProperty("dispatch_status", "accepted");
        output.addProperty("execution_result", "unknown");
        output.addProperty("feedback", feedback == null ? "" : feedback);
        return new Result("dispatched", output);
    }

    private static Result terminal(String code, String message, String dispatchStatus,
                                   String executionResult, String feedback) {
        JsonObject output = failureOutput(code, message);
        output.addProperty("dispatch_status", dispatchStatus);
        output.addProperty("execution_result", executionResult);
        output.addProperty("feedback", feedback == null ? "" : feedback);
        return new Result(code.equals("player_offline") ? "denied" : "terminal_error", output);
    }

    private static Result invalid(String message) {
        return failure("invalid", "invalid_command_request", message);
    }

    private static Result cancelled() {
        return failure("cancelled", "invocation_cancelled", "Function invocation was cancelled");
    }

    private static Result failure(String status, String code, String message) {
        return new Result(status, failureOutput(code, message));
    }

    private static JsonObject failureOutput(String code, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        return output;
    }

    private static String detailOr(CommandDispatchResult dispatch, String fallback) {
        return dispatch.detail().isBlank() ? fallback : dispatch.detail();
    }

    public record AuditContext(
            String invocationId,
            String functionName,
            String scriptHash,
            UUID playerId,
            String playerName
    ) {
        public AuditContext {
            invocationId = Objects.requireNonNull(invocationId, "invocationId");
            functionName = Objects.requireNonNull(functionName, "functionName");
            scriptHash = Objects.requireNonNull(scriptHash, "scriptHash");
            Objects.requireNonNull(playerId, "playerId");
            playerName = Objects.requireNonNull(playerName, "playerName");
        }
    }

    public record Result(String status, JsonObject output) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            output = Objects.requireNonNull(output, "output").deepCopy();
        }

        @Override
        public JsonObject output() {
            return output.deepCopy();
        }
    }

    private record Request(String command, String intent, Optional<String> player) {
        private Request {
            command = Objects.requireNonNull(command, "command");
            intent = Objects.requireNonNull(intent, "intent");
            player = Objects.requireNonNull(player, "player");
        }

        private static Request parse(JsonObject input) {
            exactMembers(input, "executor", "command", "intent");
            JsonElement executorElement = input.get("executor");
            if (executorElement == null || !executorElement.isJsonObject()) {
                throw invalidRequest("executor must be an object");
            }
            JsonObject executor = executorElement.getAsJsonObject();
            String type = string(executor, "type", 16, false);
            Optional<String> player;
            if (type.equals("console")) {
                exactMembers(executor, "type");
                player = Optional.empty();
            } else if (type.equals("player")) {
                exactMembers(executor, "type", "player");
                String name = string(executor, "player", MAX_PLAYER_CODE_POINTS, false);
                if (name.codePoints().anyMatch(ScriptCommandDispatcher::space)) {
                    throw invalidRequest("executor.player must not contain whitespace");
                }
                player = Optional.of(name);
            } else {
                throw invalidRequest("executor.type must be console or player");
            }
            String rawCommand = string(input, "command", MAX_COMMAND_CODE_POINTS, false);
            if (rawCommand.startsWith("/")) {
                throw invalidRequest("command must not begin with /");
            }
            String command = collapseWhitespace(rawCommand);
            if (command.isBlank()) {
                throw invalidRequest("command must not be blank");
            }
            String intent = collapseWhitespace(string(input, "intent", MAX_INTENT_CODE_POINTS, false));
            return new Request(command, intent, player);
        }

        private boolean console() {
            return player.isEmpty();
        }

        private String executorLabel() {
            return player.map(name -> "player:" + name).orElse("console");
        }
    }

    private static void exactMembers(JsonObject object, String... expected) {
        java.util.Set<String> allowed = java.util.Set.of(expected);
        for (String name : expected) {
            if (!object.has(name)) {
                throw invalidRequest("missing required field: " + name);
            }
        }
        for (String actual : object.keySet()) {
            if (!allowed.contains(actual)) {
                throw invalidRequest("unknown field: " + actual);
            }
        }
    }

    private static String string(JsonObject object, String name, int maximum, boolean allowBlank) {
        JsonElement element = object.get(name);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw invalidRequest(name + " must be a string");
        }
        String value = primitive.getAsString();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > maximum || !allowBlank && value.isBlank()) {
            throw invalidRequest(name + " must contain 1-" + maximum + " code points");
        }
        if (value.codePoints().anyMatch(ScriptCommandDispatcher::control)) {
            throw invalidRequest(name + " must not contain line breaks or control characters");
        }
        return value.strip();
    }

    private static String collapseWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean between = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (space(codePoint)) {
                between = !result.isEmpty();
            } else {
                if (between) {
                    result.append(' ');
                    between = false;
                }
                result.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
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

    private static InvalidRequest invalidRequest(String message) {
        return new InvalidRequest(message);
    }

    private static final class InvalidRequest extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidRequest(String message) {
            super(message);
        }
    }
}
