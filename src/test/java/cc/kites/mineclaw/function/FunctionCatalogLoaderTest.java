package cc.kites.mineclaw.function;

import cc.kites.mineclaw.javascript.JavaScriptLimits;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.schema.SchemaViolation;
import cc.kites.mineclaw.workspace.ToolCatalog;
import cc.kites.mineclaw.workspace.ToolDefinition;
import cc.kites.mineclaw.workspace.SkillFunctionReferenceValidator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionCatalogLoaderTest {
    private static JavaScriptWorkflowRuntime runtime;

    @BeforeAll
    static void startRuntime() {
        runtime = new JavaScriptWorkflowRuntime(JavaScriptLimits.defaults());
    }

    @AfterAll
    static void stopRuntime() {
        runtime.close();
    }

    @Test
    void bundledSeedFunctionCompilesAndHasAValidSkillReference(@TempDir Path root) throws Exception {
        Path functions = root.resolve(FunctionCatalogLoader.FUNCTIONS_FILE_NAME);
        Path skills = Files.createDirectories(root.resolve("skills"));
        try (var stream = FunctionCatalogLoaderTest.class.getResourceAsStream("/functions.yml")) {
            assertThat(stream).isNotNull();
            Files.copy(stream, functions);
        }
        try (var stream = FunctionCatalogLoaderTest.class
                .getResourceAsStream("/workspace/skills/self-potion-effect.md")) {
            assertThat(stream).isNotNull();
            Files.copy(stream, skills.resolve("self-potion-effect.md"));
        }

        FunctionCatalog catalog = loader(Set.of()).load(root, functions);
        SkillFunctionReferenceValidator.Report references =
                new SkillFunctionReferenceValidator().validate(skills, catalog);

        assertThat(catalog.diagnostics()).isEmpty();
        assertThat(catalog.findEnabled("player.effect.give")).isPresent();
        assertThat(references.diagnostics()).isEmpty();
        assertThat(references.referencedFunctions()).containsExactly("player.effect.give");
    }

    @Test
    void loadsPreparedImmutableDefinitionsAndMonotonicGenerations() {
        FunctionCatalogLoader loader = loader(Set.of("online_players"));
        FunctionCatalog first = loader.parse(validDocument("example.echo", true,
                "[approval.request, native_tool.call.online_players]", validSchema(), validSource()));
        FunctionDefinition definition = first.findEnabled("example.echo").orElseThrow();

        assertThat(first.generation()).isEqualTo(1L);
        assertThat(definition.status()).isEqualTo(FunctionDefinition.Status.ENABLED);
        assertThat(definition.capabilities())
                .containsExactly("approval.request", "native_tool.call.online_players");
        assertThat(definition.scriptHash()).contains(sha256(validSource() + '\n'));
        assertThat(definition.preparedSource().orElseThrow().functionName()).isEqualTo("example.echo");
        assertThat(definition.compiledParameters().orElseThrow().validate(
                JsonParser.parseString("{\"value\":\"ok\"}")).valid()).isTrue();
        assertThatThrownBy(() -> first.definitions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.capabilities().add("approval.request"))
                .isInstanceOf(UnsupportedOperationException.class);

        FunctionCatalog second = loader.parse("schema: 1\napi_version: 1\nfunctions: []\n");
        assertThat(second.generation()).isEqualTo(2L);
        assertThat(second.definitions()).isEmpty();
    }

    @Test
    void retainsDisabledEntriesButMakesThemUnavailable() {
        FunctionCatalog catalog = loader(Set.of()).parse(validDocument(
                "example.disabled", false, "[]", validSchema(), validSource()));

        assertThat(catalog.find("example.disabled")).isPresent();
        assertThat(catalog.findEnabled("example.disabled")).isEmpty();
        assertThat(catalog.find("example.disabled").orElseThrow().status())
                .isEqualTo(FunctionDefinition.Status.DISABLED);
    }

    @Test
    void marksEveryDuplicateInvalidRegardlessOfEnabledState() {
        String first = entry("same.name", true, "[]", validSchema(), validSource());
        String second = entry("same.name", false, "[]", validSchema(), validSource());
        FunctionCatalog catalog = loader(Set.of()).parse(document(first + second));

        assertThat(catalog.definitions()).hasSize(2)
                .allMatch(definition -> definition.status() == FunctionDefinition.Status.INVALID)
                .allMatch(definition -> definition.diagnostic().orElse("")
                        .equals("duplicate function name same.name"));
        assertThat(catalog.findEnabled("same.name")).isEmpty();
    }

    @Test
    void isolatesBadSchemaCapabilitySourceAndYamlScalarEntries() {
        String good = entry("good.echo", true, "[]", validSchema(), validSource());
        String badSchema = entry("bad.schema", true, "[]",
                "{type: object, additionalProperties: true}", validSource());
        String badCapability = entry("bad.capability", true, "[unknown.capability]",
                validSchema(), validSource());
        String badSource = entry("bad.source", true, "[]", validSchema(),
                "function onCall( {");
        String timestamp = """
                  - name: bad.timestamp
                    description: timestamp enum
                    enabled: true
                    capabilities: []
                    parameters:
                      type: object
                      properties:
                        value: {type: string, enum: [2026-08-03]}
                      additionalProperties: false
                    on_call: |
                      function onCall() { return {status: "ok", output: {}}; }
                """;

        FunctionCatalog catalog = loader(Set.of()).parse(document(
                good + badSchema + badCapability + badSource + timestamp));

        assertThat(catalog.findEnabled("good.echo")).isPresent();
        assertThat(catalog.invalidDefinitions()).hasSize(4);
        assertThat(catalog.diagnostics())
                .anyMatch(value -> value.contains("invalid parameters Schema"))
                .anyMatch(value -> value.contains("unknown capability"))
                .anyMatch(value -> value.contains("javascript_syntax_error"))
                .anyMatch(value -> value.contains("non-JSON YAML value"));
    }

    @Test
    void rejectsUnknownMissingOrWrongTypedRootFieldsAsEmptyCatalog() {
        FunctionCatalogLoader loader = loader(Set.of());

        assertRootInvalid(loader.parse("schema: 1\napi_version: 1\nfunctions: []\nextra: true\n"));
        assertRootInvalid(loader.parse("schema: 1.0\napi_version: 1\nfunctions: []\n"));
        assertRootInvalid(loader.parse("schema: 1\napi_version: 2\nfunctions: []\n"));
        assertRootInvalid(loader.parse("schema: 1\napi_version: 1\nfunctions: {}\n"));
        assertRootInvalid(loader.parse("[]\n"));
    }

    @Test
    void strictlyChecksEntryFieldsNamesBooleansDescriptionsAndDuplicateCapabilities() {
        String missingField = """
                  - name: missing.source
                    description: missing source
                    enabled: true
                    capabilities: []
                    parameters: {type: object, additionalProperties: false}
                """;
        String unknownField = entry("unknown.field", true, "[]", validSchema(), validSource())
                .replace("    enabled: true\n", "    enabled: true\n    surprise: true\n");
        String badName = entry("Upper.Name", true, "[]", validSchema(), validSource());
        String blankDescription = entry("blank.description", true, "[]", validSchema(), validSource())
                .replace("Echo function", "\"   \"");
        String nonBoolean = entry("bad.enabled", true, "[]", validSchema(), validSource())
                .replace("enabled: true", "enabled: yes-please");
        String duplicateCapability = entry("duplicate.capability", true,
                "[approval.request, approval.request]", validSchema(), validSource());
        String good = entry("still.good", true, "[]", validSchema(), validSource());

        FunctionCatalog catalog = loader(Set.of()).parse(document(missingField + unknownField + badName
                + blankDescription + nonBoolean + duplicateCapability + good));

        assertThat(catalog.findEnabled("still.good")).isPresent();
        assertThat(catalog.invalidDefinitions()).hasSize(6);
        assertThat(catalog.diagnostics())
                .anyMatch(value -> value.contains("entry must contain only"))
                .anyMatch(value -> value.contains("name must match"))
                .anyMatch(value -> value.contains("description must be non-blank"))
                .anyMatch(value -> value.contains("enabled must be a boolean"))
                .anyMatch(value -> value.contains("duplicate capability"));
    }

    @Test
    void rejectsDuplicateKeysAnchorsAliasesMergesAndCustomTags() {
        FunctionCatalogLoader loader = loader(Set.of());

        assertRootInvalid(loader.parse("schema: 1\nschema: 1\napi_version: 1\nfunctions: []\n"));
        assertRootInvalid(loader.parse("schema: 1\napi_version: 1\nfunctions: &items []\n"));
        assertRootInvalid(loader.parse("schema: 1\napi_version: 1\nfunctions: &items []\nother: *items\n"));
        assertRootInvalid(loader.parse("""
                schema: 1
                api_version: 1
                functions:
                  - <<: {name: merged}
                """));
        assertRootInvalid(loader.parse("schema: 1\napi_version: 1\nfunctions: !unsafe []\n"));
    }

    @Test
    void capabilitiesUseAnExplicitNativeAllowlistAndNeverPermitCallFunction() {
        FunctionCatalogLoader loader = loader(Set.of("online_players", "call_function"));
        String allowed = entry("allowed.native", true, "[native_tool.call.online_players]",
                validSchema(), validSource());
        String recursion = entry("bad.recursion", true, "[native_tool.call.call_function]",
                validSchema(), validSource());
        String aliasNotAllowed = entry("bad.alias", true, "[native_tool.call.function_alias]",
                validSchema(), validSource());

        FunctionCatalog catalog = loader.parse(document(allowed + recursion + aliasNotAllowed));

        assertThat(catalog.findEnabled("allowed.native")).isPresent();
        assertThat(catalog.findEnabled("bad.recursion")).isEmpty();
        assertThat(catalog.findEnabled("bad.alias")).isEmpty();
    }

    @Test
    void perParseSnapshotUsesExactHandlerIdsAndExcludesCallFunction() {
        ToolDefinition onlinePlayers = nativeTool(1, "online_players", ToolDefinition.Status.ENABLED);
        ToolDefinition disabledRead = nativeTool(2, "read", ToolDefinition.Status.DISABLED);
        ToolDefinition functionGateway = nativeTool(3, "call_function", ToolDefinition.Status.ENABLED);
        ToolCatalog tools = new ToolCatalog(
                List.of(onlinePlayers, disabledRead, functionGateway), List.of());
        Set<String> allowlist = FunctionCatalogLoader.nativeCapabilityAllowlist(tools);

        assertThat(allowlist).containsExactlyInAnyOrder("online_players", "read");
        assertThat(allowlist).doesNotContain("call_function", "players_here");

        String canonical = entry("canonical.allowed", true, "[native_tool.call.online_players]",
                validSchema(), validSource());
        String alias = entry("alias.denied", true, "[native_tool.call.players_here]",
                validSchema(), validSource());
        String gateway = entry("gateway.denied", true, "[native_tool.call.call_function]",
                validSchema(), validSource());
        FunctionCatalog catalog = loader(Set.of("online_players", "call_function"))
                .parse(document(canonical + alias + gateway), allowlist);

        assertThat(catalog.findEnabled("canonical.allowed")).isPresent();
        assertThat(catalog.findEnabled("alias.denied")).isEmpty();
        assertThat(catalog.findEnabled("gateway.denied")).isEmpty();
        assertThat(catalog.invalidDefinitions())
                .extracting(FunctionDefinition::name)
                .containsExactly("alias.denied", "gateway.denied");
    }

    @Test
    void perLoadSnapshotAcceptsARegisteredHandlerId(@TempDir Path root) throws IOException {
        Path file = root.resolve(FunctionCatalogLoader.FUNCTIONS_FILE_NAME);
        Files.writeString(file, validDocument("handler.loaded", true,
                "[native_tool.call.online_players]", validSchema(), validSource()));
        FunctionCatalogLoader loader = loader(Set.of());

        FunctionCatalog catalog = loader.load(root, file, Set.of("online_players"));

        assertThat(catalog.findEnabled("handler.loaded")).isPresent();
    }

    @Test
    void enforcesFrozenDescriptionCapAndConfiguredEntrySourceAndFileLimits() {
        FunctionCatalogLoader.Limits broadDescription = new FunctionCatalogLoader.Limits(
                20_000, 2, 1_000, 5_000, 16, 500, 8, 1_000);
        FunctionCatalogLoader loader = new FunctionCatalogLoader(ignored -> { }, runtime::validateSource,
                Set.of(), broadDescription);
        String description = "界".repeat(513);
        String oversizedDescription = validDocument("bad.description", true, "[]", validSchema(),
                validSource()).replace("Echo function", description);
        assertThat(loader.parse(oversizedDescription).invalidDefinitions()).hasSize(1);

        FunctionCatalogLoader.Limits oneEntry = new FunctionCatalogLoader.Limits(
                20_000, 1, 512, 5_000, 16, 500, 8, 30);
        FunctionCatalogLoader limited = new FunctionCatalogLoader(ignored -> { }, runtime::validateSource,
                Set.of(), oneEntry);
        assertRootInvalid(limited.parse(document(
                entry("one", true, "[]", validSchema(), validSource())
                        + entry("two", true, "[]", validSchema(), validSource()))));
        assertThat(limited.parse(validDocument("large.source", true, "[]", validSchema(),
                validSource() + " ".repeat(31))).invalidDefinitions()).hasSize(1);

        FunctionCatalogLoader.Limits tinyFile = new FunctionCatalogLoader.Limits(
                20, 1, 512, 100, 4, 20, 2, 100);
        assertRootInvalid(new FunctionCatalogLoader(ignored -> { }, runtime::validateSource,
                Set.of(), tinyFile).parse("schema: 1\napi_version: 1\nfunctions: []\n"));
    }

    @Test
    void missingFileIsEmptyAndMalformedUtf8IsInvalid(@TempDir Path root) throws IOException {
        FunctionCatalogLoader loader = loader(Set.of());
        Path file = root.resolve(FunctionCatalogLoader.FUNCTIONS_FILE_NAME);

        FunctionCatalog missing = loader.load(root, file);
        assertThat(missing.definitions()).isEmpty();
        assertThat(missing.diagnostics()).isEmpty();

        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
        FunctionCatalog malformed = loader.load(root, file);
        assertRootInvalid(malformed);
        assertThat(malformed.diagnostics().getFirst()).contains("UTF-8");
    }

    @Test
    void preparedIdentityMustMatchDefinition() {
        FunctionCatalogLoader loader = new FunctionCatalogLoader(ignored -> { },
                (name, version, source) -> runtime.validateSource("different.name", version, source),
                Set.of(), FunctionCatalogLoader.Limits.defaults());

        FunctionDefinition definition = loader.parse(validDocument("expected.name", true, "[]",
                validSchema(), validSource())).definitions().getFirst();

        assertThat(definition.status()).isEqualTo(FunctionDefinition.Status.INVALID);
        assertThat(definition.diagnostic().orElseThrow()).contains("identity does not match");
    }

    @Test
    void argumentViolationsComeFromTheCatalogCompiledSnapshot() {
        FunctionDefinition definition = loader(Set.of()).parse(validDocument("schema.check", true,
                "[]", validSchema(), validSource())).findEnabled("schema.check").orElseThrow();

        var result = definition.requireCompiledParameters().validate(
                JsonParser.parseString("{\"value\":\"\",\"unknown\":1}"));

        assertThat(result.violations()).containsExactly(
                new SchemaViolation("$.value", "minLength", "must contain at least 1 code points"),
                new SchemaViolation("$.unknown", "additionalProperties", "property is not allowed"));
    }

    private static FunctionCatalogLoader loader(Set<String> nativeNames) {
        return new FunctionCatalogLoader(ignored -> { }, runtime::validateSource, nativeNames,
                FunctionCatalogLoader.Limits.defaults());
    }

    private static ToolDefinition nativeTool(
            int index,
            String name,
            ToolDefinition.Status status
    ) {
        JsonObject payload = JsonParser.parseString("""
                {"type":"function","function":{"name":"%s","description":"native",
                "parameters":{"type":"object","properties":{},"additionalProperties":false}}}
                """.formatted(name)).getAsJsonObject();
        return new ToolDefinition(index, name, payload,
                status == ToolDefinition.Status.ENABLED, status,
                status == ToolDefinition.Status.DISABLED ? Optional.of("disabled") : Optional.empty());
    }

    private static void assertRootInvalid(FunctionCatalog catalog) {
        assertThat(catalog.definitions()).isEmpty();
        assertThat(catalog.diagnostics()).isNotEmpty();
    }

    private static String validDocument(
            String name,
            boolean enabled,
            String capabilities,
            String parameters,
            String source
    ) {
        return document(entry(name, enabled, capabilities, parameters, source));
    }

    private static String document(String entries) {
        return "schema: 1\napi_version: 1\nfunctions:\n" + entries;
    }

    private static String entry(
            String name,
            boolean enabled,
            String capabilities,
            String parameters,
            String source
    ) {
        return "  - name: " + name + '\n'
                + "    description: Echo function\n"
                + "    enabled: " + enabled + '\n'
                + "    capabilities: " + capabilities + '\n'
                + "    parameters: " + parameters + '\n'
                + "    on_call: |\n"
                + source.lines().map(line -> "      " + line + '\n').reduce("", String::concat);
    }

    private static String validSchema() {
        return "{type: object, properties: {value: {type: string, minLength: 1, maxLength: 16}}, "
                + "required: [value], additionalProperties: false}";
    }

    private static String validSource() {
        return "async function onCall(ctx, api) {\n"
                + "  return {status: \"ok\", output: {value: ctx.args.value}};\n"
                + "}";
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
