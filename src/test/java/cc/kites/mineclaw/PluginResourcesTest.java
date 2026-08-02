package cc.kites.mineclaw;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PluginResourcesTest {
    @Test
    void paperDescriptorMatchesRuntimeAndDeclaresEveryPermission() throws Exception {
        YamlConfiguration descriptor = yaml("/paper-plugin.yml");
        assertThat(descriptor.getString("name")).isEqualTo("Mineclaw");
        assertThat(descriptor.getString("version")).isEqualTo("0.1.0");
        assertThat(descriptor.getString("main")).isEqualTo("cc.kites.mineclaw.MineclawPlugin");
        assertThat(descriptor.getString("api-version")).isEqualTo("26.2");
        assertThat(descriptor.getBoolean("folia-supported")).isTrue();
        assertThat(descriptor.getConfigurationSection("permissions")).isNotNull();
        for (String permission : Set.of(
                "mineclaw.command.chat", "mineclaw.command.clear", "mineclaw.command.approve",
                "mineclaw.command.reload", "mineclaw.command.tools", "mineclaw.bypass.ratelimit")) {
            assertThat(descriptor.contains("permissions." + permission + ".default"))
                    .as("descriptor contains %s", permission).isTrue();
        }
    }

    @Test
    void bundledMessagesCoverAllRuntimeKeysAndResourcesContainNoSecret() throws Exception {
        YamlConfiguration messages = yaml("/message.yml");
        assertThat(messages.getKeys(false)).containsAll(Set.of(
                "no_permission", "permission_unavailable", "player_only", "busy", "rate_limited",
                "empty_question",
                "api_failure", "tool_loop_limit", "reply_prefix", "clear_success", "reload_success",
                "reload_in_progress", "reload_failure", "approve_prompt", "approve_title",
                "approve_requester", "approve_command", "approve_intent", "approve_player",
                "approve_expires", "approve_gesture", "approve_accept_button", "approve_reject_button",
                "approve_accept_hover", "approve_reject_hover", "approve_none", "approve_started",
                "approve_rejected", "approve_timeout", "approve_unavailable",
                "tools_header", "tools_entry", "tools_failure",
                "usage"));
        for (String reloadKey : Set.of("reload_success", "reload_in_progress", "reload_failure")) {
            assertThat(messages.getString(reloadKey))
                    .as("%s describes the complete configuration snapshot", reloadKey)
                    .contains("config.yml", ".env");
        }
        assertThat(messages.getString("approve_intent"))
                .contains("操作内容")
                .doesNotContain("意图");
        assertThat(messages.getString("approve_gesture"))
                .contains("主手", "不会对空气产生效果")
                .doesNotContain("空手");
        for (String resource : Set.of("/config.yml", "/message.yml", "/AGENTS.md", "/tools.yml",
                "/skills/guide.md", "/skills/command-safety.md",
                "/skills/locate-structure.md")) {
            assertThat(text(resource)).doesNotMatch("(?s).*sk-[A-Za-z0-9_-]{16,}.*");
        }

        String genericDefaults = text("/config.yml") + text("/AGENTS.md") + text("/tools.yml")
                + text("/skills/guide.md") + text("/skills/command-safety.md")
                + text("/skills/locate-structure.md");
        assertThat(genericDefaults)
                .contains("本文件不提供任何具体服务器命令")
                .contains("AI 展示名不是玩家 ID")
                .contains("命令目标不等于命令执行者")
                .doesNotContain("KitesPlaces", "kp warp", "player-tpa", "^home", "^say");
        assertThat(PluginResourcesTest.class.getResource("/skills/kp-warps.md")).isNull();
    }

    private static YamlConfiguration yaml(String resource) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text(resource));
        return yaml;
    }

    private static String text(String resource) throws IOException {
        try (InputStream stream = PluginResourcesTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
