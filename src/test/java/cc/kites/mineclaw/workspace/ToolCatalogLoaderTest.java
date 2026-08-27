package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.function.FunctionCatalogLoader;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCatalogLoaderTest {
    @Test
    void fixedLoadRequiresExplicitDataRootAndRejectsSecretAliases(@TempDir Path directory)
            throws Exception {
        Path toolsFile = directory.resolve(ToolCatalogLoader.TOOLS_FILE_NAME);
        Files.writeString(toolsFile, resource("/tools.yml"), StandardCharsets.UTF_8);
        ToolCatalogLoader loader = new ToolCatalogLoader();

        assertThat(loader.load(directory, toolsFile, enabledSettings())
                .enabledDefinitions()).hasSize(9);

        Files.delete(toolsFile);
        Path secret = directory.resolve(".env");
        Files.writeString(secret, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createLink(toolsFile, secret);
        assertThatThrownBy(() -> loader.load(directory, toolsFile, enabledSettings()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }

    @Test
    void loadsBundledSchema2CatalogAndResolvesEveryDeclaredHandler() throws Exception {
        ToolCatalog catalog = parse(resource("/tools.yml"));

        assertThat(catalog.definitions()).hasSize(9);
        assertThat(catalog.enabledDefinitions()).hasSize(9);
        assertThat(catalog.invalidDefinitions()).isEmpty();
        assertThat(catalog.findEnabled("item_inspect")).get().satisfies(tool -> {
            assertThat(tool.registeredHandler()).contains(ToolDefinition.Handler.ITEM_INSPECT);
            assertThat(tool.modelFunctionName()).isEqualTo("item_inspect");
        });
        assertThat(catalog.findEnabled("call_function")).get().satisfies(tool ->
                assertThat(tool.registeredHandler()).contains(ToolDefinition.Handler.CALL_FUNCTION));
        JsonArray wire = catalog.toChatCompletionsTools();
        assertThat(wire).hasSize(9);
        JsonArray responsesWire = catalog.toResponsesTools();
        assertThat(responsesWire).hasSize(9);
        assertThat(responsesWire.get(0).getAsJsonObject().has("function")).isFalse();
        assertThat(responsesWire.get(0).getAsJsonObject().get("type").getAsString())
                .isEqualTo("function");
        assertThat(responsesWire.get(0).getAsJsonObject().get("name").getAsString())
                .isEqualTo("player_snapshot");
        assertThat(responsesWire.get(0).getAsJsonObject().getAsJsonObject("parameters"))
                .isEqualTo(wire.get(0).getAsJsonObject().getAsJsonObject("function")
                        .getAsJsonObject("parameters"));
        assertThat(FunctionCatalogLoader.nativeCapabilityAllowlist(catalog))
                .contains("item_inspect", "read")
                .doesNotContain("call_function", "mimo_web_search");
    }

    @Test
    void rejectsEveryPreSchema2RootWithoutCompatibilityParsing() {
        ToolCatalog catalog = parse("""
                - name: list
                  handler: list
                  description: legacy
                  parameters: {type: object}
                  enabled: true
                """);

        assertThat(catalog.definitions()).isEmpty();
        assertThat(catalog.diagnostics()).singleElement().asString()
                .contains("invalid_root", "expected Schema 2")
                .doesNotContain("migration");
    }

    @Test
    void rejectsUnsupportedSchemaAndStrictRootFields() {
        assertThat(parse("schema: 1\ntools: []\n").diagnostics())
                .singleElement().asString().contains("unsupported_schema");
        assertThat(parse("schema: 2\ntools: []\nlegacy: true\n").diagnostics())
                .singleElement().asString().contains("unknown_field", "$.legacy");
    }

    @Test
    void rejectsYamlAnchorsAliasesAndMergeKeys() {
        ToolCatalog catalog = parse("""
                schema: 2
                tools:
                  - &base
                    handler: list
                    enabled: true
                    payload:
                      type: function
                      function:
                        name: list
                        description: valid
                        parameters: {type: object, properties: {}, additionalProperties: false}
                  - <<: *base
                    handler: read
                """);

        assertThat(catalog.definitions()).isEmpty();
        assertThat(catalog.diagnostics()).singleElement().asString()
                .contains("invalid_root", "invalid YAML");
    }

    @Test
    void requiresExactlyHandlerEnabledAndPayloadAndRejectsIdAndMetadata() {
        ToolCatalog catalog = parse("""
                schema: 2
                tools:
                  - handler: list
                    payload: {type: function, function: {name: list, description: x, parameters: {type: object}}}
                  - handler: read
                    enabled: "true"
                    payload: {type: function, function: {name: read, description: x, parameters: {type: object}}}
                  - handler: grep
                    enabled: true
                    id: grep
                    metadata: {type: mineclaw}
                    payload: {type: function, function: {name: grep, description: x, parameters: {type: object}}}
                """);

        assertThat(catalog.invalidDefinitions()).hasSize(3);
        assertThat(catalog.diagnostics())
                .anyMatch(value -> value.contains("missing_field at entry.enabled"))
                .anyMatch(value -> value.contains("invalid_field_type at enabled"))
                .anyMatch(value -> value.contains("unknown_field at entry.id"));
    }

    @Test
    void validatesDisabledEntriesCompletelyAndAppliesGlobalHandlerSwitches() {
        String valid = root(entry("list", false) + entry("read", true));
        ToolCatalog filtered = new ToolCatalogLoader().parse(valid,
                new MineclawConfig.Tools(true, Set.of("read")));
        assertThat(filtered.invalidDefinitions()).isEmpty();
        assertThat(filtered.enabledDefinitions()).isEmpty();
        assertThat(filtered.definitions()).extracting(ToolDefinition::status)
                .containsOnly(ToolDefinition.Status.DISABLED);

        String malformedDisabled = root("""
                  - handler: read
                    enabled: false
                    payload: {type: web_search}
                """);
        assertThat(parse(malformedDisabled).invalidDefinitions()).hasSize(1);
    }

    @Test
    void isolatesEntryFailuresAndMarksEveryDuplicateHandlerInvalid() {
        String yaml = root(entry("list", true) + entry("read", true) + entry("read", true) + """
                  - handler: bad/identifier
                    enabled: true
                    payload: {type: function}
                """);
        ToolCatalog catalog = parse(yaml);

        assertThat(catalog.enabledDefinitions()).extracting(ToolDefinition::handler).containsExactly("list");
        assertThat(catalog.invalidDefinitions()).extracting(ToolDefinition::handler)
                .containsExactly("read", "read", "bad/identifier");
        assertThat(catalog.diagnostics())
                .anyMatch(value -> value.contains("duplicate_handler"))
                .anyMatch(value -> value.contains("invalid_handler"));
    }

    @Test
    void handlerMustBeRegisteredAndPayloadNameMustEqualIt() {
        ToolCatalog catalog = parse(root("""
                  - handler: alias
                    enabled: true
                    payload:
                      type: function
                      function:
                        name: read
                        description: alias
                        parameters: {type: object, properties: {}, additionalProperties: false}
                  - handler: read
                    enabled: true
                    payload:
                      type: function
                      function:
                        name: list
                        description: mismatch
                        parameters: {type: object, properties: {}, additionalProperties: false}
                  - handler: list
                    enabled: true
                    payload: {type: web_search}
                """));

        assertThat(catalog.invalidDefinitions()).hasSize(3);
        assertThat(catalog.diagnostics())
                .anyMatch(value -> value.contains("unknown_handler at handler"))
                .anyMatch(value -> value.contains("payload_handler_mismatch at payload.function.name"))
                .anyMatch(value -> value.contains("missing_field at payload.function"));
    }

    @Test
    void functionPayloadSchemaUsesStrictSupportedKeywordsAndTypes() {
        String yaml = root("""
                  - handler: read
                    enabled: true
                    payload:
                      type: function
                      function:
                        name: read
                        description: invalid schema
                        parameters:
                          type: object
                          properties:
                            path: {type: string, minimum: 1}
                          required: [missing]
                          additionalProperties: false
                          unevaluatedProperties: false
                """);
        assertThat(parse(yaml).diagnostics()).singleElement().asString()
                .contains("invalid_payload", "unknown_field", "unevaluatedProperties");
    }

    @Test
    void callFunctionHandlerEnforcesItsFixedGatewayContract() {
        String yaml = root("""
                  - handler: call_function
                    enabled: true
                    payload:
                      type: function
                      function:
                        name: call_function
                        description: drifted
                        parameters:
                          type: object
                          properties:
                            function: {type: string}
                            arguments: {type: object, additionalProperties: false}
                          required: [function]
                          additionalProperties: false
                """);
        assertThat(parse(yaml).diagnostics()).singleElement().asString()
                .contains("invalid_payload", "call_function gateway property contract is fixed");
    }

    @Test
    void providerAndCustomEntriesHaveNoLocalSchemaRepresentation() {
        ToolCatalog legacyMetadata = parse(root("""
                  - handler: mimo_web_search
                    enabled: true
                    metadata: {type: provider, provider: mimo}
                    payload: {type: web_search}
                """));
        assertThat(legacyMetadata.diagnostics()).singleElement().asString()
                .contains("unknown_field at entry.metadata");

        ToolCatalog customHandler = parse(root("""
                  - handler: mimo_web_search
                    enabled: true
                    payload: {type: web_search}
                """));
        assertThat(customHandler.diagnostics()).singleElement().asString()
                .contains("unknown_handler at handler");
    }

    @Test
    void diagnosticsAreContextualStableAndDoNotEchoPayloadText() {
        ArrayList<String> warnings = new ArrayList<>();
        ToolCatalog catalog = new ToolCatalogLoader(warnings::add).parse(root("""
                  - handler: read
                    enabled: true
                    payload:
                      type: function
                      function:
                        name: wrong
                        description: TOP_SECRET_PAYLOAD_TEXT
                        parameters: {type: object, properties: {}, additionalProperties: false}
                """), enabledSettings());

        assertThat(catalog.diagnostics()).singleElement().asString()
                .contains("tools.yml entry #1 (read)", "payload_handler_mismatch",
                        "payload.function.name")
                .doesNotContain("TOP_SECRET_PAYLOAD_TEXT");
        assertThat(warnings).containsExactlyElementsOf(catalog.diagnostics());
        assertThat(catalog.invalidDefinitions().getFirst().diagnostic())
                .contains(catalog.diagnostics().getFirst());
    }

    @Test
    void fixedLoadRejectsDataRootExternalSymlink(@TempDir Path directory) throws Exception {
        Path dataRoot = Files.createDirectories(directory.resolve("data"));
        Path outside = directory.resolve("outside.yml");
        Files.writeString(outside, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Path toolsFile = dataRoot.resolve(ToolCatalogLoader.TOOLS_FILE_NAME);
        Files.createSymbolicLink(toolsFile, outside);

        assertThatThrownBy(() -> new ToolCatalogLoader().load(dataRoot, toolsFile, enabledSettings()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .hasMessageNotContaining(outside.toString());
    }

    private static ToolCatalog parse(String yaml) {
        return new ToolCatalogLoader().parse(yaml, enabledSettings());
    }

    private static MineclawConfig.Tools enabledSettings() {
        return new MineclawConfig.Tools(true, Set.of());
    }

    private static String root(String entries) {
        return "schema: 2\ntools:\n" + entries;
    }

    private static String entry(String handler, boolean enabled) {
        return """
                  - handler: %s
                    enabled: %s
                    payload:
                      type: function
                      function:
                        name: %s
                        description: valid
                        parameters: {type: object, properties: {}, additionalProperties: false}
                """.formatted(handler, enabled, handler);
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = ToolCatalogLoaderTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
