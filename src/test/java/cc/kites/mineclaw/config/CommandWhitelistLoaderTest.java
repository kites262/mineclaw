package cc.kites.mineclaw.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandWhitelistLoaderTest {
    private final CommandWhitelistLoader loader = new CommandWhitelistLoader();

    @Test
    void loadsStrictSeparatedFullMatchRules() throws Exception {
        CommandWhitelist whitelist = loader.parse("""
                schema: 1
                enabled: true
                player: ['^home [a-z]+$']
                console: ['^say .+$']
                """);

        assertThat(whitelist.rules().playerAllowed("HOME spawn")).isTrue();
        assertThat(whitelist.rules().playerAllowed("home spawn extra")).isFalse();
        assertThat(whitelist.rules().consoleAllowed("say hello")).isTrue();
        assertThat(whitelist.rules().consoleAllowed("home spawn")).isFalse();
    }

    @Test
    void rejectsUnknownMissingDuplicateEmptyAndInvalidRules() {
        for (String source : new String[]{
                "schema: 1\nenabled: true\nplayer: []\nconsole: []\nextra: true\n",
                "schema: 1\nenabled: true\nplayer: []\n",
                "schema: 1\nenabled: true\nplayer: ['x', 'x']\nconsole: []\n",
                "schema: 1\nenabled: true\nplayer: ['']\nconsole: []\n",
                "schema: 1\nenabled: true\nplayer: ['[']\nconsole: []\n"}) {
            assertThatThrownBy(() -> loader.parse(source)).isInstanceOf(ConfigException.class);
        }
    }
}
