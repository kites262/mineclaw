package cc.kites.mineclaw.javascript;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * GraalJS workflow runtime with one isolated Context and one serialized event queue per invocation.
 * Host operations cross the boundary only through JSON and controlled Polyglot proxies.
 */
public final class JavaScriptWorkflowRuntime implements AutoCloseable {
    public static final int API_VERSION = 1;
    public static final String ENGINE_SEMANTICS = "graaljs-25.2.4-es2025-v1";
    static final long SOURCE_VALIDATION_TIMEOUT_MILLIS = 60_000L;

    private static final Gson GSON = new Gson();
    private static final Set<String> ACTIONS = Set.of(
            "approval.request", "command.dispatch", "native_tool.call");
    private static final Set<String> FINAL_STATUSES = Set.of(
            "ok", "denied", "invalid", "recoverable_error");
    private static final Set<String> RESULT_RESOURCE_CODES = Set.of(
            "result_depth_limit", "result_member_limit", "result_size_limit");
    private static final Map<String, Set<String>> OPERATION_STATUSES = Map.of(
            "approval.request", Set.of(
                    "approved", "rejected", "timeout", "player_offline", "busy",
                    "denied", "cancelled", "invalid"),
            "command.dispatch", Set.of(
                    "dispatched", "denied", "invalid", "terminal_error", "cancelled"),
            "native_tool.call", Set.of(
                    "ok", "recoverable_error", "denied", "invalid", "dispatched", "timeout",
                    "terminal_error", "cancelled"));
    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern NATIVE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String CALL_FUNCTION_TOOL = "call_function";
    private static final Pattern OPTION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern STATUS = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int MAX_DIAGNOSTIC_CHARS = 512;
    private static final Duration SOFT_INTERRUPT_TIMEOUT = Duration.ofMillis(25L);

