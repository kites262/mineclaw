package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.config.MineclawConfig;
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
    void fixedLoadRequiresTheExplicitWorkspaceAndRejectsSecretAliases(@TempDir Path directory)
            throws Exception {
        Path toolsFile = directory.resolve(ToolCatalogLoader.TOOLS_FILE_NAME);
        Files.writeString(toolsFile, resource("/tools.yml"), StandardCharsets.UTF_8);
        MineclawConfig.Tools settings = new MineclawConfig.Tools(true, Set.of());
        ToolCatalogLoader loader = new ToolCatalogLoader();

        assertThat(loader.load(directory, toolsFile, settings).enabledDefinitions()).hasSize(8);

        Files.delete(toolsFile);
        Path secret = directory.resolve(".env");
        Files.writeString(secret, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createLink(toolsFile, secret);

        assertThatThrownBy(() -> loader.load(directory, toolsFile, settings))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }

    @Test
    void fixedLoadRejectsAWorkspaceExternalSymlink(@TempDir Path directory) throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path outside = directory.resolve("outside.yml");
        Files.writeString(outside, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Path toolsFile = workspace.resolve(ToolCatalogLoader.TOOLS_FILE_NAME);
        Files.createSymbolicLink(toolsFile, outside);

        assertThatThrownBy(() -> new ToolCatalogLoader().load(workspace, toolsFile,
                new MineclawConfig.Tools(true, Set.of())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .hasMessageNotContaining(outside.toString());
    }

    @Test
    void loadsBundledCatalogAndBuildsChatCompletionsSchema() throws Exception {
        ToolCatalog catalog = new ToolCatalogLoader().parse(resource("/tools.yml"),
                new MineclawConfig.Tools(true, Set.of()));

        assertThat(catalog.definitions()).hasSize(8);
        assertThat(catalog.enabledDefinitions()).hasSize(8);
        assertThat(catalog.invalidDefinitions()).isEmpty();
        assertThat(catalog.findEnabled("online_players"))
                .get().extracting(tool -> tool.handler().orElseThrow())
                .isEqualTo(ToolDefinition.Handler.ONLINE_PLAYERS);
        assertThat(catalog.findEnabled("run_command"))
                .get().extracting(tool -> tool.handler().orElseThrow())
                .isEqualTo(ToolDefinition.Handler.RUN_COMMAND);

        JsonArray wireTools = catalog.toChatCompletionsTools();
        assertThat(wireTools).hasSize(8);
        assertThat(wireTools.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("function");
        assertThat(wireTools.get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString())
                .isEqualTo("look_block");
    }

    @Test
    void appliesGlobalEntryAndDisableListSwitchesWithoutMakingEntriesInvalid() {
        String yaml = """
                - name: one
                  handler: list
                  description: first
                  parameters: {type: object}
                - name: two
                  handler: read
                  description: second
                  parameters: {type: object}
                  enabled: false
                """;

        ToolCatalog filtered = new ToolCatalogLoader().parse(yaml,
                new MineclawConfig.Tools(true, Set.of("one")));
        assertThat(filtered.enabledDefinitions()).isEmpty();
        assertThat(filtered.invalidDefinitions()).isEmpty();
        assertThat(filtered.definitions()).extracting(ToolDefinition::status)
                .containsOnly(ToolDefinition.Status.DISABLED);
        assertThat(filtered.diagnostics()).isEmpty();

        ToolCatalog globallyDisabled = new ToolCatalogLoader().parse(yaml,
                new MineclawConfig.Tools(false, Set.of()));
        assertThat(globallyDisabled.enabledDefinitions()).isEmpty();
        assertThat(globallyDisabled.invalidDefinitions()).isEmpty();
    }

    @Test
    void isolatesUnknownDuplicateAndMalformedEntries() {
        ArrayList<String> warnings = new ArrayList<>();
        String yaml = """
                - name: good
                  handler: list
                  description: usable
                  parameters: {type: object}
                - name: bad_handler
                  handler: explode
                  description: nope
                  parameters: {type: object}
                - name: good
                  handler: read
                  description: duplicate
                  parameters: {type: object}
                - not-a-map
                - name: bad_parameters
                  handler: grep
                  description: nope
                  parameters: []
                - name: wrong_case
                  handler: LIST
                  description: enum names are exact
                  parameters: {type: object}
                - name: also_good
                  handler: inventory
                  description: usable too
                  parameters: {type: object}
                """;

        ToolCatalog catalog = new ToolCatalogLoader(warnings::add).parse(yaml,
                new MineclawConfig.Tools(true, Set.of()));

        assertThat(catalog.definitions()).hasSize(7);
        assertThat(catalog.enabledDefinitions()).extracting(ToolDefinition::name)
                .containsExactly("good", "also_good");
        assertThat(catalog.invalidDefinitions()).hasSize(5);
        assertThat(catalog.invalidDefinitions()).extracting(tool -> tool.diagnostic().orElseThrow())
                .anyMatch(message -> message.contains("unknown handler"))
                .anyMatch(message -> message.contains("duplicate tool name"))
                .anyMatch(message -> message.contains("entry must be a mapping"))
                .anyMatch(message -> message.contains("parameters must be a JSON object"));
        assertThat(warnings).hasSize(5);
    }

    @Test
    void malformedTopLevelProducesAnEmptyDiagnosticCatalog() {
        ArrayList<String> warnings = new ArrayList<>();
        ToolCatalog catalog = new ToolCatalogLoader(warnings::add).parse("name: list\nhandler: list\n",
                new MineclawConfig.Tools(true, Set.of()));

        assertThat(catalog.definitions()).isEmpty();
        assertThat(catalog.diagnostics()).singleElement().asString().contains("top level must be a YAML list");
        assertThat(warnings).containsExactlyElementsOf(catalog.diagnostics());
    }

    @Test
    void rejectsInvalidJsonSchemaWithoutDroppingOtherTools() {
        String yaml = """
                - name: bad
                  handler: list
                  description: bad schema
                  parameters: {type: banana}
                - name: good
                  handler: run_command
                  description: valid nullable property
                  parameters:
                    type: object
                    properties:
                      player: {type: [string, 'null']}
                    required: [player]
                """;

        ToolCatalog catalog = new ToolCatalogLoader().parse(yaml,
                new MineclawConfig.Tools(true, Set.of()));
        assertThat(catalog.invalidDefinitions()).extracting(ToolDefinition::name).containsExactly("bad");
        assertThat(catalog.enabledDefinitions()).extracting(ToolDefinition::name).containsExactly("good");
    }

    @Test
    void rejectsInvalidNumericSchemaKeywordsAtCatalogLoadTime() {
        String yaml = """
                - name: bad_minimum
                  handler: list
                  description: invalid numeric keyword
                  parameters:
                    type: object
                    properties:
                      depth: {type: integer, minimum: nope}
                - name: reversed_range
                  handler: list
                  description: invalid numeric range
                  parameters:
                    type: object
                    properties:
                      depth: {type: integer, minimum: 5, maximum: 1}
                - name: bad_additional_schema
                  handler: list
                  description: invalid additional-properties schema
                  parameters:
                    type: object
                    additionalProperties: {type: integer, minimum: nope}
                """;

        ToolCatalog catalog = new ToolCatalogLoader().parse(yaml,
                new MineclawConfig.Tools(true, Set.of()));

        assertThat(catalog.invalidDefinitions()).extracting(ToolDefinition::name)
                .containsExactly("bad_minimum", "reversed_range", "bad_additional_schema");
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
