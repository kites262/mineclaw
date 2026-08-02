package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.support.FoliaTasks;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Folia-safe read-only tools bound to the player who opened the current turn. */
public final class EnvironmentTools {
    private final Server server;
    private final FoliaTasks tasks;
    private final Map<Key, Long> lastCalls = new ConcurrentHashMap<>();

    public EnvironmentTools(Server server, FoliaTasks tasks) {
        this.server = Objects.requireNonNull(server, "server");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public CompletableFuture<ToolResult> execute(
            String handler, UUID turnPlayer, String turnPlayerName, Settings settings) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(turnPlayer, "turnPlayer");
        Objects.requireNonNull(turnPlayerName, "turnPlayerName");
        Objects.requireNonNull(settings, "settings");
        long remaining = acquire(turnPlayer, handler, settings.cooldownMillis());
        if (remaining > 0L) {
            JsonObject output = new JsonObject();
            output.addProperty("status", "recoverable_error");
            output.addProperty("error_code", "tool_rate_limited");
            output.addProperty("retry_after_ms", remaining);
            return CompletableFuture.completedFuture(new ToolResult("recoverable_error", output));
        }
        if (handler.equals("online_players")) {
            return onlinePlayers(turnPlayerName);
        }
        return tasks.global(() -> server.getPlayer(turnPlayer)).thenCompose(player -> {
            if (player == null) {
                return CompletableFuture.completedFuture(ToolResult.simple("denied", "当前对话玩家已离线"));
            }
            return tasks.entity(player, () -> {
                if (!player.isOnline()) {
                    return ToolResult.simple("denied", "当前对话玩家已离线");
                }
                return switch (handler) {
                    case "look_block" -> lookBlock(player, settings.lookDistance());
                    case "feet_block" -> feetBlock(player);
                    case "inventory" -> inventory(player, settings);
                    default -> ToolResult.simple("invalid", "未知的环境工具处理器：" + handler);
                };
            });
        });
    }

    private CompletableFuture<ToolResult> onlinePlayers(String turnPlayerName) {
        return tasks.global(() -> List.copyOf(server.getOnlinePlayers())).thenCompose(players -> {
            List<CompletableFuture<String>> snapshots = players.stream()
                    .map(player -> tasks.entity(player,
                                    () -> player.isOnline() ? player.getName() : null)
                            .exceptionally(failure -> null))
                    .toList();
            CompletableFuture<?>[] pending = snapshots.toArray(CompletableFuture<?>[]::new);
            return CompletableFuture.allOf(pending).thenApply(ignored -> {
                List<String> names = snapshots.stream()
                        .map(CompletableFuture::join)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
                        .toList();
                JsonObject output = okBase();
                output.addProperty("turn_player", turnPlayerName);
                JsonArray online = new JsonArray();
                names.forEach(online::add);
                output.add("online_players", online);
                return new ToolResult("ok", output);
            });
        });
    }

    public void clear() {
        lastCalls.clear();
    }

    private long acquire(UUID player, String handler, long cooldownMillis) {
        long now = System.currentTimeMillis();
        Key key = new Key(player, handler);
        final long[] remaining = {0L};
        lastCalls.compute(key, (ignored, previous) -> {
            if (previous != null && now - previous < cooldownMillis) {
                remaining[0] = cooldownMillis - Math.max(0L, now - previous);
                return previous;
            }
            return now;
        });
        return remaining[0];
    }

    private static ToolResult lookBlock(Player player, int distance) {
        Block block = player.getTargetBlockExact(distance, FluidCollisionMode.NEVER);
        JsonObject output = okBase();
        output.addProperty("player", player.getName());
        if (block == null || block.getType().isAir()) {
            output.add("block", JsonNull.INSTANCE);
            output.addProperty("message", "准星检测范围内没有非空气方块");
        } else {
            output.add("block", block(block));
        }
        return new ToolResult("ok", output);
    }

    private static ToolResult feetBlock(Player player) {
        Block feet = player.getLocation().subtract(0.0, 0.01, 0.0).getBlock();
        JsonObject output = okBase();
        output.addProperty("player", player.getName());
        output.add("block", block(feet));
        return new ToolResult("ok", output);
    }

    private static ToolResult inventory(Player player, Settings settings) {
        PlayerInventory inventory = player.getInventory();
        JsonObject output = okBase();
        output.addProperty("player", player.getName());
        JsonArray items = new JsonArray();
        ItemStack[] storage = inventory.getStorageContents();
        int slots = Math.min(settings.inventoryMaxSlots(), storage.length);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack item = storage[slot];
            if (item != null && !item.getType().isAir()) {
                JsonObject entry = item(item);
                entry.addProperty("slot", slot);
                items.add(entry);
            }
        }
        output.add("items", items);
        output.addProperty("scanned_slots", slots);
        if (settings.includeEquipment()) {
            JsonArray equipment = new JsonArray();
            String[] names = {"boots", "leggings", "chestplate", "helmet"};
            ItemStack[] armor = inventory.getArmorContents();
            for (int index = 0; index < armor.length; index++) {
                ItemStack item = armor[index];
                if (item != null && !item.getType().isAir()) {
                    JsonObject entry = item(item);
                    entry.addProperty("slot", names[index]);
                    equipment.add(entry);
                }
            }
            ItemStack offhand = inventory.getItemInOffHand();
            if (!offhand.getType().isAir()) {
                JsonObject entry = item(offhand);
                entry.addProperty("slot", "offhand");
                equipment.add(entry);
            }
            output.add("equipment", equipment);
        }
        return new ToolResult("ok", output);
    }

    private static JsonObject okBase() {
        JsonObject output = new JsonObject();
        output.addProperty("status", "ok");
        return output;
    }

    private static JsonObject block(Block block) {
        JsonObject result = new JsonObject();
        result.addProperty("type", block.getType().getKey().asString());
        result.addProperty("world", block.getWorld().getName());
        result.addProperty("x", block.getX());
        result.addProperty("y", block.getY());
        result.addProperty("z", block.getZ());
        return result;
    }

    private static JsonObject item(ItemStack item) {
        JsonObject result = new JsonObject();
        result.addProperty("type", item.getType().getKey().asString());
        result.addProperty("amount", item.getAmount());
        return result;
    }

    public record Settings(int lookDistance, long cooldownMillis, boolean includeEquipment,
                           int inventoryMaxSlots) {
        public Settings {
            if (lookDistance < 1 || cooldownMillis < 0L || inventoryMaxSlots < 1) {
                throw new IllegalArgumentException("invalid environment tool settings");
            }
        }
    }

    private record Key(UUID player, String handler) { }
}
