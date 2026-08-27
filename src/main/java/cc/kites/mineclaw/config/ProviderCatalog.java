package cc.kites.mineclaw.config;

import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable providers.yml snapshot, including ordered model and Provider Tool catalogs. */
public final class ProviderCatalog {
    public static final int SCHEMA = 1;

    private final String defaultModel;
    private final Map<String, Provider> providers;
    private final Map<String, Model> models;

    public ProviderCatalog(String defaultModel, Map<String, Provider> providers, Map<String, Model> models) {
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.providers = immutableMap(providers, "providers");
        this.models = immutableMap(models, "models");
        if (!this.models.containsKey(defaultModel)) {
            throw new IllegalArgumentException("default model is not declared");
        }
    }

    public String defaultModel() {
        return defaultModel;
    }

    public Map<String, Provider> providers() {
        return providers;
    }

    public Map<String, Model> models() {
        return models;
    }

    public Model requireModel(String reference) {
        Model model = models.get(Objects.requireNonNull(reference, "reference"));
        if (model == null) {
            throw new IllegalArgumentException("model is not declared");
        }
        return model;
    }

    public Provider providerFor(Model model) {
        Provider provider = providers.get(Objects.requireNonNull(model, "model").providerId());
        if (provider == null) {
            throw new IllegalStateException("model provider is not declared");
        }
        return provider;
    }

    public List<String> modelReferences() {
        return List.copyOf(models.keySet());
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> source, String name) {
        Objects.requireNonNull(source, name);
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }

    public enum ApiType {
        OPENAI_CHAT_COMPLETIONS("openai_chat_completions", "/chat/completions"),
        OPENAI_RESPONSES("openai_responses", "/responses");

        private final String wireName;
        private final String endpointPath;

        ApiType(String wireName, String endpointPath) {
            this.wireName = wireName;
            this.endpointPath = endpointPath;
        }

        public String wireName() {
            return wireName;
        }

        String endpointPath() {
            return endpointPath;
        }

        public static Optional<ApiType> fromWireName(String name) {
            return java.util.Arrays.stream(values()).filter(value -> value.wireName.equals(name)).findFirst();
        }
    }

    public record Provider(String id, Api api, Transport transport, List<ProviderTool> tools) {
        public Provider {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(transport, "transport");
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        }
    }

    public record Api(ApiType type, URI baseUrl, String apiKey) {
        public Api {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(baseUrl, "baseUrl");
            apiKey = Objects.requireNonNull(apiKey, "apiKey");
        }

        public URI endpoint() {
            return URI.create(baseUrl.toString() + type.endpointPath());
        }

        @Override
        public String toString() {
            return "Api[type=" + type.wireName() + ", baseUrl=protected, apiKey=protected]";
        }
    }

    public record Transport(Duration timeout, int maxRetries, Duration backoff) {
        public Transport {
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(backoff, "backoff");
        }
    }

    public record ProviderTool(String id, JsonObject payload) {
        public ProviderTool {
            Objects.requireNonNull(id, "id");
            payload = Objects.requireNonNull(payload, "payload").deepCopy();
        }

        @Override
        public JsonObject payload() {
            return payload.deepCopy();
        }
    }

    public record Model(String reference, String providerId, String upstreamModelId, Limits limits,
                        boolean promptCacheKeyEnabled,
                        Optional<String> interleavedField, JsonObject extraBody) {
        public Model {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(upstreamModelId, "upstreamModelId");
            Objects.requireNonNull(limits, "limits");
            interleavedField = Objects.requireNonNull(interleavedField, "interleavedField");
            extraBody = Objects.requireNonNull(extraBody, "extraBody").deepCopy();
        }

        public Model(String reference, String providerId, String upstreamModelId, Limits limits,
                     Optional<String> interleavedField, JsonObject extraBody) {
            this(reference, providerId, upstreamModelId, limits, false, interleavedField, extraBody);
        }

        @Override
        public JsonObject extraBody() {
            return extraBody.deepCopy();
        }

        public List<JsonObject> providerTools(Provider provider) {
            ArrayList<JsonObject> result = new ArrayList<>();
            provider.tools().forEach(tool -> result.add(tool.payload()));
            return List.copyOf(result);
        }
    }

    public record Limits(int contextWindowTokens, int maxOutputTokens,
                         OptionalInt compactTriggerTokens) {
        public Limits {
            compactTriggerTokens = Objects.requireNonNull(compactTriggerTokens,
                    "compactTriggerTokens");
            if (contextWindowTokens < 1 || maxOutputTokens < 1
                    || maxOutputTokens > contextWindowTokens) {
                throw new IllegalArgumentException("model context/output limits are invalid");
            }
            if (compactTriggerTokens.isPresent()
                    && (compactTriggerTokens.getAsInt() < 1
                    || compactTriggerTokens.getAsInt() > contextWindowTokens - maxOutputTokens)) {
                throw new IllegalArgumentException("compaction trigger exceeds the model input budget");
            }
        }

        public Limits(int contextWindowTokens, int maxOutputTokens) {
            this(contextWindowTokens, maxOutputTokens, OptionalInt.empty());
        }

        public int inputBudgetTokens() {
            return contextWindowTokens - maxOutputTokens;
        }
    }
}
