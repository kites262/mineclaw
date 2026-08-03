package cc.kites.mineclaw.listener;

import cc.kites.mineclaw.interaction.InteractionManager;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionLifecycleListenerTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "10000000-0000-4000-8000-000000000001";

    @Test
    void quitCompletesTheExactPlayersInteractionAsOffline() {
        InteractionManager manager = new InteractionManager((delay, action) -> () -> { });
        InteractionManager.Registration registration = manager.request(new InteractionManager.Request(
                PLAYER_ID, "Alice", TOKEN, "scope", "interaction",
                new InteractionManager.Confirm("Confirm", "Proceed?"), Duration.ofSeconds(60),
                InteractionManager.CURRENT_GENERATION));
        Player player = proxyPlayer(PLAYER_ID);

        new InteractionLifecycleListener(manager).onQuit(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertThat(registration.result().join().status())
                .isEqualTo(InteractionManager.Status.PLAYER_OFFLINE);
        assertThat(manager.pendingCount()).isZero();
    }

    private static Player proxyPlayer(UUID id) {
        return Player.class.cast(Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (ignored, method, arguments) ->
                        method.getName().equals("getUniqueId") ? id : defaultValue(method.getReturnType())));
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
