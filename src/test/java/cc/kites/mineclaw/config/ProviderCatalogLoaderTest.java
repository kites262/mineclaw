package cc.kites.mineclaw.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderCatalogLoaderTest {
    private final ProviderCatalogLoader loader = new ProviderCatalogLoader(name -> null);

    @Test
    void loadsBundledCatalogAndResolvesCredentialWithoutLeakingIt() throws Exception {
        ProviderCatalog catalog = loader.parse(resource("/providers.yml"),
                Map.of("MINECLAW_API_KEY", "TOP_SECRET_KEY"));
        ProviderCatalog.Model model = catalog.requireModel("mimo/mimo-v2.5");
        ProviderCatalog.Provider provider = catalog.providerFor(model);

        assertThat(catalog.defaultModel()).isEqualTo("mimo/mimo-v2.5");
        assertThat(model.upstreamModelId()).isEqualTo("mimo-v2.5");
        assertThat(model.limits().contextWindowTokens()).isEqualTo(131_072);
        assertThat(model.limits().maxOutputTokens()).isEqualTo(16_384);
        assertThat(model.limits().compactTriggerTokens()).hasValue(102_400);
        assertThat(model.promptCacheKeyEnabled()).isTrue();
        assertThat(model.interleavedField()).contains("reasoning_content");
        assertThat(model.extraBody().toString()).isEqualTo("{\"thinking\":{\"type\":\"enabled\"}}");
        assertThat(provider.api().endpoint()).hasToString(
                "https://api.xiaomimimo.com/v1/chat/completions");
        assertThat(provider.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.id()).isEqualTo("mimo_web_search");
            assertThat(tool.payload().get("type").getAsString()).isEqualTo("web_search");
        });
        assertThat(provider.api().toString()).doesNotContain("TOP_SECRET_KEY");
    }

    @Test
    void sharesOneProviderAcrossModelsAndSplitsOnlyTheFirstSlash() throws Exception {
        ProviderCatalog catalog = loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                  mimo/org/model-b:
                    limits: {context_window_tokens: 8192, max_output_tokens: 1024}
                """, "mimo/org/model-b"), Map.of("KEY", "secret"));

        assertThat(catalog.modelReferences()).containsExactly("mimo/model-a", "mimo/org/model-b");
        assertThat(catalog.requireModel("mimo/org/model-b").upstreamModelId()).isEqualTo("org/model-b");
        assertThat(catalog.providerFor(catalog.requireModel("mimo/model-a")))
                .isSameAs(catalog.providerFor(catalog.requireModel("mimo/org/model-b")));
        assertThat(catalog.requireModel("mimo/model-a").limits().compactTriggerTokens()).isEmpty();
        assertThat(catalog.requireModel("mimo/model-a").promptCacheKeyEnabled()).isFalse();
    }

    @Test
    void validatesOptionalPromptCacheKeyAsAStrictBoolean() throws Exception {
        for (String value : java.util.List.of("true", "false")) {
            ProviderCatalog catalog = loader.parse(base("""
                      mimo/model-a:
                        limits: {context_window_tokens: 4096, max_output_tokens: 512}
                        request:
                          prompt_cache_key: %s
                    """.formatted(value), "mimo/model-a"), Map.of("KEY", "secret"));
            assertThat(catalog.requireModel("mimo/model-a").promptCacheKeyEnabled())
                    .isEqualTo(Boolean.parseBoolean(value));
        }

        String invalid = base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                    request:
                      prompt_cache_key: 'true'
                """, "mimo/model-a");
        assertThatThrownBy(() -> loader.parse(invalid, Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("prompt_cache_key must be a boolean");
    }

    @Test
    void validatesOptionalAbsoluteCompactionThresholdAgainstInputBudget() throws Exception {
        ProviderCatalog valid = loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512,
                             compact_trigger_tokens: 3584}
                """, "mimo/model-a"), Map.of("KEY", "secret"));
        assertThat(valid.requireModel("mimo/model-a").limits().compactTriggerTokens())
                .hasValue(3_584);

        for (String value : java.util.List.of("0", "3585", "true")) {
            String source = base("""
                      mimo/model-a:
                        limits: {context_window_tokens: 4096, max_output_tokens: 512,
                                 compact_trigger_tokens: %s}
                    """.formatted(value), "mimo/model-a");
            assertThatThrownBy(() -> loader.parse(source, Map.of("KEY", "secret")))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("compact_trigger_tokens");
        }
    }

    @Test
    void rejectsMissingOrBareDefaultsAndAbsentEnvironmentReferences() {
        assertThatThrownBy(() -> loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                """, "model-a"), Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("complete provider/model");
        assertThatThrownBy(() -> loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                """, "mimo/missing"), Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("exactly match");
        assertThatThrownBy(() -> loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                """, "mimo/model-a"), Map.of()))
                .isInstanceOf(ConfigException.class).hasMessageContaining("absent or empty");
    }

    @Test
    void rejectsReservedExtraBodyAndInvalidMimoThinking() {
        assertThatThrownBy(() -> loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                    request: {extra_body: {model: injected}}
                """, "mimo/model-a"), Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("model is reserved");
        assertThatThrownBy(() -> loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                    request: {extra_body: {prompt_cache_key: injected}}
                """, "mimo/model-a"), Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("prompt_cache_key is reserved");
        assertThatThrownBy(() -> loader.parse(base("""
                  mimo/model-a:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                    request: {extra_body: {thinking: {type: auto}}}
                """, "mimo/model-a"), Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("enabled or disabled");
    }

    @Test
    void rejectsUnregisteredProviderToolsAndLegacyEntryFields() {
        String source = baseWithTools("""
              - id: browser
                enabled: true
                payload: {type: web_search}
            """);
        assertThatThrownBy(() -> loader.parse(source, Map.of("KEY", "secret")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("enabled is not supported");
    }

    @Test
    void rejectsDuplicateKeysAliasesMergeKeysAndCustomTags() {
        for (String source : new String[]{
                "schema: 1\nschema: 1\ndefault: x/y\nproviders: {}\nmodels: {}\n",
                "schema: 1\ndefault: x/y\nproviders: &p {}\nmodels: *p\n",
                "schema: 1\ndefault: x/y\nproviders: {x: {<<: {}}}\nmodels: {}\n",
                "schema: 1\ndefault: x/y\nproviders: !custom {}\nmodels: {}\n"}) {
            assertThatThrownBy(() -> loader.parse(source, Map.of()))
                    .isInstanceOf(ConfigException.class);
        }
    }

    private static String base(String models, String fallback) {
        return """
                schema: 1
                default: %s
                providers:
                  mimo:
                    api:
                      type: openai_chat_completions
                      base_url: https://example.com/v1/
                      api_key: ${KEY}
                    transport:
                      timeout_ms: 1000
                      retry: {max_retries: 0, backoff_ms: 0}
                    tools: []
                models:
                %s
                """.formatted(fallback, models.indent(2));
    }

    private static String baseWithTools(String tools) {
        return """
                schema: 1
                default: mimo/model
                providers:
                  mimo:
                    api: {type: openai_chat_completions, base_url: https://example.com/v1, api_key: '${KEY}'}
                    transport: {timeout_ms: 1000, retry: {max_retries: 0, backoff_ms: 0}}
                    tools:
                %s
                models:
                  mimo/model:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                """.formatted(tools.indent(6));
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = ProviderCatalogLoaderTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