    private static final Source BOOTSTRAP_SOURCE = Source.newBuilder("js", """
            (() => {
              "use strict";
              const objectCreate = Object.create;
              const objectDefineProperty = Object.defineProperty;
              const objectFreeze = Object.freeze;
              const objectGetOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
              const objectGetPrototypeOf = Object.getPrototypeOf;
              const objectPrototype = Object.prototype;
              const arrayIsArray = Array.isArray;
              const arrayPrototype = Array.prototype;
              const ownKeys = Reflect.ownKeys;
              const jsonParse = JSON.parse.bind(JSON);
              const jsonStringify = JSON.stringify.bind(JSON);
              const promiseResolve = Promise.resolve.bind(Promise);
              const promiseReject = Promise.reject.bind(Promise);
              const promiseThen = Function.prototype.call.bind(Promise.prototype.then);
              const stringSlice = Function.prototype.call.bind(String.prototype.slice);
              const stringTrim = Function.prototype.call.bind(String.prototype.trim);
              const stringFrom = String;
              const numberIsFinite = Number.isFinite;
              const regExpTest = Function.prototype.call.bind(RegExp.prototype.test);
              const errorCodePattern = /^[a-z][a-z0-9_]*$/;
              const WeakSetConstructor = WeakSet;
              const weakSetAdd = Function.prototype.call.bind(WeakSet.prototype.add);
              const weakSetDelete = Function.prototype.call.bind(WeakSet.prototype.delete);
              const weakSetHas = Function.prototype.call.bind(WeakSet.prototype.has);
              const weakSetCreate = () => new WeakSetConstructor();

              const hide = name => {
                try {
                  objectDefineProperty(globalThis, name, {
                    value: undefined,
                    writable: false,
                    enumerable: false,
                    configurable: false
                  });
                } catch (_) {
                  // A disabled or absent extension is already harmless.
                }
              };
              for (const name of [
                "Proxy", "Polyglot", "Graal", "Java", "Packages", "load", "print",
                "console", "fetch", "setTimeout", "setInterval", "Worker"
              ]) {
                hide(name);
              }
              objectFreeze(objectPrototype);
              objectFreeze(arrayPrototype);
              objectFreeze(Promise.prototype);

              const limitedError = (code, message) => {
                const error = objectCreate(null);
                objectDefineProperty(error, "code", {
                  value: code, enumerable: true, writable: false, configurable: false
                });
                objectDefineProperty(error, "message", {
                  value: message, enumerable: true, writable: false, configurable: false
                });
                return objectFreeze(error);
              };

              const safeMessage = value => {
                if (typeof value === "string") {
                  return stringSlice(value, 0, 256);
                }
                if (value !== null && typeof value === "object") {
                  const descriptor = objectGetOwnPropertyDescriptor(value, "message");
                  if (descriptor && !descriptor.get && !descriptor.set
                      && typeof descriptor.value === "string") {
                    return stringSlice(descriptor.value, 0, 256);
                  }
                }
                return "JavaScript execution failed";
              };

              const cloneData = (value, depth, state, freezeResult) => {
                if (depth > state.maxDepth) {
                  throw limitedError("result_depth_limit", "object depth limit exceeded");
                }
                if (value === null || typeof value === "boolean" || typeof value === "string") {
                  return value;
                }
                if (typeof value === "number") {
                  if (!numberIsFinite(value)) {
                    throw limitedError("non_finite_number", "numbers must be finite");
                  }
                  return value;
                }
                if (typeof value !== "object") {
                  throw limitedError("unsupported_value", "value is not JSON serializable");
                }
                if (weakSetHas(state.seen, value)) {
                  throw limitedError("cyclic_value", "cyclic values are not supported");
                }
                weakSetAdd(state.seen, value);
                try {
                  if (arrayIsArray(value)) {
                    if (objectGetPrototypeOf(value) !== arrayPrototype) {
                      throw limitedError("invalid_array", "array has a non-standard prototype");
                    }
                    const keys = ownKeys(value);
                    if (keys.length !== value.length + 1 || keys[keys.length - 1] !== "length") {
                      throw limitedError("invalid_array", "arrays must be dense and have no custom members");
                    }
                    const copy = [];
                    for (let index = 0; index < value.length; index += 1) {
                      if (keys[index] !== stringFrom(index)) {
                        throw limitedError("invalid_array", "arrays must be dense");
                      }
                      const descriptor = objectGetOwnPropertyDescriptor(value, keys[index]);
                      if (!descriptor || descriptor.get || descriptor.set) {
                        throw limitedError("accessor_value", "getters and setters are not supported");
                      }
                      state.members += 1;
                      if (state.members > state.maxMembers) {
                        throw limitedError("result_member_limit", "member limit exceeded");
                      }
                      copy.push(cloneData(descriptor.value, depth + 1, state, freezeResult));
                    }
                    return freezeResult ? objectFreeze(copy) : copy;
                  }

                  const prototype = objectGetPrototypeOf(value);
                  if (prototype !== objectPrototype && prototype !== null) {
                    throw limitedError("invalid_object", "only plain objects are supported");
                  }
                  const copy = objectCreate(null);
                  for (const key of ownKeys(value)) {
                    if (typeof key !== "string") {
                      throw limitedError("symbol_member", "symbol members are not supported");
                    }
                    const descriptor = objectGetOwnPropertyDescriptor(value, key);
                    if (!descriptor || descriptor.get || descriptor.set || !descriptor.enumerable) {
                      throw limitedError("accessor_value", "only enumerable data members are supported");
                    }
                    state.members += 1;
                    if (state.members > state.maxMembers) {
                      throw limitedError("result_member_limit", "member limit exceeded");
                    }
                    objectDefineProperty(copy, key, {
                      value: cloneData(descriptor.value, depth + 1, state, freezeResult),
                      enumerable: true,
                      writable: !freezeResult,
                      configurable: !freezeResult
                    });
                  }
                  return freezeResult ? objectFreeze(copy) : copy;
                } finally {
                  weakSetDelete(state.seen, value);
                }
              };

              const convert = (value, maxDepth, maxMembers, freezeResult) => cloneData(
                value, 0, {maxDepth, maxMembers, members: 0, seen: weakSetCreate()}, freezeResult
              );

              const decode = (json, maxDepth, maxMembers) =>
                convert(jsonParse(json), maxDepth, maxMembers, true);

              const encodeRequest = (request, maxChars, maxDepth, maxMembers) => {
                const json = jsonStringify(convert(
                  request, maxDepth + 1, maxMembers + 2, false
                ));
                if (json.length > maxChars) {
                  throw limitedError("request_size_limit", "operation request is too large");
                }
                return json;
              };

              const encodeFailure = failure => {
                const code = failure !== null && typeof failure === "object"
                    && typeof failure.code === "string"
                    && (failure.code === "result_depth_limit"
                      || failure.code === "result_member_limit"
                      || failure.code === "result_size_limit"
                      || failure.code === "script_resource_limit")
                    ? failure.code : "script_exception";
                return jsonStringify({kind: "error", code, message: safeMessage(failure)});
              };

              const run = (
                  onCall, ctxJson, bridge, maxChars, maxDepth, maxMembers,
                  ctxMaxDepth, ctxMaxMembers
              ) => {
                let invokeOpen = true;
                let ctx;
                try {
                  ctx = decode(ctxJson, ctxMaxDepth, ctxMaxMembers);
                } catch (failure) {
                  invokeOpen = false;
                  return promiseResolve(encodeFailure(failure));
                }
                const invoke = request => {
                  if (!invokeOpen) {
                    return promiseReject(limitedError(
                      "invocation_completed", "Invocation is already completing"
                    ));
                  }
                  try {
                    return promiseResolve(bridge(
                      encodeRequest(request, maxChars, maxDepth, maxMembers)
                    ));
                  } catch (failure) {
                    return promiseReject(limitedError(
                      "invalid_api_request", safeMessage(failure)
                    ));
                  }
                };
                const api = objectCreate(null);
                objectDefineProperty(api, "version", {
                  value: 1, enumerable: true, writable: false, configurable: false
                });
                objectDefineProperty(api, "invoke", {
                  value: invoke, enumerable: true, writable: false, configurable: false
                });
                objectFreeze(api);

                const encodeFinal = value => {
                  invokeOpen = false;
                  try {
                    const result = convert(value, maxDepth + 1, maxMembers + 2, false);
                    const keys = ownKeys(result);
                    if (objectGetPrototypeOf(result) !== null
                        || keys.length !== 2 || !keys.includes("status") || !keys.includes("output")) {
                      throw limitedError(
                        "invalid_result_envelope", "final result must contain only status and output"
                      );
                    }
                    if (typeof result.status !== "string"
                        || !["ok", "denied", "invalid", "recoverable_error"].includes(result.status)) {
                      throw limitedError("invalid_result_status", "invalid final result status");
                    }
                    if (result.output === null || typeof result.output !== "object"
                        || arrayIsArray(result.output)) {
                      throw limitedError("invalid_result_output", "final output must be an object");
                    }
                    if (objectGetOwnPropertyDescriptor(result.output, "status")) {
                      throw limitedError("duplicate_result_status", "output must not contain status");
                    }
                    if (objectGetOwnPropertyDescriptor(result.output, "function")) {
                      throw limitedError(
                        "duplicate_result_function", "output must not contain function"
                      );
                    }
                    if (result.status !== "ok") {
                      if (typeof result.output.error_code !== "string"
                          || result.output.error_code === "none"
                          || !regExpTest(errorCodePattern, result.output.error_code)) {
                        throw limitedError(
                          "invalid_result_error_code",
                          "non-success output must contain a stable error_code"
                        );
                      }
                      if (typeof result.output.message !== "string"
                          || stringTrim(result.output.message).length === 0) {
                        throw limitedError(
                          "invalid_result_message",
                          "non-success output must contain a non-empty message"
                        );
                      }
                    } else if (result.output.error_code === "none") {
                      throw limitedError(
                        "invalid_success_error_code", "successful output must not use error_code none"
                      );
                    }
                    const encoded = jsonStringify({
                      kind: "success", status: result.status, output: result.output
                    });
                    if (encoded.length > maxChars) {
                      throw limitedError("result_size_limit", "final result is too large");
                    }
                    return encoded;
                  } catch (failure) {
                    return jsonStringify({
                      kind: "invalid",
                      code: failure && typeof failure.code === "string"
                        ? failure.code : "invalid_script_result",
                      message: safeMessage(failure)
                    });
                  }
                };
                const closeAndEncodeFailure = failure => {
                  invokeOpen = false;
                  return encodeFailure(failure);
                };
                let returned;
                try {
                  returned = onCall(ctx, api);
                } catch (failure) {
                  return promiseResolve(closeAndEncodeFailure(failure));
                }

                // Use the captured intrinsic directly as a brand check and continuation hook.
                // Promise.resolve(returned) is deliberately avoided: it would execute an arbitrary
                // non-Promise thenable, which is forbidden as a final value by the protocol.
                if (returned !== null && typeof returned === "object") {
                  try {
                    return promiseThen(returned, encodeFinal, closeAndEncodeFailure);
                  } catch (_) {
                    // A regular protocol object is handled synchronously below.
                  }
                }
                return promiseResolve(encodeFinal(returned));
              };

              return objectFreeze({run, decode});
            })()
            """, "mineclaw-bootstrap.js").cached(true).buildLiteral();

    private final AtomicReference<JavaScriptLimits> limits;
    private final Engine engine;
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService watchdogExecutor;
    private final RuntimeEventSink eventSink;
    private final ConcurrentHashMap<String, FunctionInvocation> activeInvocations =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Source> validatedSources = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object configurationLock = new Object();
    private boolean accepting = true;

