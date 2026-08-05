package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.support.FoliaTasks;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Folia-safe read-only tools bound to the player who opened the current turn. */
public final class EnvironmentTools {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final int MAX_CUSTOM_NAME_CHARS = 256;
    private static final int MAX_EFFECTS = 64;
    private static final Set<String> FULLY_REPORTED_DATA_COMPONENTS = Set.of(
            "minecraft:max_stack_size",
            "minecraft:max_damage",
            "minecraft:damage",
            "minecraft:enchantments"
    );

    private final Server server;
    private final FoliaTasks tasks;
    private final Map<Key, Long> lastCalls = new ConcurrentHashMap<>();

    public EnvironmentTools(Server server, FoliaTasks tasks) {
        this.server = Objects.requireNonNull(server, "server");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public CompletableFuture<ToolResult> execute(
            String handler,
            JsonObject arguments,
            UUID turnPlayer,
            String turnPlayerName,
            Settings settings
    ) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(arguments, "arguments");
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
                return CompletableFuture.completedFuture(offline());
            }
            return tasks.entity(player, () -> {
                if (!player.isOnline()) {
                    return offline();
                }
                return switch (handler) {
                    case "player_snapshot" -> playerSnapshot(player);
                    case "item_inspect" -> itemInspect(player, arguments, settings);
                    case "block_inspect" -> blockInspect(player, arguments, settings);
                    default -> invalid("unknown_environment_handler", "未知的环境工具处理器：" + handler);
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

    @SuppressWarnings("deprecation")
    private static ToolResult playerSnapshot(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        Block feet = location.getBlock();

        JsonObject output = okBase();
        output.addProperty("player", player.getName());

        JsonObject position = new JsonObject();
        position.addProperty("world", world.getName());
        position.addProperty("world_key", world.getKey().asString());
        position.addProperty("dimension", world.getEnvironment().name().toLowerCase(java.util.Locale.ROOT));
        position.addProperty("x", location.getX());
        position.addProperty("y", location.getY());
        position.addProperty("z", location.getZ());
        position.addProperty("block_x", location.getBlockX());
        position.addProperty("block_y", location.getBlockY());
        position.addProperty("block_z", location.getBlockZ());
        position.addProperty("yaw_degrees", location.getYaw());
        position.addProperty("pitch_degrees", location.getPitch());
        output.add("position", position);

        JsonObject survival = new JsonObject();
        survival.addProperty("game_mode", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
        survival.addProperty("health", player.getHealth());
        survival.addProperty("max_health", player.getMaxHealth());
        survival.addProperty("food_level", player.getFoodLevel());
        survival.addProperty("saturation", player.getSaturation());
        survival.addProperty("experience_level", player.getLevel());
        survival.addProperty("experience_progress", player.getExp());
        survival.addProperty("remaining_air_ticks", player.getRemainingAir());
        survival.addProperty("maximum_air_ticks", player.getMaximumAir());
        survival.addProperty("remaining_fire_ticks", Math.max(0, player.getFireTicks()));
        survival.addProperty("freeze_ticks", player.getFreezeTicks());
        survival.addProperty("maximum_freeze_ticks", player.getMaxFreezeTicks());
        survival.addProperty("frozen", player.isFrozen());
        output.add("survival", survival);

        JsonObject movement = new JsonObject();
        movement.addProperty("on_ground", ((Entity) player).isOnGround());
        movement.addProperty("sneaking", player.isSneaking());
        movement.addProperty("sprinting", player.isSprinting());
        movement.addProperty("swimming", player.isSwimming());
        movement.addProperty("gliding", player.isGliding());
        movement.addProperty("flying", player.isFlying());
        movement.addProperty("inside_vehicle", player.isInsideVehicle());
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            movement.add("vehicle_type", JsonNull.INSTANCE);
        } else {
            movement.addProperty("vehicle_type", vehicle.getType().getKey().asString());
        }
        output.add("movement", movement);

        JsonObject environment = new JsonObject();
        environment.addProperty("biome", biomeKey(feet.getBiome()));
        environment.addProperty("light", feet.getLightLevel());
        environment.addProperty("sky_light", feet.getLightFromSky());
        environment.addProperty("block_light", feet.getLightFromBlocks());
        environment.addProperty("world_time_ticks", world.getTime());
        environment.addProperty("world_full_time_ticks", world.getFullTime());
        environment.addProperty("storm", world.hasStorm());
        environment.addProperty("thundering", world.isThundering());
        output.add("environment", environment);

        ArrayList<PotionEffect> effects = new ArrayList<>(player.getActivePotionEffects());
        effects.sort(Comparator.comparing(effect -> effect.getType().getKey().asString()));
        JsonArray activeEffects = new JsonArray();
        for (int index = 0; index < Math.min(effects.size(), MAX_EFFECTS); index++) {
            activeEffects.add(potionEffect(effects.get(index)));
        }
        output.add("active_effects", activeEffects);
        output.addProperty("effects_truncated", effects.size() > MAX_EFFECTS);
        return new ToolResult("ok", output);
    }

    private static ToolResult itemInspect(Player player, JsonObject arguments, Settings settings) {
        String mode = text(arguments, "mode", "inventory");
        boolean hasSlot = arguments.has("slot");
        if (mode.equals("slot") != hasSlot) {
            return invalid("invalid_slot_selector",
                    mode.equals("slot") ? "slot 模式必须提供 slot" : "只有 slot 模式可以提供 slot");
        }

        PlayerInventory inventory = player.getInventory();
        if (mode.equals("inventory")) {
            return inventorySummary(player, inventory, settings);
        }

        ItemSelection selection = switch (mode) {
            case "slot" -> storageSelection(inventory, arguments.get("slot").getAsInt());
            case "main_hand" -> new ItemSelection("main_hand", null, inventory.getItemInMainHand());
            case "off_hand" -> new ItemSelection("off_hand", null, inventory.getItemInOffHand());
            case "helmet" -> new ItemSelection("helmet", null, inventory.getHelmet());
            case "chestplate" -> new ItemSelection("chestplate", null, inventory.getChestplate());
            case "leggings" -> new ItemSelection("leggings", null, inventory.getLeggings());
            case "boots" -> new ItemSelection("boots", null, inventory.getBoots());
            default -> null;
        };
        if (selection == null) {
            if (mode.equals("slot")) {
                return invalid("invalid_slot", "slot 超出当前玩家背包范围");
            }
            return invalid("invalid_item_mode", "未知的 item_inspect 模式：" + mode);
        }

        JsonObject output = okBase();
        output.addProperty("player", player.getName());
        output.addProperty("mode", mode);
        if (empty(selection.item())) {
            output.add("item", JsonNull.INSTANCE);
        } else {
            output.add("item", item(selection, true));
        }
        return new ToolResult("ok", output);
    }

    private static ToolResult inventorySummary(Player player, PlayerInventory inventory, Settings settings) {
        JsonObject output = okBase();
        output.addProperty("player", player.getName());
        output.addProperty("mode", "inventory");
        JsonArray items = new JsonArray();
        output.add("items", items);

        ItemStack[] storage = inventory.getStorageContents();
        int slots = Math.min(settings.itemMaxSlots(), storage.length);
        ArrayList<ItemSelection> selections = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            selections.add(new ItemSelection("storage", slot, storage[slot]));
        }
        selections.add(new ItemSelection("helmet", null, inventory.getHelmet()));
        selections.add(new ItemSelection("chestplate", null, inventory.getChestplate()));
        selections.add(new ItemSelection("leggings", null, inventory.getLeggings()));
        selections.add(new ItemSelection("boots", null, inventory.getBoots()));
        selections.add(new ItemSelection("main_hand", null, inventory.getItemInMainHand()));
        selections.add(new ItemSelection("off_hand", null, inventory.getItemInOffHand()));

        boolean truncated = false;
        for (ItemSelection selection : selections) {
            if (empty(selection.item())) {
                continue;
            }
            items.add(item(selection, false));
            output.addProperty("scanned_storage_slots", slots);
            output.addProperty("truncated", false);
            if (output.toString().length() > settings.itemMaxOutputChars()) {
                items.remove(items.size() - 1);
                truncated = true;
                break;
            }
        }
        output.addProperty("scanned_storage_slots", slots);
        output.addProperty("truncated", truncated);
        return new ToolResult("ok", output);
    }

    private static ItemSelection storageSelection(PlayerInventory inventory, int slot) {
        ItemStack[] storage = inventory.getStorageContents();
        if (slot < 0 || slot >= storage.length) {
            return null;
        }
        return new ItemSelection("storage", slot, storage[slot]);
    }

    private static JsonObject item(ItemSelection selection, boolean detailed) {
        ItemStack item = selection.item();
        Material material = item.getType();
        JsonObject result = new JsonObject();
        result.addProperty("position", selection.position());
        if (selection.slot() != null) {
            result.addProperty("slot", selection.slot());
        }
        result.addProperty("type", material.getKey().asString());
        result.addProperty("amount", item.getAmount());
        result.addProperty("max_stack_size", item.getMaxStackSize());

        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        boolean hasCustomName = meta != null && meta.hasDisplayName();
        result.addProperty("has_custom_name", hasCustomName);
        if (hasCustomName) {
            result.addProperty("custom_name", truncate(plain(meta.displayName()), MAX_CUSTOM_NAME_CHARS));
        }
        if (meta instanceof Damageable damageable) {
            int maxDamage = maxDamage(material, damageable);
            if (maxDamage > 0) {
                result.addProperty("damage", damageable.getDamage());
                result.addProperty("max_damage", maxDamage);
            }
        }
        result.add("enchantments", enchantments(item.getEnchantments()));

        if (detailed) {
            JsonObject properties = new JsonObject();
            properties.addProperty("edible", safeMaterialBoolean(material::isEdible));
            properties.addProperty("block_item", safeMaterialBoolean(material::isBlock));
            properties.addProperty("fuel", safeMaterialBoolean(material::isFuel));
            properties.addProperty("max_stack_size", item.getMaxStackSize());
            int defaultMaxDurability = safeMaterialInt(material::getMaxDurability);
            if (defaultMaxDurability > 0) {
                properties.addProperty("max_durability", defaultMaxDurability);
            }
            EquipmentSlot equipmentSlot = equipmentSlot(material, meta);
            if (equipmentSlot != null) {
                properties.addProperty("equipment_slot", equipmentSlot.name().toLowerCase(java.util.Locale.ROOT));
            }
            result.add("properties", properties);

            if (meta instanceof PotionMeta potion) {
                JsonObject potionData = new JsonObject();
                if (potion.getBasePotionType() == null) {
                    potionData.add("base_type", JsonNull.INSTANCE);
                } else {
                    potionData.addProperty("base_type", potion.getBasePotionType().getKey().asString());
                }
                ArrayList<PotionEffect> effects = new ArrayList<>(potion.getCustomEffects());
                effects.sort(Comparator.comparing(effect -> effect.getType().getKey().asString()));
                JsonArray custom = new JsonArray();
                for (int index = 0; index < Math.min(effects.size(), MAX_EFFECTS); index++) {
                    custom.add(potionEffect(effects.get(index)));
                }
                potionData.add("custom_effects", custom);
                potionData.addProperty("effects_truncated", effects.size() > MAX_EFFECTS);
                result.add("potion", potionData);
            }
            if (meta instanceof EnchantmentStorageMeta stored) {
                result.add("stored_enchantments", enchantments(stored.getStoredEnchants()));
            }
            if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.hasBlockState()) {
                result.addProperty("block_state_type", blockStateMeta.getBlockState().getType().getKey().asString());
            }
            result.addProperty("has_unexpanded_data", hasUnexpandedData(item, meta));
        }
        return result;
    }

    private static int maxDamage(Material material, Damageable damageable) {
        if (damageable.hasMaxDamage()) {
            return damageable.getMaxDamage();
        }
        try {
            return material.getMaxDurability();
        } catch (RuntimeException | LinkageError ignored) {
            // Material metadata needs a live registry, which isolated unit tests do not provide.
            return 0;
        }
    }

    private static boolean safeMaterialBoolean(java.util.function.BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static int safeMaterialInt(java.util.function.IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static JsonArray enchantments(Map<Enchantment, Integer> enchantments) {
        JsonArray result = new JsonArray();
        enchantments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(value -> value.getKey().asString())))
                .forEach(entry -> {
                    JsonObject enchantment = new JsonObject();
                    enchantment.addProperty("type", entry.getKey().getKey().asString());
                    enchantment.addProperty("level", entry.getValue());
                    result.add(enchantment);
                });
        return result;
    }

    private static boolean hasUnexpandedData(ItemStack item, ItemMeta meta) {
        if (meta != null && (!meta.getPersistentDataContainer().isEmpty()
                || meta.hasLore()
                || meta instanceof org.bukkit.inventory.meta.BookMeta
                || meta instanceof BlockStateMeta)) {
            return true;
        }
        try {
            Set<io.papermc.paper.datacomponent.DataComponentType> types = item.getDataTypes();
            if (types == null) {
                return meta != null;
            }
            return types.stream().anyMatch(type -> item.isDataOverridden(type)
                    && !FULLY_REPORTED_DATA_COMPONENTS.contains(type.getKey().asString()));
        } catch (RuntimeException | LinkageError ignored) {
            // The Data Component registry is unavailable only in isolated tests or a broken runtime.
            return meta != null;
        }
    }

    @SuppressWarnings("deprecation")
    private static EquipmentSlot equipmentSlot(Material material, ItemMeta meta) {
        try {
            if (meta != null && meta.hasEquippable()) {
                var equippable = meta.getEquippable();
                return equippable == null ? null : equippable.getSlot();
            }
            EquipmentSlot materialSlot = material.getEquipmentSlot();
            return materialSlot == EquipmentSlot.HAND ? null : materialSlot;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static ToolResult blockInspect(Player player, JsonObject arguments, Settings settings) {
        String mode = text(arguments, "mode", "look");
        boolean hasDistance = arguments.has("distance");
        if (mode.equals("feet") && hasDistance) {
            return invalid("invalid_block_selector", "feet 模式不得提供 distance");
        }

        Block block;
        if (mode.equals("look")) {
            int distance = hasDistance ? arguments.get("distance").getAsInt() : settings.lookDistance();
            if (distance > settings.lookDistance()) {
                return invalid("look_distance_exceeded",
                        "distance 超过服务端上限 " + settings.lookDistance());
            }
            block = player.getTargetBlockExact(distance, FluidCollisionMode.NEVER);
        } else if (mode.equals("feet")) {
            block = player.getLocation().subtract(0.0, 0.01, 0.0).getBlock();
        } else {
            return invalid("invalid_block_mode", "未知的 block_inspect 模式：" + mode);
        }

        JsonObject output = okBase();
        output.addProperty("player", player.getName());
        output.addProperty("mode", mode);
        if (block == null || mode.equals("look") && block.isEmpty()) {
            output.add("block", JsonNull.INSTANCE);
        } else {
            output.add("block", block(block));
        }
        return new ToolResult("ok", output);
    }

    private static JsonObject block(Block block) {
        Material material = block.getType();
        JsonObject result = new JsonObject();
        result.addProperty("type", material.getKey().asString());
        result.addProperty("world", block.getWorld().getName());
        result.addProperty("x", block.getX());
        result.addProperty("y", block.getY());
        result.addProperty("z", block.getZ());
        result.add("block_data", blockData(block.getBlockData()));
        result.addProperty("air", block.isEmpty());
        result.addProperty("liquid", block.isLiquid());
        result.addProperty("passable", block.isPassable());
        result.addProperty("solid", block.isSolid());
        result.addProperty("flammable", block.isBurnable());
        result.addProperty("interactable", interactable(material));
        result.addProperty("light", block.getLightLevel());
        result.addProperty("sky_light", block.getLightFromSky());
        result.addProperty("block_light", block.getLightFromBlocks());
        result.addProperty("biome", biomeKey(block.getBiome()));
        BlockState state = block.getState(false);
        if (state instanceof TileState) {
            JsonObject blockEntity = new JsonObject();
            blockEntity.addProperty("type", state.getType().getKey().asString());
            result.add("block_entity", blockEntity);
        } else {
            result.add("block_entity", JsonNull.INSTANCE);
        }
        return result;
    }

    private static JsonObject blockData(BlockData data) {
        JsonObject result = new JsonObject();
        String serialized = data.getAsString();
        int start = serialized.indexOf('[');
        if (start < 0 || !serialized.endsWith("]")) {
            return result;
        }
        String body = serialized.substring(start + 1, serialized.length() - 1);
        TreeMap<String, String> properties = new TreeMap<>();
        if (!body.isEmpty()) {
            for (String pair : body.split(",")) {
                int separator = pair.indexOf('=');
                if (separator > 0 && separator < pair.length() - 1) {
                    properties.put(pair.substring(0, separator), pair.substring(separator + 1));
                }
            }
        }
        properties.forEach(result::addProperty);
        return result;
    }

    @SuppressWarnings("deprecation")
    private static boolean interactable(Material material) {
        try {
            return material.asBlockType().isInteractable();
        } catch (RuntimeException | LinkageError ignored) {
            // The API may be used without a live server registry in isolated tests.
            return false;
        }
    }

    private static String biomeKey(Object biome) {
        if (biome instanceof org.bukkit.Keyed keyed) {
            try {
                return keyed.getKey().asString();
            } catch (RuntimeException | LinkageError ignored) {
                // A missing registry is only possible outside a running server.
            }
        }
        return "unknown";
    }

    private static JsonObject potionEffect(PotionEffect effect) {
        JsonObject result = new JsonObject();
        result.addProperty("type", effect.getType().getKey().asString());
        result.addProperty("level", effect.getAmplifier() + 1);
        if (effect.isInfinite()) {
            result.add("duration_ticks", JsonNull.INSTANCE);
        } else {
            result.addProperty("duration_ticks", effect.getDuration());
        }
        result.addProperty("infinite", effect.isInfinite());
        return result;
    }

    private static String plain(Component component) {
        return component == null ? "" : PLAIN_TEXT.serialize(component);
    }

    private static String truncate(String value, int maximumChars) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumChars) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumChars));
    }

    private static String text(JsonObject arguments, String field, String defaultValue) {
        return arguments.has(field) && arguments.get(field).isJsonPrimitive()
                ? arguments.get(field).getAsString() : defaultValue;
    }

    private static boolean empty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static JsonObject okBase() {
        JsonObject output = new JsonObject();
        output.addProperty("status", "ok");
        return output;
    }

    private static ToolResult offline() {
        return ToolResult.simple("denied", "当前对话玩家已离线");
    }

    private static ToolResult invalid(String code, String message) {
        JsonObject output = new JsonObject();
        output.addProperty("status", "invalid");
        output.addProperty("error_code", code);
        output.addProperty("message", message);
        return new ToolResult("invalid", output);
    }

    public record Settings(
            int lookDistance,
            long cooldownMillis,
            int itemMaxSlots,
            int itemMaxOutputChars
    ) {
        public Settings {
            if (lookDistance < 1 || lookDistance > 128 || cooldownMillis < 0L
                    || itemMaxSlots < 1 || itemMaxSlots > 36
                    || itemMaxOutputChars < 1_024 || itemMaxOutputChars > 65_536) {
                throw new IllegalArgumentException("invalid environment tool settings");
            }
        }
    }

    private record ItemSelection(String position, Integer slot, ItemStack item) {
    }

    private record Key(UUID player, String handler) {
    }
}
