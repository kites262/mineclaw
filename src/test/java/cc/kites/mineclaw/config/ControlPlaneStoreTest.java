package cc.kites.mineclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneStoreTest {
    @Test
    void publishesAllThreeFilesAtomicallyAndKeepsOldSnapshotOnFailure(@TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve(".env"), "KEY=secret\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("config.yml"), "schema: 1\nidentity: {name: First}\n");
        Files.writeString(directory.resolve("providers.yml"), providers(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("whitelist.yml"), whitelist(true), StandardCharsets.UTF_8);
        ControlPlaneStore store = new ControlPlaneStore(directory, new ConfigLoader(),
                new ProviderCatalogLoader(name -> null), new CommandWhitelistLoader());

        ControlPlaneSnapshot first = store.loadInitial();
        assertThat(first.config().identity().name()).isEqualTo("First");
        assertThat(first.providers().defaultModel()).isEqualTo("mimo/model");
        assertThat(first.whitelist().enabled()).isTrue();

        Files.writeString(directory.resolve("config.yml"), "schema: 1\nidentity: {name: Second}\n");
        Files.writeString(directory.resolve("whitelist.yml"), """
                schema: 1
                enabled: false
                player: ['x', 'x']
                console: []
                """);

        assertThatThrownBy(store::reload).isInstanceOf(ConfigException.class);
        assertThat(store.get()).isSameAs(first);
        assertThat(store.get().config().identity().name()).isEqualTo("First");
        assertThat(store.get().whitelist().enabled()).isTrue();

        Files.writeString(directory.resolve("whitelist.yml"), whitelist(false));
        ControlPlaneSnapshot second = store.reload();
        assertThat(second.config().identity().name()).isEqualTo("Second");
        assertThat(second.whitelist().enabled()).isFalse();
    }

    private static String providers() {
        return """
                schema: 1
                default: mimo/model
                providers:
                  mimo:
                    api: {type: openai_chat_completions, base_url: https://example.com/v1, api_key: '${KEY}'}
                    transport: {timeout_ms: 1000, retry: {max_retries: 0, backoff_ms: 0}}
                    tools: []
                models:
                  mimo/model:
                    limits: {context_window_tokens: 4096, max_output_tokens: 512}
                """;
    }

    private static String whitelist(boolean enabled) {
        return "schema: 1\nenabled: " + enabled + "\nplayer: []\nconsole: []\n";
    }
}