    public JavaScriptWorkflowRuntime(JavaScriptLimits limits) {
        this(limits, event -> { });
    }

    public JavaScriptWorkflowRuntime(JavaScriptLimits limits, RuntimeEventSink eventSink) {
        this(limits, Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newScheduledThreadPool(2, daemonThreadFactory()), eventSink);
    }

    JavaScriptWorkflowRuntime(
            JavaScriptLimits limits,
            ExecutorService workerExecutor,
            ScheduledExecutorService watchdogExecutor
    ) {
        this(limits, workerExecutor, watchdogExecutor, event -> { });
    }

    JavaScriptWorkflowRuntime(
            JavaScriptLimits limits,
            ExecutorService workerExecutor,
            ScheduledExecutorService watchdogExecutor,
            RuntimeEventSink eventSink
    ) {
        this.limits = new AtomicReference<>(Objects.requireNonNull(limits, "limits"));
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.watchdogExecutor = Objects.requireNonNull(watchdogExecutor, "watchdogExecutor");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.engine = Engine.newBuilder("js").build();
    }

    /** Validates syntax and the global onCall entry without retaining any Context-owned Value. */
    public SourceValidation validateSource(String functionName, int apiVersion, String sourceText) {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(sourceText, "sourceText");
        JavaScriptLimits snapshot;
        synchronized (configurationLock) {
            if (closed.get()) {
                return SourceValidation.invalid("javascript_runtime_closed", "JavaScript runtime is closed");
            }
            if (!accepting) {
                return SourceValidation.invalid("javascript_runtime_suspended",
                        "JavaScript runtime is suspended for configuration reload");
            }
            snapshot = limits.get();
        }
        if (apiVersion != API_VERSION) {
            return SourceValidation.invalid("unsupported_api_version",
                    "unsupported JavaScript API version: " + apiVersion);
        }
        if (sourceText.length() > snapshot.maxSourceChars()) {
            return SourceValidation.invalid("source_too_large", "JavaScript source exceeds max_source_chars");
        }
        if (functionName.length() > 96 || !FUNCTION_NAME.matcher(functionName).matches()) {
            return SourceValidation.invalid("invalid_function_name",
                    "function name must match " + FUNCTION_NAME.pattern()
                            + " and contain at most 96 characters");
        }

        String hash = sha256(sourceText);
        String cacheKey = apiVersion + ":" + ENGINE_SEMANTICS + ':' + hash;
        Source cached = validatedSources.get(cacheKey);
        if (cached != null) {
            return SourceValidation.valid(new PreparedScript(functionName, apiVersion, hash, cached));
        }
        Source source = Source.newBuilder("js", sourceText, sourceName(hash))
                .cached(true)
                .buildLiteral();
        AtomicBoolean timedOut = new AtomicBoolean();
        Context context;
        try {
            context = newContext();
        } catch (RuntimeException failure) {
            return SourceValidation.invalid("javascript_runtime_unavailable", safeMessage(failure));
        }
        ScheduledFuture<?> timeout = watchdogExecutor.schedule(() -> {
            timedOut.set(true);
            hardClose(context);
        }, SOURCE_VALIDATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        try {
            context.eval(BOOTSTRAP_SOURCE);
            context.parse(source);
            context.eval(source);
            Value onCall = context.getBindings("js").getMember("onCall");
            if (onCall == null || !onCall.canExecute()) {
                return SourceValidation.invalid("missing_on_call",
                        "script must define one executable global onCall function");
            }
            if (timedOut.get()) {
                return SourceValidation.invalid("source_validation_timeout",
                        "JavaScript source validation timed out");
            }
            Source retained = validatedSources.putIfAbsent(cacheKey, source);
            return SourceValidation.valid(new PreparedScript(functionName, apiVersion, hash,
                    retained == null ? source : retained));
        } catch (PolyglotException failure) {
            if (timedOut.get() || failure.isCancelled()) {
                return SourceValidation.invalid("source_validation_timeout",
                        "JavaScript source validation timed out");
            }
            return SourceValidation.invalid(failure.isSyntaxError()
                    ? "javascript_syntax_error" : "javascript_validation_error", safeMessage(failure));
        } catch (RuntimeException failure) {
            return SourceValidation.invalid("javascript_validation_error", safeMessage(failure));
        } finally {
            timeout.cancel(false);
            closeIdle(context);
        }
    }

    /** Starts one isolated invocation and returns immediately with its cancellable result handle. */
    public InvocationHandle execute(
            PreparedScript script,
            InvocationRequest request,
            OperationHost host
    ) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(host, "host");
        FunctionInvocation scope;
        synchronized (configurationLock) {
            if (closed.get()) {
                throw new IllegalStateException("JavaScript runtime is closed");
            }
            if (!accepting) {
                throw new IllegalStateException("JavaScript runtime is suspended for configuration reload");
            }
            if (script.apiVersion() != API_VERSION) {
                throw new IllegalArgumentException("prepared script has an unsupported API version");
            }
            scope = new FunctionInvocation(script, request, host, limits.get());
            if (activeInvocations.putIfAbsent(request.invocationId(), scope) != null) {
                throw new IllegalArgumentException("duplicate invocation id: " + request.invocationId());
            }
        }
        scope.start();
        return new InvocationHandle(request.invocationId(), scope.result, scope::cancel);
    }

    /** True only while the invocation may still produce host side effects. */
    public boolean isActive(String invocationId) {
        Objects.requireNonNull(invocationId, "invocationId");
        FunctionInvocation scope = activeInvocations.get(invocationId);
        return scope != null && scope.active.get() && scope.sideEffectsOpen.get();
    }

    /** Operator diagnostic: reports whether this exact catalog Function owns a live invocation. */
    public boolean hasActiveInvocation(String functionName) {
        return activeInvocationCount(functionName) > 0;
    }

    /** Operator diagnostic count for one exact Function name. */
    public long activeInvocationCount(String functionName) {
        Objects.requireNonNull(functionName, "functionName");
        return activeInvocations.values().stream()
                .filter(scope -> scope.active.get()
                        && scope.script.functionName().equals(functionName))
                .count();
    }

    /** Cancels all old scopes, then atomically applies limits to subsequent validation/invocation. */
    public void reconfigure(JavaScriptLimits newLimits) {
        Objects.requireNonNull(newLimits, "newLimits");
        synchronized (configurationLock) {
            requireOpen();
            cancelAll();
            limits.set(newLimits);
            accepting = true;
        }
    }

    /** Closes the invocation admission gate and cancels all scopes until reconfigure publishes limits. */
    public void suspendForReload() {
        synchronized (configurationLock) {
            requireOpen();
            accepting = false;
            cancelAll();
        }
    }

