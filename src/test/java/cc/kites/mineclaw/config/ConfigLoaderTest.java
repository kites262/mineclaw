package cc.kites.mineclaw.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void bundledConfigContainsOnlyLocalRuntimeSettings() throws Exception {
        MineclawConfig config = loader.parse(resource("/config.yml"));

        assertThat(config.schema()).isEqualTo(1);
        assertThat(config.context()).isEqualTo(new MineclawConfig.Context(24));
        assertThat(config.chat().publicPrefix()).isEqualTo("@ai");
        assertThat(config.tools().enabled()).isTrue();
        assertThat(config.identity().name()).isEqualTo("Mineclaw");
    }

    @Test
    void rejectsLegacyApiCommandsAndContextTokenFields() {
        assertThatThrownBy(() -> loader.parse("schema: 1\napi: {}\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("api is a legacy section")
                .hasMessageContaining("providers.yml");
        assertThatThrownBy(() -> loader.parse("schema: 1\ncommands: {}\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("commands is a legacy section")
                .hasMessageContaining("whitelist.yml");
        assertThatThrownBy(() -> loader.parse("schema: 1\ncontext: {max_tokens: 24000}\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("context.max_tokens is not a supported field");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidSectionShapes() {
        assertThatThrownBy(() -> loader.parse("schema: 1\nprovider: mimo\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("provider is not a supported field");
        assertThatThrownBy(() -> loader.parse("schema: 1\nchat: true\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("chat must be a mapping");
    }

    @Test
    void rejectsUnsafeYamlFeaturesBeforeBukkitParsing() {
        for (String source : new String[]{
                "schema: 1\nschema: 1\n",
                "schema: &version 1\nidentity: {name: *version}\n",
                "schema: 1\nidentity: !custom {name: Mineclaw}\n",
                "schema: 1\nidentity: {<<: {name: Mineclaw}}\n"}) {
            assertThatThrownBy(() -> loader.parse(source)).isInstanceOf(ConfigException.class);
        }
    }

    @Test
    void stillValidatesLocalRuntimeRelationships() {
        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                chat: {public_prefix: '', wake_pattern: ''}
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("cannot both be empty");
        assertThatThrownBy(() -> loader.parse("""
                schema: 1
                javascript:
                  max_operations_per_invocation: 2
                  max_concurrent_operations: 3
                """))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must not exceed");
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = ConfigLoaderTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
