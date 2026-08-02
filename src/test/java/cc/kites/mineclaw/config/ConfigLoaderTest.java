package cc.kites.mineclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void parsesEverySeedConfigSectionAndPrecompilesRegexes() throws Exception {
        MineclawConfig config = loader.parse(resource("/config.yml"));

        assertThat(config.schema()).isEqualTo(1);
        assertThat(config.api().baseUrl().toString())
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(config.api().apiKey()).isEqualTo("MINECLAW_API_KEY");
        assertThat(config.api().model()).isEqualTo("gpt-5-mini");
        assertThat(config.api().timeoutMillis()).isEqualTo(60_000);
        assertThat(config.api().maxRetries()).isEqualTo(5);
        assertThat(config.api().retryBackoffMillis()).isEqualTo(500);
        assertThat(config.context()).isEqualTo(new MineclawConfig.Context(24, 24_000));
        assertThat(config.chat().publicPrefix()).isEqualTo("@ai");
        assertThat(config.chat().wakePattern()).isEmpty();
        assertThat(config.chat().replyMaxChars()).isEqualTo(2_000);
        assertThat(config.chat().actionbarMaxChars()).isEqualTo(120);
        assertThat(config.tools().enabled()).isTrue();
        assertThat(config.tools().disabled()).isEmpty();
        assertThat(config.commands().runEnabled()).isFalse();
        assertThat(config.commands().playerWhitelist())
                .extracting(java.util.regex.Pattern::pattern)
                .containsExactly("^locate structure #?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$");
        assertThat(config.commands().playerCommandAllowed("locate structure end_city")).isTrue();
        assertThat(config.commands().playerCommandAllowed("locate structure #minecraft:village")).isTrue();
        assertThat(config.commands().playerCommandAllowed("locate structure end_city extra")).isFalse();
        assertThat(config.commands().playerCommandAllowed("home village")).isFalse();
        assertThat(config.commands().playerCommandAllowed("spawn")).isFalse();
        assertThat(config.commands().consoleWhitelist()).isEmpty();
        assertThat(config.commands().consoleCommandAllowed("say hello")).isFalse();
        assertThat(config.commands().consoleCommandAllowed("kp warp list")).isFalse();
        assertThat(config.commands().consoleCommandAllowed("kp warp set home")).isFalse();
        assertThat(config.rateLimit()).isEqualTo(new MineclawConfig.RateLimit(5_000, 1_000));
        assertThat(config.workspace().seedDefaults()).isTrue();
        assertThat(config.workspace().maxChars().agents()).isEqualTo(16_000);
        assertThat(config.fileTools()).isEqualTo(new MineclawConfig.FileTools(12_000, 100, 4, 3_000));
        assertThat(config.turn()).isEqualTo(new MineclawConfig.Turn(8, 24));
        assertThat(config.identity().name()).isEqualTo("Mineclaw");
        assertThat(config.environment().lookDistance()).isEqualTo(12);
        assertThat(config.environment().toolCooldownMillis()).isEqualTo(250);
        assertThat(config.environment().inventory())
                .isEqualTo(new MineclawConfig.Environment.Inventory(true, 36));
        assertThat(config.logging().level()).isEqualTo(Level.INFO);
    }

    @Test
    void omittedKeysUseSafeSchemaDefaults() throws Exception {
        MineclawConfig config = loader.parse("schema: 1\n");

        assertThat(config).isEqualTo(MineclawConfig.defaults());
    }

    @Test
    void processEnvironmentOverridesDotenvForAllApiReferences(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, referencedApiConfig(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), """
                API_BASE=https://dotenv.example/v1/chat/completions
                API_MODEL=dotenv-model
                API_TOKEN=dotenv-secret
                """, StandardCharsets.UTF_8);
        ConfigLoader environmentLoader = new ConfigLoader(Map.of(
                "API_BASE", "https://system.example/v1/chat/completions",
                "API_MODEL", "system-model",
                "API_TOKEN", "system-secret")::get);

        MineclawConfig.Api api = new ConfigStore(path, environmentLoader).loadInitial().api();

        assertThat(api.baseUrl()).hasToString("https://system.example/v1/chat/completions");
        assertThat(api.model()).isEqualTo("system-model");
        assertThat(api.configuredApiKey()).contains("system-secret");
        assertThat(api.toString()).doesNotContain("system.example", "system-model", "system-secret",
                "dotenv.example", "dotenv-model", "dotenv-secret", "API_BASE", "API_MODEL", "API_TOKEN");
    }

    @Test
    void dotenvThenConfigLiteralProvideUnifiedFallbacks(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, referencedApiConfig(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), """
                API_BASE=https://dotenv.example/v1/chat/completions
                API_MODEL=dotenv-model
                API_TOKEN=dotenv-secret
                """, StandardCharsets.UTF_8);

        MineclawConfig.Api dotenv = new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial().api();

        assertThat(dotenv.baseUrl()).hasToString("https://dotenv.example/v1/chat/completions");
        assertThat(dotenv.model()).isEqualTo("dotenv-model");
        assertThat(dotenv.configuredApiKey()).contains("dotenv-secret");

        Files.writeString(path, """
                schema: 1
                api:
                  base_url: https://literal.example/v1/chat/completions
                  model: literal-model
                  api_key: sk-literal
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), "UNRELATED=value\n", StandardCharsets.UTF_8);
        MineclawConfig.Api literal = new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial().api();

        assertThat(literal.baseUrl()).hasToString("https://literal.example/v1/chat/completions");
        assertThat(literal.model()).isEqualTo("literal-model");
        assertThat(literal.configuredApiKey()).contains("sk-literal");
    }

    @Test
    void emptyDefinedValuesTerminateFallbackAtTheirPrecedenceLevel(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, referencedApiConfig(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), """
                API_BASE=https://dotenv.example/v1/chat/completions
                API_MODEL=dotenv-model
                API_TOKEN=dotenv-secret
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ConfigStore(path,
                new ConfigLoader(Map.of("API_BASE", "")::get)).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage("api.base_url must not be blank")
                .hasNoCause();
        assertThatThrownBy(() -> new ConfigStore(path,
                new ConfigLoader(Map.of("API_MODEL", "")::get)).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage("api.model must not be blank")
                .hasNoCause();

        MineclawConfig.Api api = new ConfigStore(path,
                new ConfigLoader(Map.of("API_TOKEN", "")::get)).loadInitial().api();
        assertThat(api.configuredApiKey()).isEmpty();

        Files.writeString(directory.resolve(".env"), """
                API_BASE=
                API_MODEL=dotenv-model
                API_TOKEN=dotenv-secret
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage("api.base_url must not be blank");

        Files.writeString(directory.resolve(".env"), """
                API_BASE=https://dotenv.example/v1/chat/completions
                API_MODEL=
                API_TOKEN=dotenv-secret
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage("api.model must not be blank");
    }

    @Test
    void seededEmptyApiKeyAllowsLoadButRemainsUnavailable(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, resource("/config.yml"), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), "MINECLAW_API_KEY=\n", StandardCharsets.UTF_8);

        MineclawConfig.Api api = new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial().api();

        assertThat(api.baseUrl()).hasToString("https://api.openai.com/v1/chat/completions");
        assertThat(api.model()).isEqualTo("gpt-5-mini");
        assertThat(api.configuredApiKey()).isEmpty();
    }

    @Test
    void dotenvSupportsCommonQuotesCommentsAndExportPrefix(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "schema: 1\napi: {api_key: API_TOKEN}\n",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), """
                # Local secrets
                UNUSED=plain # comment
                export API_TOKEN="line\\nvalue#kept" # comment
                """, StandardCharsets.UTF_8);

        assertThat(new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial().api().configuredApiKey())
                .contains("line\nvalue#kept");
    }

    @Test
    void dotenvDistinguishesAnEmptyCommentedValueFromAHashLiteral(@TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "schema: 1\napi: {api_key: API_TOKEN}\n",
                StandardCharsets.UTF_8);
        Path dotEnv = directory.resolve(".env");
        Files.writeString(dotEnv, "API_TOKEN= # placeholder\n", StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(path, new ConfigLoader(name -> null));

        assertThat(store.loadInitial().api().configuredApiKey()).isEmpty();

        Files.writeString(dotEnv, "API_TOKEN=#literal\n", StandardCharsets.UTF_8);
        assertThat(store.reload().api().configuredApiKey()).contains("#literal");
    }

    @Test
    void invalidResolvedBaseUrlAndModelFailWithoutDisclosingValues(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, referencedApiConfig(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), """
                API_BASE=https://valid.example/v1/chat/completions
                API_MODEL=TOP SECRET MODEL
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ConfigStore(path,
                new ConfigLoader(Map.of("API_BASE", "TOP_SECRET_INVALID_URL")::get)).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage("api.base_url must be an absolute HTTP(S) URI")
                .hasMessageNotContaining("TOP_SECRET_INVALID_URL")
                .hasNoCause();
        assertThatThrownBy(() -> new ConfigStore(path, new ConfigLoader(name -> null)).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage("api.model must not contain whitespace or control characters")
                .hasMessageNotContaining("TOP SECRET MODEL")
                .hasNoCause();
    }

    @Test
    void reloadPublishesOneResolvedApiSnapshotAndLeavesPriorTurnConfigUnchanged(@TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, referencedApiConfig(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".env"), "API_TOKEN=unused-dotenv\n", StandardCharsets.UTF_8);
        AtomicReference<Map<String, String>> process = new AtomicReference<>(Map.of(
                "API_BASE", "https://first.example/v1/chat/completions",
                "API_MODEL", "first-model",
                "API_TOKEN", "first-key"));
        ConfigStore store = new ConfigStore(path, new ConfigLoader(name -> process.get().get(name)));

        MineclawConfig first = store.loadInitial();
        process.set(Map.of(
                "API_BASE", "https://second.example/v1/chat/completions",
                "API_MODEL", "second-model",
                "API_TOKEN", "second-key"));
        MineclawConfig second = store.reload();

        assertThat(first.api().baseUrl()).hasToString("https://first.example/v1/chat/completions");
        assertThat(first.api().model()).isEqualTo("first-model");
        assertThat(first.api().configuredApiKey()).contains("first-key");
        assertThat(second.api().baseUrl()).hasToString("https://second.example/v1/chat/completions");
        assertThat(second.api().model()).isEqualTo("second-model");
        assertThat(second.api().configuredApiKey()).contains("second-key");
        assertThat(store.get()).isSameAs(second);
    }

    @Test
    void invalidOrOversizedDotenvDoesNotReplacePublishedSnapshot(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Path dotEnv = directory.resolve(".env");
        Files.writeString(path, "schema: 1\napi: {api_key: API_TOKEN}\n",
                StandardCharsets.UTF_8);
        Files.writeString(dotEnv, "API_TOKEN=first\n", StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(path);
        MineclawConfig initial = store.loadInitial();

        Files.writeString(dotEnv, "API_TOKEN='TOP_SECRET_VALUE\n", StandardCharsets.UTF_8);
        assertThatThrownBy(store::reload)
                .isInstanceOf(ConfigException.class)
                .hasMessage(".env contains an invalid entry at line 1")
                .hasMessageNotContaining("TOP_SECRET_VALUE");
        assertThat(store.get()).isSameAs(initial);

        Files.writeString(dotEnv, "x".repeat(DotEnvLoader.MAX_BYTES + 1), StandardCharsets.UTF_8);
        assertThatThrownBy(store::reload)
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("65536-byte limit");
        assertThat(store.get()).isSameAs(initial);
    }

    @Test
    void dotenvRejectsSymbolicLinksAndNonRegularFiles(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Path dotEnv = directory.resolve(".env");
        Files.writeString(path, "schema: 1\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("secrets.txt"), "MINECLAW_API_KEY=secret\n",
                StandardCharsets.UTF_8);
        Files.createSymbolicLink(dotEnv, Path.of("secrets.txt"));

        assertThatThrownBy(() -> new ConfigStore(path).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must not be a symbolic link")
                .hasMessageNotContaining("secret");

        Files.delete(dotEnv);
        Files.createDirectory(dotEnv);
        assertThatThrownBy(() -> new ConfigStore(path).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must be a regular file");
    }

    @Test
    void dotenvRejectsMalformedUtf8WithoutEchoingBytes(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "schema: 1\n", StandardCharsets.UTF_8);
        Files.write(directory.resolve(".env"), new byte[]{'K', 'E', 'Y', '=', (byte) 0xc3, 0x28});

        assertThatThrownBy(() -> new ConfigStore(path).loadInitial())
                .isInstanceOf(ConfigException.class)
                .hasMessage(".env is not valid UTF-8");
    }

    @Test
    void rejectsInvalidCommandAndWakeRegexesDuringParsing() {
        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                commands:
                  player_whitelist: ['[']
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("commands.player_whitelist[0]")
                .hasMessageContaining("regular expression");

        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                chat:
                  wake_pattern: '('
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("chat.wake_pattern")
                .hasMessageContaining("regular expression");
    }

    @Test
    void rejectsWrongTypesAndUnsafeNumericValues() {
        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                tools:
                  enabled: 'yes'
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("tools.enabled must be a boolean");

        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                turn:
                  max_tool_calls: 0
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("turn.max_tool_calls must be greater than zero");

        assertThatThrownBy(() -> loader.parse("schema: 1\napi: not-a-mapping\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("api must be a mapping");

        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                workspace:
                  max_chars:
                    agents: 1000001
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("workspace.max_chars.agents must not exceed 1000000");

        assertThatThrownBy(() -> loader.parse("schema: 1\napi: {max_retries: 21}\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("api.max_retries must not exceed 20");
    }

    @Test
    void yamlParserErrorsDoNotEchoNearbyApiKeys() {
        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                api:
                  api_key: TOP_SECRET_VALUE
                  broken: [
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessage("config.yml contains invalid YAML")
                .hasNoCause()
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }

    @Test
    void storePublishesOnlySuccessfulReloads(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("config.yml");
        Files.writeString(path, "schema: 1\nidentity: {name: First}\n", StandardCharsets.UTF_8);
        ConfigStore store = new ConfigStore(path);

        MineclawConfig initial = store.loadInitial();
        assertThat(initial.identity().name()).isEqualTo("First");

        Files.writeString(path, "schema: 1\ncommands: {player_whitelist: ['[']}\n", StandardCharsets.UTF_8);
        assertThatThrownBy(store::reload).isInstanceOf(ConfigException.class);
        assertThat(store.get()).isSameAs(initial);

        Files.writeString(path, "schema: 1\nidentity: {name: Second}\n", StandardCharsets.UTF_8);
        assertThat(store.reload().identity().name()).isEqualTo("Second");
        assertThat(store.path()).isEqualTo(path.toAbsolutePath().normalize());
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = ConfigLoaderTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String referencedApiConfig() {
        return """
                schema: 1
                api:
                  base_url: API_BASE
                  model: API_MODEL
                  api_key: API_TOKEN
                """;
    }
}
