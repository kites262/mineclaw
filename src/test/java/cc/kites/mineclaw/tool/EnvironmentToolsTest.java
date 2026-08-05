package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.support.FoliaTasks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentToolsTest {
    private static final UUID TURN_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final EnvironmentTools.Settings SETTINGS =
            new EnvironmentTools.Settings(12, 0L, 36, 12_000);

    @Test
    void onlinePlayersReturnsOnlyStableNamesReadFromEachEntityOwner() {
        Harness harness = new Harness();
        harness.addNamedPlayer("zed", true);
        harness.addNamedPlayer("Caller", true);
        harness.addNamedPlayer("Ghost", false);
        harness.addNamedPlayer("Alice", true);

        ToolResult result = harness.tools.execute("online_players", new JsonObject(), TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.output().keySet())
                .containsExactlyInAnyOrder("status", "turn_player", "online_players");
        JsonArray online = result.output().getAsJsonArray("online_players");
        assertThat(online.asList()).extracting(element -> element.getAsString())
                .containsExactly("Alice", "Caller", "zed");
        assertThat(result.output().toString()).doesNotContain(TURN_PLAYER.toString());
        assertThat(harness.globalScheduler.executeCalls).hasValue(1);
        assertThat(harness.entityExecutions).hasValue(4);
    }

    @Test
    void playerSnapshotReturnsBoundPlayerStateFromItsEntityOwner() {
        Harness harness = new Harness();
        harness.installSnapshotPlayer();

        ToolResult result = harness.tools.execute("player_snapshot", new JsonObject(), TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.output().get("player").getAsString()).isEqualTo("Caller");
        assertThat(result.output().getAsJsonObject("position").get("world_key").getAsString())
                .isEqualTo("minecraft:overworld");
        assertThat(result.output().getAsJsonObject("survival").get("health").getAsDouble())
                .isEqualTo(18.0);
        assertThat(result.output().getAsJsonObject("environment").get("biome").getAsString())
                .isEqualTo("unknown");
        assertThat(result.output().getAsJsonObject("environment").get("world_time_ticks").getAsLong())
                .isEqualTo(6_000L);
        assertThat(result.output().getAsJsonObject("environment").get("world_full_time_ticks").getAsLong())
                .isEqualTo(30_000L);
        assertThat(result.output().toString())
                .doesNotContain(TURN_PLAYER.toString(), "address", "permission");
        assertThat(harness.entityExecutions).hasValue(1);
    }

    @Test
    void missingOrOfflineTurnPlayerIsDenied() {
        Harness missing = new Harness();

        ToolResult missingResult = missing.tools.execute("player_snapshot", new JsonObject(), TURN_PLAYER,
                "Caller", SETTINGS).join();

        assertThat(missingResult.status()).isEqualTo("denied");
        assertThat(missingResult.output().get("status").getAsString()).isEqualTo("denied");
        assertThat(missing.globalScheduler.executeCalls).hasValue(1);
        assertThat(missing.entityExecutions).hasValue(0);

        Harness offline = new Harness();
        offline.installOfflineTurnPlayer();

        ToolResult offlineResult = offline.tools.execute("item_inspect", new JsonObject(), TURN_PLAYER,
                "Caller", SETTINGS).join();

        assertThat(offlineResult.status()).isEqualTo("denied");
        assertThat(offlineResult.output().get("status").getAsString()).isEqualTo("denied");
        assertThat(offline.entityExecutions).hasValue(1);
    }

    @Test
    void itemInspectReplacesInventorySummaryAndSupportsExplicitEmptySlots() {
        Harness harness = new Harness();
        harness.installSnapshotPlayer();

        JsonObject inventory = new JsonObject();
        inventory.addProperty("mode", "inventory");
        ToolResult summary = harness.tools.execute("item_inspect", inventory, TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(summary.status()).isEqualTo("ok");
        assertThat(summary.output().get("mode").getAsString()).isEqualTo("inventory");
        assertThat(summary.output().getAsJsonArray("items").toString())
                .isEqualTo("[]")
                .doesNotContain("nbt", "persistent_data", "book_text");
        assertThat(summary.output().get("truncated").getAsBoolean()).isFalse();

        JsonObject slot = new JsonObject();
        slot.addProperty("mode", "slot");
        slot.addProperty("slot", 1);
        ToolResult empty = harness.tools.execute("item_inspect", slot, TURN_PLAYER, "Caller",
                SETTINGS).join();
        assertThat(empty.status()).isEqualTo("ok");
        assertThat(empty.output().get("item").isJsonNull()).isTrue();
    }

    @Test
    void itemInspectDoesNotReadMissingCustomMaximum() {
        Damageable metadata = proxy(Damageable.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "hasMaxDamage", "hasDisplayName" -> false;
            case "getMaxDamage" -> throw new IllegalStateException("getMaxDamage requires hasMaxDamage");
            case "getDamage" -> 42;
            default -> defaultValue(method.getReturnType());
        });
        ItemStack pickaxe = new ItemStack() {
            @Override
            public Material getType() {
                return Material.DIAMOND_PICKAXE;
            }

            @Override
            public int getAmount() {
                return 1;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public boolean hasItemMeta() {
                return true;
            }

            @Override
            public Damageable getItemMeta() {
                return metadata;
            }

            @Override
            public java.util.Map<org.bukkit.enchantments.Enchantment, Integer> getEnchantments() {
                return java.util.Map.of();
            }
        };
        Harness harness = new Harness();
        harness.installSnapshotPlayer(pickaxe);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("mode", "inventory");

        ToolResult result = harness.tools.execute("item_inspect", arguments, TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(result.status()).isEqualTo("ok");
        JsonObject item = result.output().getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(item.get("type").getAsString()).isEqualTo("minecraft:diamond_pickaxe");
        assertThat(item.has("damage")).isFalse();
        assertThat(item.has("max_damage")).isFalse();
    }

    @Test
    void inventorySummaryStopsAtCharacterBudgetAndReportsTruncation() {
        ItemMeta metadata = proxy(ItemMeta.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "hasDisplayName" -> true;
            case "displayName" -> net.kyori.adventure.text.Component.text("x".repeat(256));
            default -> defaultValue(method.getReturnType());
        });
        ItemStack[] contents = new ItemStack[6];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = item(Material.PAPER, metadata);
        }
        Harness harness = new Harness();
        harness.installSnapshotPlayerContents(contents);
        EnvironmentTools.Settings bounded = new EnvironmentTools.Settings(12, 0L, 6, 1_024);

        ToolResult result = harness.tools.execute("item_inspect", new JsonObject(), TURN_PLAYER, "Caller",
                bounded).join();

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.output().get("scanned_storage_slots").getAsInt()).isEqualTo(6);
        assertThat(result.output().get("truncated").getAsBoolean()).isTrue();
        JsonArray items = result.output().getAsJsonArray("items");
        assertThat(items.size()).isBetween(1, contents.length - 1);
        for (int index = 0; index < items.size(); index++) {
            assertThat(items.get(index).getAsJsonObject().get("slot").getAsInt()).isEqualTo(index);
        }
        assertThat(result.output().toString()).hasSizeLessThanOrEqualTo(1_024);
    }

    @Test
    void customNameTruncationPreservesUnicodeCodePoints() {
        String rawName = "x".repeat(255) + "🚀tail";
        ItemMeta metadata = proxy(ItemMeta.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "hasDisplayName" -> true;
            case "displayName" -> net.kyori.adventure.text.Component.text(rawName);
            default -> defaultValue(method.getReturnType());
        });
        Harness harness = new Harness();
        harness.installSnapshotPlayer(item(Material.PAPER, metadata));

        ToolResult result = harness.tools.execute("item_inspect", new JsonObject(), TURN_PLAYER,
                "Caller", SETTINGS).join();

        String customName = result.output().getAsJsonArray("items").get(0).getAsJsonObject()
                .get("custom_name").getAsString();
        assertThat(customName.codePointCount(0, customName.length())).isEqualTo(256);
        assertThat(customName).endsWith("🚀").doesNotContain("tail");
        assertThat(new String(customName.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(customName);
    }

    @Test
    void inventorySummaryDoesNotExposePdcLoreBookOrContainerContents() {
        String pdcSecret = "SECRET_PDC_VALUE";
        String loreSecret = "SECRET_LORE_TEXT";
        String bookSecret = "SECRET_BOOK_TEXT";
        String containerSecret = "SECRET_CONTAINER_ITEM";
        PersistentDataContainer persistentData = proxy(PersistentDataContainer.class,
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "isEmpty" -> false;
                    case "getKeys" -> Set.of(new NamespacedKey("private", "secret"));
                    case "serializeToBytes" -> pdcSecret.getBytes(StandardCharsets.UTF_8);
                    case "toString" -> pdcSecret;
                    default -> defaultValue(method.getReturnType());
                });
        BookMeta book = proxy(BookMeta.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "hasDisplayName" -> false;
            case "getPersistentDataContainer" -> persistentData;
            case "hasLore", "hasPages", "hasTitle", "hasAuthor" -> true;
            case "getLore", "getPages" -> List.of(method.getName().equals("getLore") ? loreSecret : bookSecret);
            case "lore", "pages" -> List.of(net.kyori.adventure.text.Component.text(
                    method.getName().equals("lore") ? loreSecret : bookSecret));
            case "getTitle", "getAuthor", "getPage" -> bookSecret;
            case "title", "author", "page" -> net.kyori.adventure.text.Component.text(bookSecret);
            case "toString" -> bookSecret;
            default -> defaultValue(method.getReturnType());
        });
        Container container = proxy(Container.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getType" -> Material.CHEST;
            case "toString" -> containerSecret;
            default -> defaultValue(method.getReturnType());
        });
        BlockStateMeta containerMeta = proxy(BlockStateMeta.class,
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "hasDisplayName" -> false;
                    case "hasBlockState" -> true;
                    case "getBlockState" -> container;
                    case "getPersistentDataContainer" -> persistentData;
                    case "hasLore" -> true;
                    case "getLore" -> List.of(loreSecret);
                    case "lore" -> List.of(net.kyori.adventure.text.Component.text(loreSecret));
                    case "toString" -> containerSecret;
                    default -> defaultValue(method.getReturnType());
                });
        Harness harness = new Harness();
        harness.installSnapshotPlayerContents(
                item(Material.WRITTEN_BOOK, book),
                item(Material.CHEST, containerMeta));

        ToolResult result = harness.tools.execute("item_inspect", new JsonObject(), TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.output().getAsJsonArray("items")).hasSize(3);
        assertThat(result.output().toString()).doesNotContain(
                pdcSecret, loreSecret, bookSecret, containerSecret,
                "persistent_data", "book_text", "container_contents", "nbt");
    }

    @Test
    void itemInspectRejectsInconsistentSelectors() {
        Harness harness = new Harness();
        harness.installSnapshotPlayer();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("mode", "slot");

        ToolResult result = harness.tools.execute("item_inspect", arguments, TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(result.status()).isEqualTo("invalid");
        assertThat(result.output().get("error_code").getAsString()).isEqualTo("invalid_slot_selector");
    }

    @Test
    void itemDetailReportsUnexpandedDataComponentsAndEffectiveEquipmentSlot() {
        PersistentDataContainer emptyPdc = proxy(PersistentDataContainer.class,
                (ignored, method, arguments) -> method.getName().equals("isEmpty")
                        ? true : defaultValue(method.getReturnType()));
        EquippableComponent equippable = proxy(EquippableComponent.class,
                (ignored, method, arguments) -> method.getName().equals("getSlot")
                        ? org.bukkit.inventory.EquipmentSlot.CHEST : defaultValue(method.getReturnType()));
        ItemMeta metadata = proxy(ItemMeta.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "hasDisplayName", "hasLore" -> false;
            case "getPersistentDataContainer" -> emptyPdc;
            case "hasEquippable" -> true;
            case "getEquippable" -> equippable;
            default -> defaultValue(method.getReturnType());
        });
        DataComponentType hidden = proxy(DataComponentType.class,
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getKey" -> NamespacedKey.minecraft("custom_model_data");
                    case "isPersistent" -> true;
                    default -> defaultValue(method.getReturnType());
                });
        Harness harness = new Harness();
        harness.installSnapshotPlayer(item(Material.PAPER, metadata, Set.of(hidden)));
        JsonObject arguments = new JsonObject();
        arguments.addProperty("mode", "slot");
        arguments.addProperty("slot", 0);

        ToolResult result = harness.tools.execute("item_inspect", arguments, TURN_PLAYER, "Caller",
                SETTINGS).join();

        JsonObject detail = result.output().getAsJsonObject("item");
        assertThat(detail.get("has_unexpanded_data").getAsBoolean()).isTrue();
        assertThat(detail.getAsJsonObject("properties").get("equipment_slot").getAsString())
                .isEqualTo("chest");
        assertThat(detail.toString()).doesNotContain("custom_model_data");

        ItemMeta ordinaryMetadata = proxy(ItemMeta.class,
                (ignored, method, methodArguments) -> switch (method.getName()) {
                    case "hasDisplayName", "hasLore", "hasEquippable" -> false;
                    case "getPersistentDataContainer" -> emptyPdc;
                    default -> defaultValue(method.getReturnType());
                });
        Harness ordinaryHarness = new Harness();
        ordinaryHarness.installSnapshotPlayer(item(Material.PAPER, ordinaryMetadata));
        ToolResult ordinary = ordinaryHarness.tools.execute("item_inspect", arguments, TURN_PLAYER,
                "Caller", SETTINGS).join();
        JsonObject ordinaryDetail = ordinary.output().getAsJsonObject("item");
        assertThat(ordinaryDetail.get("has_unexpanded_data").getAsBoolean()).isFalse();
        assertThat(ordinaryDetail.getAsJsonObject("properties").has("equipment_slot")).isFalse();
    }

    @Test
    void blockInspectReturnsStructuredPropertiesAndEnforcesDistanceLimit() {
        Harness harness = new Harness();
        harness.installSnapshotPlayer();
        JsonObject look = new JsonObject();
        look.addProperty("mode", "look");
        look.addProperty("distance", 8);

        ToolResult result = harness.tools.execute("block_inspect", look, TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(result.status()).isEqualTo("ok");
        JsonObject block = result.output().getAsJsonObject("block");
        assertThat(block.get("type").getAsString()).isEqualTo("minecraft:wheat");
        assertThat(block.getAsJsonObject("block_data").entrySet())
                .extracting(java.util.Map.Entry::getKey).containsExactly("age");
        assertThat(block.getAsJsonObject("block_data").get("age").getAsString()).isEqualTo("7");
        assertThat(block.toString()).doesNotContain("minecraft:wheat[age=7]");

        JsonObject tooFar = new JsonObject();
        tooFar.addProperty("distance", 13);
        ToolResult denied = harness.tools.execute("block_inspect", tooFar, TURN_PLAYER, "Caller",
                SETTINGS).join();
        assertThat(denied.status()).isEqualTo("invalid");
        assertThat(denied.output().get("error_code").getAsString()).isEqualTo("look_distance_exceeded");
    }

    @Test
    void blockInspectReturnsNullWithoutLookTargetAndSupportsFeetMode() {
        Harness harness = new Harness();
        harness.installSnapshotPlayerWithoutLookTarget();

        ToolResult look = harness.tools.execute("block_inspect", new JsonObject(), TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(look.status()).isEqualTo("ok");
        assertThat(look.output().get("mode").getAsString()).isEqualTo("look");
        assertThat(look.output().get("block").isJsonNull()).isTrue();

        JsonObject arguments = new JsonObject();
        arguments.addProperty("mode", "feet");
        ToolResult feet = harness.tools.execute("block_inspect", arguments, TURN_PLAYER, "Caller",
                SETTINGS).join();

        assertThat(feet.status()).isEqualTo("ok");
        assertThat(feet.output().get("mode").getAsString()).isEqualTo("feet");
        JsonObject block = feet.output().getAsJsonObject("block");
        assertThat(block.get("type").getAsString()).isEqualTo("minecraft:wheat");
        assertThat(block.get("y").getAsInt()).isEqualTo(64);
    }

    @Test
    void repeatedEnvironmentToolCallsRespectPerHandlerCooldown() {
        Harness harness = new Harness();
        harness.installSnapshotPlayer();
        EnvironmentTools.Settings cooldown = new EnvironmentTools.Settings(12, 60_000L, 36, 12_000);

        assertThat(harness.tools.execute("player_snapshot", new JsonObject(), TURN_PLAYER, "Caller", cooldown)
                .join().status()).isEqualTo("ok");
        ToolResult second = harness.tools.execute("player_snapshot", new JsonObject(), TURN_PLAYER, "Caller",
                cooldown).join();

        assertThat(second.status()).isEqualTo("recoverable_error");
        assertThat(second.output().get("error_code").getAsString()).isEqualTo("tool_rate_limited");
    }

    private static final class Harness {
        private final AtomicBoolean globalOwner = new AtomicBoolean();
        private final AtomicReference<String> entityOwner = new AtomicReference<>();
        private final AtomicInteger entityExecutions = new AtomicInteger();
        private final List<Player> players = new ArrayList<>();
        private final AtomicReference<Player> turnPlayer = new AtomicReference<>();
        private final ImmediateGlobalScheduler globalScheduler = new ImmediateGlobalScheduler(globalOwner);
        private final Server server = proxy(Server.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getGlobalRegionScheduler" -> globalScheduler;
            case "getAsyncScheduler" -> new NoopAsyncScheduler();
            case "getOnlinePlayers" -> onlinePlayers();
            case "getPlayer" -> {
                requireGlobal();
                yield turnPlayer.get();
            }
            default -> defaultValue(method.getReturnType());
        });
        private final Plugin plugin = proxy(Plugin.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "isEnabled" -> true;
            default -> defaultValue(method.getReturnType());
        });
        private final EnvironmentTools tools = new EnvironmentTools(server, new FoliaTasks(plugin));

        private void addNamedPlayer(String name, boolean online) {
            EntityScheduler scheduler = new ImmediateEntityScheduler(name, entityOwner, entityExecutions);
            Player player = proxy(Player.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getScheduler" -> scheduler;
                case "isOnline" -> {
                    requireOwner(name);
                    yield online;
                }
                case "getName" -> {
                    requireOwner(name);
                    yield name;
                }
                default -> defaultValue(method.getReturnType());
            });
            players.add(player);
        }

        private void installSnapshotPlayer() {
            installSnapshotPlayer(true, new ItemStack[0]);
        }

        private void installSnapshotPlayer(ItemStack firstStorageItem) {
            installSnapshotPlayer(true, new ItemStack[]{firstStorageItem});
        }

        private void installSnapshotPlayerContents(ItemStack... storageItems) {
            installSnapshotPlayer(true, storageItems);
        }

        private void installSnapshotPlayerWithoutLookTarget() {
            installSnapshotPlayer(false, new ItemStack[0]);
        }

        private void installOfflineTurnPlayer() {
            String name = "Caller";
            EntityScheduler scheduler = new ImmediateEntityScheduler(name, entityOwner, entityExecutions);
            Player player = proxy(Player.class, (ignored, method, arguments) -> {
                if (method.getName().equals("getScheduler")) {
                    return scheduler;
                }
                requireOwner(name);
                return method.getName().equals("isOnline") ? false : defaultValue(method.getReturnType());
            });
            turnPlayer.set(player);
        }

        private void installSnapshotPlayer(boolean hasLookTarget, ItemStack[] storageItems) {
            String name = "Caller";
            EntityScheduler scheduler = new ImmediateEntityScheduler(name, entityOwner, entityExecutions);
            AtomicReference<Block> blockReference = new AtomicReference<>();
            World world = proxy(World.class, (ignored, method, arguments) -> {
                requireOwner(name);
                return switch (method.getName()) {
                    case "getName" -> "world";
                    case "getKey" -> NamespacedKey.minecraft("overworld");
                    case "getEnvironment" -> World.Environment.NORMAL;
                    case "getTime" -> 6_000L;
                    case "getFullTime" -> 30_000L;
                    case "hasStorm", "isThundering" -> false;
                    case "getBlockAt" -> blockReference.get();
                    default -> defaultValue(method.getReturnType());
                };
            });
            BlockData blockData = proxy(BlockData.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getMaterial" -> Material.WHEAT;
                case "getAsString" -> "minecraft:wheat[age=7]";
                default -> defaultValue(method.getReturnType());
            });
            Block block = proxy(Block.class, (ignored, method, arguments) -> {
                requireOwner(name);
                return switch (method.getName()) {
                    case "getType" -> Material.WHEAT;
                    case "getWorld" -> world;
                    case "getX" -> 10;
                    case "getY" -> 64;
                    case "getZ" -> -4;
                    case "getBlockData" -> blockData;
                    case "isLiquid", "isPassable" -> false;
                    case "isSolid" -> true;
                    case "getLightLevel" -> (byte) 12;
                    case "getLightFromSky" -> (byte) 10;
                    case "getLightFromBlocks" -> (byte) 2;
                    case "getBiome" -> null;
                    case "getState" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            });
            blockReference.set(block);
            Location location = new Location(world, 10.5, 64.0, -3.5, 90.0f, 15.0f);
            ItemStack[] storage = new ItemStack[36];
            System.arraycopy(storageItems, 0, storage, 0, Math.min(storage.length, storageItems.length));
            PlayerInventory inventory = proxy(PlayerInventory.class,
                    (ignored, method, arguments) -> {
                        requireOwner(name);
                        return switch (method.getName()) {
                            case "getStorageContents" -> storage.clone();
                            case "getItemInMainHand" -> storage[0];
                            case "getItemInOffHand", "getHelmet", "getChestplate", "getLeggings", "getBoots" -> null;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
            AttributeInstance maximumHealth = proxy(AttributeInstance.class,
                    (ignored, method, arguments) -> method.getName().equals("getValue")
                            ? 20.0 : defaultValue(method.getReturnType()));
            Player player = proxy(Player.class, (ignored, method, arguments) -> {
                if (method.getName().equals("getScheduler")) {
                    return scheduler;
                }
                requireOwner(name);
                return switch (method.getName()) {
                    case "isOnline" -> true;
                    case "getName" -> name;
                    case "getLocation" -> location.clone();
                    case "getTargetBlockExact" -> hasLookTarget ? block : null;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "getHealth" -> 18.0;
                    case "getAttribute" -> maximumHealth;
                    case "getFoodLevel" -> 17;
                    case "getSaturation" -> 4.0f;
                    case "getLevel" -> 12;
                    case "getExp" -> 0.5f;
                    case "getRemainingAir", "getMaximumAir" -> 300;
                    case "getFireTicks", "getFreezeTicks" -> 0;
                    case "getMaxFreezeTicks" -> 140;
                    case "isFrozen", "isSneaking", "isSprinting", "isSwimming", "isGliding",
                            "isFlying", "isInsideVehicle" -> false;
                    case "isOnGround" -> true;
                    case "getVehicle" -> null;
                    case "getActivePotionEffects" -> List.of();
                    case "getInventory" -> inventory;
                    default -> defaultValue(method.getReturnType());
                };
            });
            turnPlayer.set(player);
        }

        private Collection<? extends Player> onlinePlayers() {
            requireGlobal();
            return List.copyOf(players);
        }

        private void requireGlobal() {
            if (!globalOwner.get()) {
                throw new AssertionError("server state read outside GlobalScheduler");
            }
        }

        private void requireOwner(String name) {
            if (!name.equals(entityOwner.get())) {
                throw new AssertionError("player state read outside its EntityScheduler: " + name);
            }
        }
    }

    private static final class ImmediateGlobalScheduler implements GlobalRegionScheduler {
        private final AtomicBoolean owner;
        private final AtomicInteger executeCalls = new AtomicInteger();

        private ImmediateGlobalScheduler(AtomicBoolean owner) {
            this.owner = owner;
        }

        @Override
        public void execute(Plugin plugin, Runnable run) {
            executeCalls.incrementAndGet();
            if (!owner.compareAndSet(false, true)) {
                throw new AssertionError("nested GlobalScheduler execution");
            }
            try {
                run.run();
            } finally {
                owner.set(false);
            }
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delayTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) {
        }
    }

    private static final class ImmediateEntityScheduler implements EntityScheduler {
        private final String name;
        private final AtomicReference<String> owner;
        private final AtomicInteger executions;

        private ImmediateEntityScheduler(String name, AtomicReference<String> owner, AtomicInteger executions) {
            this.name = name;
            this.owner = owner;
            this.executions = executions;
        }

        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
            executions.incrementAndGet();
            if (!owner.compareAndSet(null, name)) {
                throw new AssertionError("nested EntityScheduler execution");
            }
            try {
                run.run();
            } finally {
                owner.set(null);
            }
            return true;
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                        Runnable retired, long delayTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            Runnable retired, long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopAsyncScheduler implements AsyncScheduler {
        @Override
        public ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                        long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) {
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static ItemStack item(Material material, ItemMeta metadata) {
        return item(material, metadata, Set.of());
    }

    private static ItemStack item(Material material, ItemMeta metadata,
                                  Set<DataComponentType> overriddenData) {
        return new ItemStack() {
            @Override
            public Material getType() {
                return material;
            }

            @Override
            public int getAmount() {
                return 1;
            }

            @Override
            public int getMaxStackSize() {
                return 64;
            }

            @Override
            public boolean hasItemMeta() {
                return metadata != null;
            }

            @Override
            public ItemMeta getItemMeta() {
                return metadata;
            }

            @Override
            public java.util.Map<org.bukkit.enchantments.Enchantment, Integer> getEnchantments() {
                return java.util.Map.of();
            }

            @Override
            public Set<DataComponentType> getDataTypes() {
                return overriddenData;
            }

            @Override
            public boolean isDataOverridden(DataComponentType type) {
                return overriddenData.stream().anyMatch(candidate -> candidate == type);
            }
        };
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
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0.0d;
    }
}
