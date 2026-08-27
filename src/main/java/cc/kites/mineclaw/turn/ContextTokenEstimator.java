package cc.kites.mineclaw.turn;

import cc.kites.mineclaw.api.ApiMessage;
import cc.kites.mineclaw.api.ApiUsage;
import cc.kites.mineclaw.api.ToolCall;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Provider-calibrated prompt estimator with a deterministic local fallback for missing Usage. */
final class ContextTokenEstimator {
    private static final double MIN_FACTOR = 0.25d;
    private static final double MAX_FACTOR = 8.0d;

    private final ConcurrentHashMap<String, Double> factors = new ConcurrentHashMap<>();

    Estimate estimate(String modelReference, String system, List<ApiMessage> messages,
                      List<JsonObject> tools) {
        return estimate(modelReference, system, messages, tools, true);
    }

    Estimate estimate(String modelReference, String system, List<ApiMessage> messages,
                      List<JsonObject> tools, boolean includeMessageNames) {
        return estimate(modelReference, system, messages, tools, includeMessageNames, true);
    }

    Estimate estimate(String modelReference, String system, List<ApiMessage> messages,
                      List<JsonObject> tools, boolean includeMessageNames,
                      boolean includePlayerContentPrefix) {
        Objects.requireNonNull(modelReference, "modelReference");
        int raw = rawEstimate(system, messages, tools, includeMessageNames,
                includePlayerContentPrefix);
        double factor = factors.getOrDefault(modelReference, 1.0d);
        return new Estimate(raw, saturatedCeil(raw * factor), factors.containsKey(modelReference));
    }

    Estimate estimateRaw(String modelReference, int rawTokens) {
        Objects.requireNonNull(modelReference, "modelReference");
        if (rawTokens < 1) {
            throw new IllegalArgumentException("rawTokens must be positive");
        }
        double factor = factors.getOrDefault(modelReference, 1.0d);
        return new Estimate(rawTokens, saturatedCeil(rawTokens * factor),
                factors.containsKey(modelReference));
    }

    int estimateMessages(String modelReference, List<ApiMessage> messages) {
        return estimateMessages(modelReference, messages, true);
    }

    int estimateMessages(String modelReference, List<ApiMessage> messages,
                         boolean includeMessageNames) {
        return estimateMessages(modelReference, messages, includeMessageNames, true);
    }

    int estimateMessages(String modelReference, List<ApiMessage> messages,
                         boolean includeMessageNames, boolean includePlayerContentPrefix) {
        return estimateRaw(modelReference,
                Math.max(1, messageEstimate(messages, includeMessageNames,
                        includePlayerContentPrefix))).tokens();
    }

    void observe(String modelReference, int rawEstimate, ApiUsage usage) {
        Objects.requireNonNull(modelReference, "modelReference");
        if (rawEstimate < 1 || usage == null) {
            return;
        }
        Integer observed = usage.promptTokens() != null ? usage.promptTokens() : usage.totalTokens();
        if (observed == null || observed < 1) {
            return;
        }
        double sample = clamp((double) observed / rawEstimate);
        factors.merge(modelReference, sample, (previous, next) -> clamp(previous * 0.35d + next * 0.65d));
    }

    static int rawEstimate(String system, List<ApiMessage> messages, List<JsonObject> tools) {
        return rawEstimate(system, messages, tools, true);
    }

    static int rawEstimate(String system, List<ApiMessage> messages, List<JsonObject> tools,
                           boolean includeMessageNames) {
        return rawEstimate(system, messages, tools, includeMessageNames, true);
    }

    static int rawEstimate(String system, List<ApiMessage> messages, List<JsonObject> tools,
                           boolean includeMessageNames, boolean includePlayerContentPrefix) {
        long tokens = 8L + textTokens(system);
        for (ApiMessage message : Objects.requireNonNull(messages, "messages")) {
            long normalizedTokens = 4L + textTokens(message.role())
                    + textTokens(message.modelContent(includePlayerContentPrefix));
            if (includeMessageNames) {
                normalizedTokens += textTokens(message.name());
            }
            for (ToolCall call : message.toolCalls()) {
                normalizedTokens += 8L + textTokens(call.id()) + textTokens(call.name())
                        + textTokens(call.arguments());
            }
            normalizedTokens += textTokens(message.toolCallId());
            for (var entry : message.providerFields().entrySet()) {
                normalizedTokens += textTokens(entry.getKey()) + textTokens(entry.getValue());
            }
            long responseItemTokens = 4L;
            for (JsonObject item : message.responseItems()) {
                responseItemTokens += textTokens(item.toString());
            }
            // A request sends either the normalized Chat frame or its raw Responses items,
            // never both. Use the larger projection so model switching remains conservative
            // without double-counting Responses history during usage calibration.
            tokens += message.responseItems().isEmpty()
                    ? normalizedTokens : Math.max(normalizedTokens, responseItemTokens);
        }
        for (JsonObject tool : Objects.requireNonNull(tools, "tools")) {
            tokens += 8L + textTokens(tool.toString());
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, tokens));
    }

    static int messageEstimate(List<ApiMessage> messages) {
        return messageEstimate(messages, true);
    }

    static int messageEstimate(List<ApiMessage> messages, boolean includeMessageNames) {
        return messageEstimate(messages, includeMessageNames, true);
    }

    static int messageEstimate(List<ApiMessage> messages, boolean includeMessageNames,
                               boolean includePlayerContentPrefix) {
        return rawEstimate("", messages, List.of(), includeMessageNames,
                includePlayerContentPrefix) - 8;
    }

    private static long textTokens(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        long quarterUnits = 0L;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            quarterUnits += codePoint <= 0x7f ? 1L : 4L;
        }
        return (quarterUnits + 3L) / 4L;
    }

    private static int saturatedCeil(double value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) Math.ceil(value));
    }

    private static double clamp(double value) {
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, value));
    }

    record Estimate(int rawTokens, int tokens, boolean providerCalibrated) { }
}