    public void cancelAll() {
        List<FunctionInvocation> scopes = new ArrayList<>(activeInvocations.values());
        scopes.forEach(FunctionInvocation::cancel);
    }

    @Override
    public void close() {
        synchronized (configurationLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            accepting = false;
            cancelAll();
            try {
                engine.close(true);
            } catch (RuntimeException ignored) {
                // Each scope is already terminal; shutdown must remain best-effort and idempotent.
            }
            workerExecutor.shutdownNow();
            watchdogExecutor.shutdownNow();
        }
    }

    private Context newContext() {
        // There is deliberately no JavaScript working directory: direct IO is disabled entirely.
        // Reviewed native_tool.call file capabilities are the only filesystem bridge, and the
        // dispatcher behind them is rooted at plugins/Mineclaw/workspace.
        return Context.newBuilder("js")
                .engine(engine)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(ignored -> false)
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowValueSharing(false)
                .allowInnerContextOptions(false)
                .allowExperimentalOptions(false)
                .useSystemExit(false)
                .option("js.ecmascript-version", "2025")
                .option("js.strict", "true")
                .option("js.load", "false")
                .option("js.print", "false")
                .option("js.console", "false")
                .option("js.allow-eval", "false")
                .option("js.graal-builtin", "false")
                .option("js.foreign-object-prototype", "false")
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .build();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("JavaScript runtime is closed");
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "mineclaw-js-watchdog-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String sourceName(String hash) {
        return "mineclaw-" + hash.substring("sha256:".length(), 19) + ".js";
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return shorten(message.replace('\n', ' ').replace('\r', ' '), MAX_DIAGNOSTIC_CHARS);
    }

