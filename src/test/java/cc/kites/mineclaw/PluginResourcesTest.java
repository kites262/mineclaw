package cc.kites.mineclaw;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PluginResourcesTest {
    @Test
    void paperDescriptorMatchesRuntimeAndDeclaresEveryPermission() throws Exception {
        YamlConfiguration descriptor = yaml("/paper-plugin.yml");
        assertThat(descriptor.getString("name")).isEqualTo("Mineclaw");
        assertThat(descriptor.getString("version")).isEqualTo("1.4.0");
        assertThat(descriptor.getString("main")).isEqualTo("cc.kites.mineclaw.MineclawPlugin");
        assertThat(descriptor.getString("api-version")).isEqualTo("26.2");
        assertThat(descriptor.getBoolean("folia-supported")).isTrue();
        assertThat(descriptor.getConfigurationSection("permissions")).isNotNull();
        for (String permission : Set.of(
                "mineclaw.command.chat", "mineclaw.command.clear", "mineclaw.command.compact",
                "mineclaw.command.approve",
                "mineclaw.command.reload", "mineclaw.command.tools", "mineclaw.command.functions",
                "mineclaw.command.model",
                "mineclaw.bypass.ratelimit")) {
            assertThat(descriptor.contains("permissions." + permission + ".default"))
                    .as("descriptor contains %s", permission).isTrue();
        }
    }

    @Test
    void bundledMessagesCoverAllRuntimeKeysAndResourcesContainNoSecret() throws Exception {
        YamlConfiguration messages = yaml("/message.yml");
        assertThat(messages.getKeys(false)).containsAll(Set.of(
                "no_permission", "permission_unavailable", "player_only", "busy", "rate_limited",
                "empty_question", "actionbar_thinking",
                "api_failure", "context_capacity", "tool_loop_limit", "reply_prefix", "clear_success",
                "compact_started", "compact_queued", "compact_already_pending", "compact_success",
                "compact_no_history", "compact_failure", "compact_cancelled", "reload_success",
                "reload_in_progress", "reload_failure", "control_plane_unavailable",
                "approve_prompt", "approve_layout",
                "approve_prefix", "approve_separator", "approve_buttons", "approve_title",
                "approve_requester", "approve_command", "approve_intent", "approve_player",
                "approve_expires", "approve_accept_button", "approve_reject_button",
                "approve_accept_hover", "approve_reject_hover", "approve_none", "approve_started",
                "approve_rejected", "approve_timeout", "approve_unavailable",
                "interaction_confirm_layout", "interaction_select_layout", "interaction_prefix",
                "interaction_separator", "interaction_title", "interaction_message",
                "interaction_confirm_buttons", "interaction_accept_button", "interaction_reject_button",
                "interaction_accept_hover", "interaction_reject_hover", "interaction_select_option",
                "interaction_select_option_hover", "interaction_select_option_separator",
                "tools_header", "tools_entry", "tools_validate_success", "tools_failure",
                "functions_header", "functions_entry", "functions_validate_success", "functions_failure",
                "model_current", "model_list_header", "model_list_entry", "model_selected",
                "model_default", "model_unknown",
                "usage"));
        for (String reloadKey : Set.of("reload_success", "reload_in_progress")) {
            assertThat(messages.getString(reloadKey))
                    .as("%s describes the complete configuration snapshot", reloadKey)
                    .contains("config.yml", "providers.yml", "whitelist.yml", ".env");
        }
        assertThat(messages.getString("reload_failure")).contains("旧快照保持生效");
        assertThat(messages.getString("approve_intent"))
                .contains("操作内容")
                .doesNotContain("意图");
        assertThat(messages.getString("approve_layout"))
                .contains("<separator>", "<prefix><title>", "<prefix><buttons>");
        assertThat(messages.getString("approve_buttons")).contains("<accept>", "│", "<reject>");
        assertThat(messages.getString("interaction_confirm_layout"))
                .contains("<title>", "<message>", "<buttons>");
        assertThat(messages.getString("interaction_select_layout"))
                .contains("<title>", "<message>", "<options>", "<reject>");
        assertThat(messages.getString("actionbar_thinking")).contains("Thinking...");
        assertThat(messages.getString("tools_entry"))
                .contains("<handler>", "<status>", "<payload>", "<diagnostic>")
                .doesNotContain("<id>", "<type>", "<provider>", "metadata=");
        for (String resource : Set.of("/config.yml", "/providers.yml", "/whitelist.yml",
                "/message.yml", "/workspace/AGENTS.md", "/tools.yml", "/functions.yml",
                "/workspace/skills/locate-structure.md",
                "/workspace/skills/self-potion-effect.md")) {
            assertThat(text(resource)).doesNotMatch("(?s).*sk-[A-Za-z0-9_-]{16,}.*");
        }

        String genericDefaults = text("/config.yml") + text("/providers.yml") + text("/whitelist.yml")
                + text("/workspace/AGENTS.md") + text("/tools.yml")
                + text("/functions.yml")
                + text("/workspace/skills/locate-structure.md")
                + text("/workspace/skills/self-potion-effect.md");
        assertThat(genericDefaults)
                .contains("当前 Workspace 未提供答案或能力说明")
                .contains("AI 展示名「Mineclaw」")
                .contains("执行身份和命令目标始终分开")
                .doesNotContain("KitesPlaces", "kp warp", "player-tpa", "^home", "^say");
        assertThat(PluginResourcesTest.class.getResource("/AGENTS.md")).isNull();
        assertThat(PluginResourcesTest.class.getResource("/skills/locate-structure.md")).isNull();
        assertThat(PluginResourcesTest.class.getResource("/workspace/skills/guide.md")).isNull();
        assertThat(PluginResourcesTest.class.getResource("/workspace/skills/command-safety.md")).isNull();
        assertThat(PluginResourcesTest.class.getResource("/workspace/skills/kp-warps.md")).isNull();

        YamlConfiguration providers = yaml("/providers.yml");
        assertThat(providers.getInt("models.mimo/mimo-v2.5.limits.context_window_tokens"))
                .isEqualTo(131_072);
        assertThat(providers.getInt("models.mimo/mimo-v2.5.limits.max_output_tokens"))
                .isEqualTo(16_384);
        assertThat(providers.getInt("models.mimo/mimo-v2.5.limits.compact_trigger_tokens"))
                .isEqualTo(102_400);
        assertThat(providers.getBoolean("models.mimo/mimo-v2.5.request.prompt_cache_key"))
                .isTrue();

        YamlConfiguration tools = yaml("/tools.yml");
        assertThat(tools.getInt("schema")).isEqualTo(2);
        assertThat(tools.getMapList("tools")).hasSize(9).allSatisfy(tool -> {
            assertThat(tool.keySet()).isEqualTo(Set.of("handler", "enabled", "payload"));
        });
        java.util.List<String> handlers = tools.getMapList("tools").stream()
                .map(tool -> String.valueOf(tool.get("handler")))
                .toList();
        assertThat(handlers)
                .containsExactly("player_snapshot", "item_inspect", "block_inspect", "online_players",
                        "call_function", "list", "read", "grep", "run_command")
                .doesNotContain("look_block", "feet_block", "inventory");
        Map<?, ?> runCommand = tools.getMapList("tools").stream()
                .filter(tool -> tool.get("handler").equals("run_command"))
                .findFirst()
                .orElseThrow();
        Map<?, ?> payload = (Map<?, ?>) runCommand.get("payload");
        Map<?, ?> toolFunction = (Map<?, ?>) payload.get("function");
        Map<?, ?> parameters = (Map<?, ?>) toolFunction.get("parameters");
        Map<?, ?> properties = (Map<?, ?>) parameters.get("properties");
        Map<?, ?> player = (Map<?, ?>) properties.get("player");
        assertThat(player.get("type")).isEqualTo("string");

        YamlConfiguration functions = yaml("/functions.yml");
        assertThat(functions.getInt("schema")).isEqualTo(1);
        assertThat(functions.getInt("api_version")).isEqualTo(1);
        assertThat(functions.getMapList("functions")).singleElement().satisfies(function -> {
            assertThat(function.get("name")).isEqualTo("player.effect.give");
            assertThat(function.get("enabled")).isEqualTo(true);
            assertThat(function.get("capabilities")).isEqualTo(java.util.List.of(
                    "approval.request", "command.dispatch.console"));
        });
        assertThat(text("/workspace/skills/self-potion-effect.md"))
                .contains("functions:", "player.effect.give", "call_function", "invalid_arguments");

        assertThat(text("/META-INF/LICENSE-GRAALJS.txt"))
                .contains("Copyright (c) 2010, 2022, Oracle and/or its affiliates.")
                .contains("WRITTEN OFFER FOR SOURCE CODE")
                .doesNotContain("[year]", "<year>", "<copyright holders>");
        assertThat(text("/META-INF/THIRD-PARTY-LICENSE-GRAALJS.txt"))
                .contains("DToA", "FastDtoaBuilder", "ICU4J", "UNICODE", "ASM")
                .doesNotContain("[year]", "<year>", "<copyright holders>");
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
