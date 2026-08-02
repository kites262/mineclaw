package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.workspace.ToolDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDispatcherTest {
    @TempDir
    Path workspace;

    @Test
    void preservesProtectedResultWhenReadIsDispatchedThroughCatalogDefinition() throws IOException {
        Files.writeString(workspace.resolve(".env"), "MINECLAW_API_KEY=secret");
        ToolDispatcher dispatcher = dispatcher(new AtomicInteger());

        ToolExecution execution = dispatcher.execute(readDefinition(), "{\"path\":\".env\"}", turnPlayer(),
                MineclawConfig.defaults()).join();

        assertThat(execution.pending()).isFalse();
        assertThat(execution.immediate().status()).isEqualTo("denied");
        assertThat(execution.immediate().output())
                .extracting(
                        output -> output.get("status").getAsString(),
                        output -> output.get("content").getAsString())
                .containsExactly("protected", WorkspaceFileTools.PROTECTED_CONTENT);
    }

    @Test
    void returnsStructuredInvalidResultWithoutInvokingHandlerOnTypeMismatch() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ToolDispatcher dispatcher = dispatcher(calls);

        ToolExecution execution = dispatcher.execute(definition(), "{\"command\":42}", turnPlayer(),
                MineclawConfig.defaults()).join();

        assertThat(calls).hasValue(0);
        assertThat(execution.pending()).isFalse();
        assertThat(execution.immediate().status()).isEqualTo("invalid");
        assertThat(execution.immediate().output())
                .extracting(
                        output -> output.get("error_code").getAsString(),
                        output -> output.get("path").getAsString(),
                        output -> output.get("message").getAsString())
                .containsExactly("invalid_arguments", "$.command", "expected string but found integer");

        dispatcher.execute(definition(), "{\"command\":\"say hello\"}", turnPlayer(),
                MineclawConfig.defaults()).join();
        assertThat(calls).hasValue(1);
    }

    private ToolDispatcher dispatcher(AtomicInteger calls) throws IOException {
        Server server = proxy(Server.class, null);
        Plugin plugin = proxy(Plugin.class, server);
        EnvironmentTools environment = new EnvironmentTools(server, new FoliaTasks(plugin));
        return new ToolDispatcher(new WorkspaceFileTools(workspace), environment,
                (arguments, player, config) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ToolExecution.completed(ToolResult.simple("ok", "called")));
                }, Runnable::run);
    }

    private static ToolDefinition definition() {
        JsonObject schema = JsonParser.parseString("""
                {
                  "type":"object",
                  "properties":{"command":{"type":"string"}},
                  "required":["command"],
                  "additionalProperties":false
                }
                """).getAsJsonObject();
        return new ToolDefinition(1, "run_command", "run_command",
                Optional.of(ToolDefinition.Handler.RUN_COMMAND), "test", schema, true,
                ToolDefinition.Status.ENABLED, Optional.empty());
    }

    private static ToolDefinition readDefinition() {
        JsonObject schema = JsonParser.parseString("""
                {
                  "type":"object",
                  "properties":{"path":{"type":"string"}},
                  "required":["path"],
                  "additionalProperties":false
                }
                """).getAsJsonObject();
        return new ToolDefinition(1, "read", "read", Optional.of(ToolDefinition.Handler.READ),
                "test", schema, true, ToolDefinition.Status.ENABLED, Optional.empty());
    }

    private static ToolDispatcher.TurnPlayer turnPlayer() {
        return new ToolDispatcher.TurnPlayer(UUID.randomUUID(), "Tester", proxy(Player.class, null));
    }

    private static <T> T proxy(Class<T> type, Server server) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> method.getName().equals("getServer") && server != null
                        ? server : defaultValue(method.getReturnType()));
        return type.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