    private static String shorten(String text, int maximum) {
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static int addSaturated(int value, int increment) {
        long sum = (long) value + increment;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static void closeIdle(Context context) {
        try {
            context.close(false);
        } catch (RuntimeException failure) {
            hardClose(context);
        }
    }

    private static void hardClose(Context context) {
        try {
            context.close(true);
        } catch (RuntimeException ignored) {
            // Closing is an idempotent terminal cleanup path.
        }
    }

    private final class FunctionInvocation {
        private final PreparedScript script;
        private final InvocationRequest request;
        private final OperationHost host;
        private final JavaScriptLimits invocationLimits;
        private final SerialExecutor serial = new SerialExecutor(workerExecutor);
        private final CompletableFuture<ScriptResult> result = new CompletableFuture<>();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean sideEffectsOpen = new AtomicBoolean(true);
        private final AtomicInteger operationSequence = new AtomicInteger();
        private final AtomicInteger concurrentOperations = new AtomicInteger();
        private final AtomicInteger pendingApprovals = new AtomicInteger();
        private final AtomicLong segmentGeneration = new AtomicLong();
        private final ConcurrentHashMap<Integer, PendingOperation> operations = new ConcurrentHashMap<>();
        private final Object operationsLock = new Object();
        private final Object contextLock = new Object();
        private volatile Context context;
        private volatile Value decoder;
        private volatile ScheduledFuture<?> workflowTimeout;

        private FunctionInvocation(
                PreparedScript script,
                InvocationRequest request,
                OperationHost host,
                JavaScriptLimits invocationLimits
        ) {
            this.script = script;
            this.request = request;
            this.host = host;
            this.invocationLimits = invocationLimits;
        }

        private void start() {
            workflowTimeout = watchdogExecutor.schedule(
                    () -> terminate("terminal_error", "script_resource_limit",
                            "JavaScript workflow timed out", true),
                    invocationLimits.maxWorkflowMillis(), TimeUnit.MILLISECONDS);
            serial.execute(() -> runGuestSegment(this::startGuest));
        }

        private void startGuest() {
            if (!active.get()) {
                return;
            }
            try {
                Context created = newContext();
                if (!installContext(created)) {
                    hardClose(created);
                    return;
                }
                Value bootstrap = created.eval(BOOTSTRAP_SOURCE);
                created.eval(script.source());
                Value onCall = created.getBindings("js").getMember("onCall");
                if (onCall == null || !onCall.canExecute()) {
                    terminate("terminal_error", "script_snapshot_invalid",
                            "validated onCall entry is unavailable", false);
                    return;
                }
                if (!installDecoder(created, bootstrap.getMember("decode"))) {
                    return;
                }
                String ctxJson = contextJson(script, request);
                Value finalPromise = bootstrap.invokeMember("run", onCall, ctxJson,
                        (ProxyExecutable) this::bridge,
                        invocationLimits.maxResultChars(), invocationLimits.maxResultDepth(),
                        invocationLimits.maxResultMembers(),
                        addSaturated(invocationLimits.maxResultDepth(), 1),
                        addSaturated(invocationLimits.maxResultMembers(), 7));
                finalPromise.invokeMember("then",
                        (ProxyExecutable) values -> {
                            String encoded = values.length == 1 && values[0].isString()
                                    ? values[0].asString() : "";
                            closeSideEffectAdmission();
                            serial.execute(() -> finishEncoded(encoded));
                            return null;
                        },
                        (ProxyExecutable) values -> {
                            closeSideEffectAdmission();
                            serial.execute(() -> terminate("terminal_error", "script_exception",
                                    "JavaScript result bridge failed", false));
                            return null;
                        });
            } catch (PolyglotException failure) {
                if (active.get()) {
                    terminate("terminal_error", failure.isCancelled()
                                    ? "script_resource_limit" : "script_exception",
                            safeMessage(failure), false);
                }
            } catch (RuntimeException failure) {
                if (active.get()) {
                    terminate("terminal_error", "javascript_runtime_error", safeMessage(failure), false);
                }
            }
        }

        private Object bridge(Value... values) {
            if (!active.get() || !sideEffectsOpen.get()) {
                return immediateRejected("invocation_cancelled", "Invocation is no longer active");
            }
            if (values.length != 1 || !values[0].isString()) {
                return immediateRejected("invalid_api_request", "api.invoke requires one request object");
            }
            JsonObject envelope;
            try {
                JsonElement parsed = JsonParser.parseString(values[0].asString());
                if (!parsed.isJsonObject()) {
                    return immediateRejected("invalid_api_request", "api.invoke request must be an object");
                }
                envelope = parsed.getAsJsonObject();
            } catch (JsonParseException failure) {
                return immediateRejected("invalid_api_request", "api.invoke request is not valid JSON");
            }

            JsonElement actionValue = envelope.get("action");
            if (actionValue == null || !actionValue.isJsonPrimitive()
                    || !actionValue.getAsJsonPrimitive().isString()) {
                return immediateRejected("invalid_api_action", "action must be a string");
            }
            String action = actionValue.getAsString();
            if (!ACTIONS.contains(action)) {
                return immediateRejected("unknown_api_action", "unknown action: " + shorten(action, 64));
            }
            if (!exactKeys(envelope, Set.of("action", "input"))) {
                return immediateResolved(runtimeResult(action, "invalid", "invalid_request_envelope",
                        "request must contain only action and input"));
            }
            JsonElement inputValue = envelope.get("input");
            if (inputValue == null || !inputValue.isJsonObject()) {
                return immediateResolved(runtimeResult(action, "invalid", "invalid_action_input",
                        "input must be an object"));
            }
            JsonObject input = inputValue.getAsJsonObject();
            Optional<String> inputError = validateOperationInput(action, input);
            if (inputError.isPresent()) {
                return immediateResolved(runtimeResult(action, "invalid", "invalid_action_input",
                        inputError.orElseThrow()));
            }
            Capability capability = capability(action, input);
            if (!capability.valid()) {
                return immediateResolved(runtimeResult(action, "invalid", "invalid_action_input",
                        capability.error()));
            }
            if (!request.capabilities().contains(capability.name())) {
                return immediateResolved(runtimeResult(action, "denied", "capability_denied",
                        "required capability is not declared"));
            }

            int sequence = reserveOperation(action);
            if (sequence < 0) {
                return immediateResolved(runtimeResult(action, "invalid", "operation_limit",
                        "invocation operation limit exceeded"));
            }
            OperationHandle handle;
            PendingOperation pending;
            synchronized (operationsLock) {
                if (!active.get() || !sideEffectsOpen.get()) {
                    releaseOperation(action);
                    emitRuntimeEvent(sequence, action, "late_completion", "discarded",
                            "invocation ended before operation admission");
                    return immediateRejected("invocation_cancelled", "Invocation is no longer active");
                }
                try {
                    handle = Objects.requireNonNull(host.invoke(new OperationCall(
                            request.invocationId(), script.functionName(), script.scriptHash(), sequence,
                            action, input)), "operation host returned null");
                } catch (RuntimeException failure) {
                    releaseOperation(action);
                    return immediateRejected("host_bridge_error", "operation host failed");
                }
                pending = new PendingOperation(sequence, action, handle);
                operations.put(sequence, pending);
            }
            WeakReference<FunctionInvocation> scopeReference = new WeakReference<>(this);
            WeakReference<PendingOperation> pendingReference = new WeakReference<>(pending);
            handle.completion().whenComplete((operationResult, failure) -> {
                FunctionInvocation scope = scopeReference.get();
                PendingOperation retainedPending = pendingReference.get();
                if (scope == null || retainedPending == null) {
                    return;
                }
                OperationCompletion completion = scope.operationCompletion(
                        action, operationResult, failure);
                scope.serial.execute(() -> scope.completeOperation(retainedPending, completion));
            });
            return pending;
        }

        private int reserveOperation(String action) {
            while (true) {
                int previous = operationSequence.get();
                if (previous >= invocationLimits.maxOperationsPerInvocation()) {
                    return -1;
                }
                if (operationSequence.compareAndSet(previous, previous + 1)) {
                    break;
                }
            }
            int concurrent = concurrentOperations.incrementAndGet();
            if (concurrent > invocationLimits.maxConcurrentOperations()) {
                concurrentOperations.decrementAndGet();
                operationSequence.decrementAndGet();
                return -1;
            }
            if ("approval.request".equals(action)) {
                int approvals = pendingApprovals.incrementAndGet();
                if (approvals > invocationLimits.maxPendingApprovals()) {
                    pendingApprovals.decrementAndGet();
                    concurrentOperations.decrementAndGet();
                    operationSequence.decrementAndGet();
                    return -1;
                }
            }
            return operationSequence.get();
        }

        private void releaseOperation(String action) {
            concurrentOperations.decrementAndGet();
            if ("approval.request".equals(action)) {
                pendingApprovals.decrementAndGet();
            }
        }

        private OperationCompletion operationCompletion(
                String action,
                OperationResult operationResult,
                Throwable failure
        ) {
            if (failure != null) {
                return OperationCompletion.rejected("host_bridge_error", "operation host failed");
            }
            if (operationResult == null
                    || !OPERATION_STATUSES.get(action).contains(operationResult.status())) {
                return OperationCompletion.rejected("internal_protocol_error",
                        "operation host returned an invalid status");
            }
            JsonObject output = operationResult.output();
            if (output.has("action") || output.has("status")) {
                return OperationCompletion.rejected("internal_protocol_error",
                        "operation output duplicates envelope fields");
            }
            JsonObject envelope = new JsonObject();
            envelope.addProperty("action", action);
            envelope.addProperty("status", operationResult.status());
            envelope.add("output", output);
            if (!withinJsonLimits(output, invocationLimits)) {
                return OperationCompletion.rejected("script_resource_limit",
                        "operation result exceeds JavaScript limits");
            }
            return OperationCompletion.resolved(envelope);
        }

        private void completeOperation(PendingOperation pending, OperationCompletion completion) {
            boolean registered;
            boolean resume;
            synchronized (operationsLock) {
                registered = operations.remove(pending.sequence, pending);
                if (registered) {
                    releaseOperation(pending.action);
                }
                resume = registered && active.get() && sideEffectsOpen.get();
            }
            if (!registered) {
                emitRuntimeEvent(pending.sequence, pending.action, "late_completion", "discarded",
                        "operation was no longer registered");
                return;
            }
            if (!resume) {
                pending.cancel();
                emitRuntimeEvent(pending.sequence, pending.action, "late_completion", "discarded",
                        "invocation was already completing or terminal");
                return;
            }
            emitRuntimeEvent(pending.sequence, pending.action, "resumed",
                    completion.rejected ? "rejected" : "resolved", "");
            pending.complete(completion);
        }

        private void emitRuntimeEvent(
                int operation,
                String action,
                String phase,
                String status,
                String reason
        ) {
            try {
                eventSink.record(new RuntimeEvent(
                        request.invocationId(), script.functionName(), script.scriptHash(),
                        script.apiVersion(), request.playerName(), operation, action,
                        phase, status, shorten(reason, 240)));
            } catch (RuntimeException ignored) {
                // Audit failures must never alter workflow completion or cancellation semantics.
            }
        }

        private Object immediateResolved(JsonObject value) {
            return new ImmediateThenable(false, GSON.toJson(value));
        }

        private Object immediateRejected(String code, String message) {
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", shorten(message, 256));
            return new ImmediateThenable(true, GSON.toJson(error));
        }

        private void runGuestSegment(Runnable action) {
            if (!active.get()) {
                return;
            }
            long generation = segmentGeneration.incrementAndGet();
            ScheduledFuture<?> timeout = watchdogExecutor.schedule(() -> {
                if (segmentGeneration.compareAndSet(generation, generation + 1)) {
                    terminate("terminal_error", "script_resource_limit",
                            "JavaScript synchronous segment timed out", true);
                }
            }, invocationLimits.maxSyncSegmentMillis(), TimeUnit.MILLISECONDS);
            try {
                action.run();
            } finally {
                segmentGeneration.compareAndSet(generation, generation + 1);
                timeout.cancel(false);
            }
        }

        private void finishEncoded(String encoded) {
            if (!active.get()) {
                return;
            }
            if (encoded.isEmpty() || encoded.length() > invocationLimits.maxResultChars()) {
                terminate("terminal_error", "script_resource_limit",
                        "JavaScript final result exceeds the configured size limit", false);
                return;
            }
            try {
                JsonElement parsed = JsonParser.parseString(encoded);
                if (!parsed.isJsonObject()) {
                    throw new JsonParseException("encoded result is not an object");
                }
                JsonObject root = parsed.getAsJsonObject();
                String kind = requiredString(root, "kind");
                if ("success".equals(kind)) {
                    String status = requiredString(root, "status");
                    if (!FINAL_STATUSES.contains(status) || root.size() != 3
                            || !root.has("output") || !root.get("output").isJsonObject()) {
                        throw new JsonParseException("invalid success envelope");
                    }
                    JsonObject output = root.getAsJsonObject("output");
                    if (!validFinalOutput(status, output)) {
                        throw new JsonParseException("invalid Function result output");
                    }
                    completeNormally(new ScriptResult(status, output));
                } else if ("invalid".equals(kind)) {
                    String detailCode = safeCode(root, "invalid_script_result");
                    if (RESULT_RESOURCE_CODES.contains(detailCode)) {
                        terminate("terminal_error", "script_resource_limit",
                                safeRootMessage(root, "JavaScript result exceeds configured limits"), false);
                    } else {
                        terminate("invalid", "invalid_script_result",
                                safeRootMessage(root, "invalid JavaScript final result"), false);
                    }
                } else if ("error".equals(kind)) {
                    String detailCode = safeCode(root, "script_exception");
                    terminate("terminal_error", RESULT_RESOURCE_CODES.contains(detailCode)
                                    ? "script_resource_limit" : detailCode,
                            safeRootMessage(root, "JavaScript execution failed"), false);
                } else {
                    throw new JsonParseException("unknown encoded result kind");
                }
            } catch (JsonParseException | IllegalStateException failure) {
                terminate("invalid", "invalid_script_result", "invalid JavaScript final result", false);
            }
        }

        private void completeNormally(ScriptResult scriptResult) {
            closeSideEffectAdmission();
            if (!active.compareAndSet(true, false)) {
                return;
            }
            cleanup(false);
            result.complete(scriptResult);
        }

        private void cancel() {
            terminate("cancelled", "invocation_cancelled", "Function invocation was cancelled", true);
        }

        private void terminate(
                String status,
                String errorCode,
                String message,
                boolean forceClose
        ) {
            closeSideEffectAdmission();
            if (!active.compareAndSet(true, false)) {
                return;
            }
            JsonObject output = new JsonObject();
            output.addProperty("error_code", errorCode);
            output.addProperty("message", shorten(message, 256));
            cleanup(forceClose);
            result.complete(new ScriptResult(status, output));
        }

        private void cleanup(boolean forceClose) {
            activeInvocations.remove(request.invocationId(), this);
            ScheduledFuture<?> timeout = workflowTimeout;
            if (timeout != null) {
                timeout.cancel(false);
            }
            closeSideEffectAdmission();
            Context current;
            synchronized (contextLock) {
                decoder = null;
                current = context;
                context = null;
            }
            if (current != null) {
                if (forceClose) {
                    watchdogExecutor.execute(() -> {
                        try {
                            current.interrupt(SOFT_INTERRUPT_TIMEOUT);
                        } catch (TimeoutException | RuntimeException ignored) {
                            // Hard close below is the authoritative terminal action.
                        }
                        hardClose(current);
                    });
                } else {
                    closeIdle(current);
                }
            }
        }

        private boolean installContext(Context created) {
            synchronized (contextLock) {
                if (!active.get()) {
                    return false;
                }
                context = created;
                return true;
            }
        }

        private boolean installDecoder(Context created, Value candidate) {
            synchronized (contextLock) {
                if (!active.get() || context != created) {
                    return false;
                }
                decoder = candidate;
                return true;
            }
        }

        private void closeSideEffectAdmission() {
            List<PendingOperation> pending;
            synchronized (operationsLock) {
                if (!sideEffectsOpen.compareAndSet(true, false)) {
                    return;
                }
                pending = new ArrayList<>(operations.values());
                operations.clear();
            }
            pending.forEach(PendingOperation::cancel);
        }

        private Value decode(String json, int depthOverhead, int memberOverhead) {
            Value currentDecoder = decoder;
            if (currentDecoder == null) {
                throw new IllegalStateException("JavaScript decoder is unavailable");
            }
            return currentDecoder.execute(json,
                    addSaturated(invocationLimits.maxResultDepth(), depthOverhead),
                    addSaturated(invocationLimits.maxResultMembers(), memberOverhead));
        }

        private final class ImmediateThenable implements ProxyObject {
            private final boolean rejected;
            private final String json;

            private ImmediateThenable(boolean rejected, String json) {
                this.rejected = rejected;
                this.json = json;
            }

            @Override
            public Object getMember(String key) {
                if (!"then".equals(key)) {
                    return null;
                }
                return (ProxyExecutable) callbacks -> {
                    if (callbacks.length < 2) {
                        return null;
                    }
                    Value decoded = decode(json, 1, 4);
                    (rejected ? callbacks[1] : callbacks[0]).execute(decoded);
                    return null;
                };
            }

            @Override
            public Object getMemberKeys() {
                return ProxyArray.fromArray("then");
            }

            @Override
            public boolean hasMember(String key) {
                return "then".equals(key);
            }

            @Override
            public void putMember(String key, Value value) {
                throw new UnsupportedOperationException("read-only thenable");
            }
        }

        private final class PendingOperation implements ProxyObject {
            private final int sequence;
            private final String action;
            private final OperationHandle handle;
            private final Object callbackLock = new Object();
            private boolean attached;
            private boolean cancelled;
            private Value resolve;
            private Value reject;

            private PendingOperation(int sequence, String action, OperationHandle handle) {
                this.sequence = sequence;
                this.action = action;
                this.handle = handle;
            }

            @Override
            public Object getMember(String key) {
                if (!"then".equals(key)) {
                    return null;
                }
                return (ProxyExecutable) callbacks -> {
                    synchronized (callbackLock) {
                        if (callbacks.length >= 2 && !cancelled && !attached) {
                            resolve = callbacks[0];
                            reject = callbacks[1];
                            attached = true;
                        }
                    }
                    return null;
                };
            }

            @Override
            public Object getMemberKeys() {
                return ProxyArray.fromArray("then");
            }

            @Override
            public boolean hasMember(String key) {
                return "then".equals(key);
            }

            @Override
            public void putMember(String key, Value value) {
                throw new UnsupportedOperationException("read-only thenable");
            }

            private void complete(OperationCompletion completion) {
                Value callback;
                synchronized (callbackLock) {
                    if (cancelled) {
                        return;
                    }
                    callback = completion.rejected ? reject : resolve;
                    resolve = null;
                    reject = null;
                }
                if (callback == null) {
                    terminate("terminal_error", "internal_protocol_error",
                            "operation promise callbacks are unavailable", false);
                    return;
                }
                runGuestSegment(() -> {
                    try {
                        callback.execute(decode(completion.json, 1, 3));
                    } catch (PolyglotException failure) {
                        if (active.get()) {
                            terminate("terminal_error", failure.isCancelled()
                                            ? "script_resource_limit" : "script_exception",
                                    safeMessage(failure), false);
                        }
                    }
                });
            }

            private void cancel() {
                synchronized (callbackLock) {
                    cancelled = true;
                    resolve = null;
                    reject = null;
                }
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // Cancellation is best effort after the scope is already terminal.
                }
            }
        }
    }

    private static Optional<String> validateOperationInput(String action, JsonObject input) {
        try {
            switch (action) {
                case "approval.request" -> validateApprovalInput(input);
                case "command.dispatch" -> validateCommandInput(input);
                case "native_tool.call" -> validateNativeInput(input);
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            return Optional.empty();
        } catch (InvalidOperationInput failure) {
            return Optional.of(failure.getMessage());
        }
    }

    private static void validateApprovalInput(JsonObject input) {
        exactInputKeys(input, Set.of("player", "interaction", "timeout_ms"),
                Set.of("player", "interaction"));
        String player = inputText(input, "player", 1, 64);
        if (player.codePoints().anyMatch(JavaScriptWorkflowRuntime::space)) {
            throw invalidOperation("player must not contain whitespace");
        }
        JsonElement interactionValue = input.get("interaction");
        if (interactionValue == null || !interactionValue.isJsonObject()) {
            throw invalidOperation("interaction must be an object");
        }
        JsonObject interaction = interactionValue.getAsJsonObject();
        String type = inputText(interaction, "type", 1, 16);
        inputText(interaction, "title", 1, 64);
        inputText(interaction, "message", 1, 512);
        if (type.equals("confirm")) {
            exactInputKeys(interaction, Set.of("type", "title", "message"),
                    Set.of("type", "title", "message"));
        } else if (type.equals("select")) {
            exactInputKeys(interaction, Set.of("type", "title", "message", "options"),
                    Set.of("type", "title", "message", "options"));
            JsonElement optionsValue = interaction.get("options");
            if (optionsValue == null || !optionsValue.isJsonArray()
                    || optionsValue.getAsJsonArray().size() < 2
                    || optionsValue.getAsJsonArray().size() > 8) {
                throw invalidOperation("interaction.options must contain 2-8 entries");
            }
            Set<String> identifiers = new HashSet<>();
            for (int index = 0; index < optionsValue.getAsJsonArray().size(); index++) {
                JsonElement optionValue = optionsValue.getAsJsonArray().get(index);
                if (!optionValue.isJsonObject()) {
                    throw invalidOperation("interaction.options[" + index + "] must be an object");
                }
                JsonObject option = optionValue.getAsJsonObject();
                exactInputKeys(option, Set.of("id", "label"), Set.of("id", "label"));
                String id = inputText(option, "id", 1, 64);
                if (!OPTION_ID.matcher(id).matches() || !identifiers.add(id)) {
                    throw invalidOperation("select option ids must be unique and match "
                            + OPTION_ID.pattern());
                }
                inputText(option, "label", 1, 128);
            }
        } else {
            throw invalidOperation("interaction.type must be confirm or select");
        }
        if (input.has("timeout_ms")) {
            long timeout = inputInteger(input.get("timeout_ms"), "timeout_ms");
            if (timeout < 1_000L || timeout > 300_000L) {
                throw invalidOperation("timeout_ms must be between 1000 and 300000");
            }
        }
    }

    private static void validateCommandInput(JsonObject input) {
        exactInputKeys(input, Set.of("executor", "command", "intent"),
                Set.of("executor", "command", "intent"));
        JsonElement executorValue = input.get("executor");
        if (executorValue == null || !executorValue.isJsonObject()) {
            throw invalidOperation("executor must be an object");
        }
        JsonObject executor = executorValue.getAsJsonObject();
        String type = inputText(executor, "type", 1, 16);
        if (type.equals("console")) {
            exactInputKeys(executor, Set.of("type"), Set.of("type"));
        } else if (type.equals("player")) {
            exactInputKeys(executor, Set.of("type", "player"), Set.of("type", "player"));
            String player = inputText(executor, "player", 1, 64);
            if (player.codePoints().anyMatch(JavaScriptWorkflowRuntime::space)) {
                throw invalidOperation("executor.player must not contain whitespace");
            }
        } else {
            throw invalidOperation("executor.type must be console or player");
        }
        String command = inputText(input, "command", 1, 512).strip();
        if (command.startsWith("/")) {
            throw invalidOperation("command must not begin with /");
        }
        inputText(input, "intent", 1, 1_024);
    }

    private static void validateNativeInput(JsonObject input) {
        exactInputKeys(input, Set.of("name", "arguments"), Set.of("name", "arguments"));
        String name = inputText(input, "name", 1, 64);
        if (!NATIVE_TOOL_NAME.matcher(name).matches()) {
            throw invalidOperation("name must match " + NATIVE_TOOL_NAME.pattern());
        }
        if (CALL_FUNCTION_TOOL.equals(name)) {
            throw invalidOperation("call_function cannot be invoked through native_tool.call");
        }
        JsonElement arguments = input.get("arguments");
        if (arguments == null || !arguments.isJsonObject()) {
            throw invalidOperation("arguments must be an object");
        }
    }

    private static void exactInputKeys(JsonObject input, Set<String> allowed, Set<String> required) {
        for (String name : required) {
            if (!input.has(name)) {
                throw invalidOperation("missing required field: " + name);
            }
        }
        for (String name : input.keySet()) {
            if (!allowed.contains(name)) {
                throw invalidOperation("unknown field: " + name);
            }
        }
    }

    private static String inputText(JsonObject input, String name, int minimum, int maximum) {
        JsonElement value = input.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalidOperation(name + " must be a string");
        }
        String text = value.getAsString();
        int length = text.codePointCount(0, text.length());
        if (length < minimum || length > maximum || text.isBlank()) {
            throw invalidOperation(name + " must contain " + minimum + '-' + maximum + " code points");
        }
        if (text.codePoints().anyMatch(JavaScriptWorkflowRuntime::control)) {
            throw invalidOperation(name + " contains a control character");
        }
        return text;
    }

    private static long inputInteger(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidOperation(name + " must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalidOperation(name + " must be an integer");
        }
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean control(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static InvalidOperationInput invalidOperation(String message) {
        return new InvalidOperationInput(message);
    }

    private static Capability capability(String action, JsonObject input) {
        return switch (action) {
            case "approval.request" -> Capability.valid("approval.request");
            case "command.dispatch" -> {
                JsonElement executorValue = input.get("executor");
                if (executorValue == null || !executorValue.isJsonObject()) {
                    yield Capability.invalid("command executor must be an object");
                }
                JsonElement type = executorValue.getAsJsonObject().get("type");
                if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()
                        || !("console".equals(type.getAsString()) || "player".equals(type.getAsString()))) {
                    yield Capability.invalid("command executor type must be console or player");
                }
                yield Capability.valid("command.dispatch." + type.getAsString());
            }
            case "native_tool.call" -> {
                JsonElement name = input.get("name");
                if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                        || !NATIVE_TOOL_NAME.matcher(name.getAsString()).matches()
                        || CALL_FUNCTION_TOOL.equals(name.getAsString())) {
                    yield Capability.invalid("native tool name is invalid");
                }
                yield Capability.valid("native_tool.call." + name.getAsString());
            }
            default -> throw new IllegalArgumentException("unknown action: " + action);
        };
    }

    private static JsonObject runtimeResult(String action, String status, String code, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("action", action);
        envelope.addProperty("status", status);
        envelope.add("output", output);
        return envelope;
    }

    private static boolean exactKeys(JsonObject object, Set<String> expected) {
        return object.size() == expected.size() && object.keySet().containsAll(expected);
    }

    private static String contextJson(PreparedScript script, InvocationRequest request) {
        JsonObject invocation = new JsonObject();
        invocation.addProperty("id", request.invocationId());
        invocation.addProperty("function_name", script.functionName());
        invocation.addProperty("script_hash", script.scriptHash());
        JsonObject player = new JsonObject();
        player.addProperty("name", request.playerName());
        JsonObject context = new JsonObject();
        context.add("invocation", invocation);
        context.add("player", player);
        context.add("args", request.arguments());
        return GSON.toJson(context);
    }

    private static boolean withinJsonLimits(JsonElement value, JavaScriptLimits limits) {
        Counter counter = new Counter();
        if (!withinJsonLimits(value, 0, limits, counter)) {
            return false;
        }
        return GSON.toJson(value).length() <= limits.maxResultChars();
    }

    private static boolean withinJsonLimits(
            JsonElement value,
            int depth,
            JavaScriptLimits limits,
            Counter counter
    ) {
        if (depth > limits.maxResultDepth()) {
            return false;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (++counter.members > limits.maxResultMembers()
                        || !withinJsonLimits(element, depth + 1, limits, counter)) {
                    return false;
                }
            }
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : value.getAsJsonObject().entrySet()) {
                if (++counter.members > limits.maxResultMembers()
                        || !withinJsonLimits(member.getValue(), depth + 1, limits, counter)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String requiredString(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("missing string member: " + name);
        }
        return value.getAsString();
    }

    private static String safeCode(JsonObject root, String fallback) {
        try {
            String code = requiredString(root, "code");
            return STATUS.matcher(code).matches() ? code : fallback;
        } catch (JsonParseException failure) {
            return fallback;
        }
    }

    private static String safeRootMessage(JsonObject root, String fallback) {
        try {
            return shorten(requiredString(root, "message"), 256);
        } catch (JsonParseException failure) {
            return fallback;
        }
    }

    private static boolean validFinalOutput(String status, JsonObject output) {
        if (output.has("status") || output.has("function")) {
            return false;
        }
        JsonElement errorCode = output.get("error_code");
        if ("ok".equals(status)) {
            return errorCode == null || !errorCode.isJsonPrimitive()
                    || !errorCode.getAsJsonPrimitive().isString()
                    || !"none".equals(errorCode.getAsString());
        }
        JsonElement message = output.get("message");
        return errorCode != null && errorCode.isJsonPrimitive()
                && errorCode.getAsJsonPrimitive().isString()
                && STATUS.matcher(errorCode.getAsString()).matches()
                && !"none".equals(errorCode.getAsString())
                && message != null && message.isJsonPrimitive()
                && message.getAsJsonPrimitive().isString()
                && !message.getAsString().isBlank();
    }

    private record Capability(boolean valid, String name, String error) {
        private static Capability valid(String name) {
            return new Capability(true, name, "");
        }

        private static Capability invalid(String error) {
            return new Capability(false, "", error);
        }
    }

    private static final class InvalidOperationInput extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private InvalidOperationInput(String message) {
            super(message);
        }
    }

    private record OperationCompletion(boolean rejected, String json) {
        private static OperationCompletion resolved(JsonObject result) {
            return new OperationCompletion(false, GSON.toJson(result));
        }

        private static OperationCompletion rejected(String code, String message) {
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            return new OperationCompletion(true, GSON.toJson(error));
        }
    }

    private static final class Counter {
        private int members;
    }

    @FunctionalInterface
    public interface RuntimeEventSink {
        void record(RuntimeEvent event);
    }

    public record RuntimeEvent(
            String invocationId,
            String functionName,
            String scriptHash,
            int apiVersion,
            String playerName,
            int operation,
            String action,
            String phase,
            String status,
            String reason
    ) {
        public RuntimeEvent {
            invocationId = Objects.requireNonNull(invocationId, "invocationId");
            functionName = Objects.requireNonNull(functionName, "functionName");
            scriptHash = Objects.requireNonNull(scriptHash, "scriptHash");
            playerName = Objects.requireNonNull(playerName, "playerName");
            action = Objects.requireNonNull(action, "action");
            phase = Objects.requireNonNull(phase, "phase");
            status = Objects.requireNonNull(status, "status");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    private static final class SerialExecutor implements Executor {
        private final Executor delegate;
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();

        private SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            queue.add(Objects.requireNonNull(command, "command"));
            scheduleDrain();
        }

        private void scheduleDrain() {
            if (draining.compareAndSet(false, true)) {
                delegate.execute(this::drain);
            }
        }

        private void drain() {
            try {
                Runnable task;
                while ((task = queue.poll()) != null) {
                    try {
                        task.run();
                    } catch (RuntimeException ignored) {
                        // Invocation tasks map their own failures; one bad task must not wedge the queue.
                    }
                }
            } finally {
                draining.set(false);
                if (!queue.isEmpty()) {
                    scheduleDrain();
                }
            }
        }
    }
}
