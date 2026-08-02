package cc.kites.mineclaw.listener;

import cc.kites.mineclaw.config.ConfigStore;
import cc.kites.mineclaw.config.MineclawConfig;
import cc.kites.mineclaw.support.MessageService;
import cc.kites.mineclaw.support.PlayerChannel;
import cc.kites.mineclaw.support.FoliaTasks;
import cc.kites.mineclaw.turn.TurnCoordinator;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Detects configurable public wake messages while preserving accepted questions in normal chat. */
public final class PublicChatListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ConfigStore config;
    private final TurnCoordinator turns;
    private final MessageService messages;
    private final PlayerChannel channel;
    private final FoliaTasks tasks;
    private final Executor ioExecutor;

    public PublicChatListener(ConfigStore config, TurnCoordinator turns,
                              MessageService messages, PlayerChannel channel, FoliaTasks tasks,
                              Executor ioExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        MineclawConfig.Chat chat = config.get().chat();
        Optional<String> parsed = parseWake(PLAIN.serialize(event.message()), chat);
        if (parsed.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        PermissionSnapshot permission = permissions(player);
        if (permission == null) {
            event.setCancelled(true);
            renderAndSend(player, "permission_unavailable", Map.of());
            return;
        }
        if (!permission.chat()) {
            event.setCancelled(true);
            renderAndSend(player, "no_permission", Map.of());
            return;
        }
        String question = parsed.orElseThrow().trim();
        if (question.isEmpty()) {
            event.setCancelled(true);
            renderAndSend(player, "empty_question", Map.of("prefix", chat.publicPrefix()));
            return;
        }
        TurnCoordinator.StartResult result = turns.start(player, permission.playerId(), permission.playerName(),
                question, permission.bypassRateLimit());
        switch (result.status()) {
            case ACCEPTED -> {
                // Keep the original AsyncChatEvent uncancelled: the question remains public.
            }
            case BUSY -> {
                event.setCancelled(true);
                renderAndSend(player, "busy", Map.of());
            }
            case RATE_LIMITED -> {
                event.setCancelled(true);
                renderAndSend(player, "rate_limited",
                        Map.of("remaining_ms", Long.toString(result.remainingMillis())));
            }
        }
    }

    private PermissionSnapshot permissions(Player player) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            return snapshot(player);
        }
        try {
            return tasks.entity(player, () -> snapshot(player)).get(2L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException exception) {
            return null;
        }
    }

    private static PermissionSnapshot snapshot(Player player) {
        return new PermissionSnapshot(player.getUniqueId(), player.getName(),
                player.hasPermission("mineclaw.command.chat"),
                player.hasPermission("mineclaw.bypass.ratelimit"));
    }

    private void renderAndSend(Player player, String key, Map<String, String> values) {
        CompletableFuture.supplyAsync(() -> messages.render(key, values), ioExecutor)
                .thenAccept(message -> channel.send(player, message));
    }

    static Optional<String> parseWake(String text, MineclawConfig.Chat chat) {
        if (chat.wakePattern().isPresent()) {
            Matcher matcher = chat.wakePattern().orElseThrow().matcher(text);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            return Optional.of(matcher.groupCount() >= 1 ? nullToEmpty(matcher.group(1)) : matcher.group());
        }
        String prefix = chat.publicPrefix();
        if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        if (text.length() > prefix.length()
                && !Character.isWhitespace(text.codePointAt(prefix.length()))) {
            return Optional.empty();
        }
        return Optional.of(text.substring(prefix.length()).stripLeading());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PermissionSnapshot(UUID playerId, String playerName,
                                      boolean chat, boolean bypassRateLimit) { }
}
