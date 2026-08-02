package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Objects;

/** Keeps ActionBar, direct-player, and public-chat channels explicitly separate. */
public final class PlayerChannel {
    private final Server server;
    private final FoliaTasks tasks;

    public PlayerChannel(Server server, FoliaTasks tasks) {
        this.server = Objects.requireNonNull(server, "server");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public void send(Player player, Component message) {
        tasks.entity(player, () -> player.sendMessage(message), () -> { });
    }

    public void actionBar(Player player, Component message) {
        tasks.entity(player, () -> player.sendActionBar(message), () -> { });
    }

    public void clearActionBar(Player player) {
        tasks.entity(player, () -> player.sendActionBar(Component.empty()), () -> { });
    }

    public void broadcast(Component message) {
        tasks.global(() -> server.broadcast(message));
    }

    public static String truncate(String text, int maximumCodePoints) {
        int count = text.codePointCount(0, text.length());
        if (count <= maximumCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maximumCodePoints);
        return text.substring(0, end);
    }

}
