package cc.kites.mineclaw.listener;

import cc.kites.mineclaw.approval.ApprovalManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGestureListenerTest {
    private static final UUID PLAYER_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test
    void acceptsOnlySneakingMainHandRightClicksAtTheUpwardLimit() {
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, -90.0F, true, true)).isTrue();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, -89.0F, true, true)).isTrue();

        assertThat(gesture(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, true, -90.0F, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, true, -90.0F, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, null, true, -90.0F, true, true)).isFalse();
        assertThat(gesture(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, true, -90.0F, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, false, -90.0F, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, -88.99F, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, Float.NaN, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true,
                Float.NEGATIVE_INFINITY, true, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, -90.0F, false, true)).isFalse();
        assertThat(gesture(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, true, -90.0F, true, false)).isFalse();
    }

    @Test
    void requiresTheUntouchedAirInteractionResults() {
        assertThat(ApprovalGestureListener.untouchedNoOpResults(Event.Result.DENY, Event.Result.DEFAULT)).isTrue();

        for (Event.Result block : Event.Result.values()) {
            for (Event.Result item : Event.Result.values()) {
                if (block != Event.Result.DENY || item != Event.Result.DEFAULT) {
                    assertThat(ApprovalGestureListener.untouchedNoOpResults(block, item))
                            .as("block=%s item=%s", block, item)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void allowsInertItemsAndBlocksButRejectsAllKnownAirUseFamilies() {
        assertThat(ApprovalGestureListener.hasNativeAirUse(Material.STONE)).isFalse();
        assertThat(ApprovalGestureListener.hasNativeAirUse(Material.PAPER)).isFalse();
        assertThat(ApprovalGestureListener.hasNativeAirUse(Material.STICK)).isFalse();
        assertThat(ApprovalGestureListener.hasNativeAirUse(Material.IRON_PICKAXE)).isFalse();

        assertThat(Set.of(
                Material.EGG, Material.ENDER_PEARL, Material.BOW, Material.CROSSBOW,
                Material.FISHING_ROD, Material.GOAT_HORN, Material.SPLASH_POTION,
                Material.WIND_CHARGE, Material.WRITABLE_BOOK, Material.MAP,
                Material.OAK_BOAT, Material.BAMBOO_RAFT, Material.ALLAY_SPAWN_EGG,
                Material.WATER_BUCKET, Material.BUNDLE))
                .allSatisfy(material -> assertThat(ApprovalGestureListener.hasNativeAirUse(material))
                        .as(material.name()).isTrue());
    }

    @Test
    void rejectsActiveComponentsCustomItemsAirAndUnknownMaterials() {
        assertThat(ApprovalGestureListener.safeNoOpAirItem(Material.STONE, false, false)).isTrue();
        assertThat(ApprovalGestureListener.safeNoOpAirItem(Material.PAPER, false, false)).isTrue();

        assertThat(ApprovalGestureListener.safeNoOpAirItem(Material.STONE, true, false)).isFalse();
        assertThat(ApprovalGestureListener.safeNoOpAirItem(Material.STONE, false, true)).isFalse();
        assertThat(ApprovalGestureListener.safeNoOpAirItem(Material.EGG, false, false)).isFalse();
        assertThat(ApprovalGestureListener.safeNoOpAirItem(Material.AIR, false, false)).isFalse();
        assertThat(ApprovalGestureListener.safeNoOpAirItem(null, false, false)).isFalse();
    }

    @Test
    void approvesOnceOnlyWhenBothEventSnapshotsRemainSafe() {
        PlayerState state = new PlayerState(Material.STONE, Material.AIR);
        AtomicInteger approvals = new AtomicInteger();
        ApprovalGestureListener listener = listener(state, approvals, ApprovalManager.ApprovalOutcome.STARTED);
        PlayerInteractEvent event = interact(state);

        listener.rememberSafeAirGesture(event);
        listener.onInteract(event);
        listener.onInteract(event);

        assertThat(approvals).hasValue(1);
        assertThat(event.useInteractedBlock()).isEqualTo(Event.Result.DENY);
        assertThat(event.useItemInHand()).isEqualTo(Event.Result.DENY);
    }

    @Test
    void rejectsWhenAnotherPluginChangesEitherSnapshotOrAnItemBecomesActive() {
        PlayerState state = new PlayerState(Material.STONE, Material.AIR);
        AtomicInteger approvals = new AtomicInteger();
        AtomicBoolean itemSafe = new AtomicBoolean(true);
        ApprovalGestureListener listener = listener(state, approvals,
                ApprovalManager.ApprovalOutcome.STARTED, ignored -> itemSafe.get());

        PlayerInteractEvent changedResult = interact(state);
        listener.rememberSafeAirGesture(changedResult);
        changedResult.setUseItemInHand(Event.Result.DENY);
        listener.onInteract(changedResult);

        PlayerInteractEvent changedItem = interact(state);
        listener.rememberSafeAirGesture(changedItem);
        itemSafe.set(false);
        listener.onInteract(changedItem);

        PlayerInteractEvent unsafeAtLowest = interact(state);
        listener.rememberSafeAirGesture(unsafeAtLowest);
        itemSafe.set(true);
        listener.onInteract(unsafeAtLowest);

        assertThat(approvals).hasValue(0);
    }

    @Test
    void doesNotConsumeAnUnapprovedOrUnauthorizedClick() {
        PlayerState state = new PlayerState(Material.STONE, Material.AIR);
        AtomicInteger approvals = new AtomicInteger();
        ApprovalGestureListener noPending = listener(state, approvals, ApprovalManager.ApprovalOutcome.NONE);
        PlayerInteractEvent event = interact(state);
        noPending.rememberSafeAirGesture(event);
        noPending.onInteract(event);

        assertThat(approvals).hasValue(1);
        assertThat(event.useItemInHand()).isEqualTo(Event.Result.DEFAULT);

        state.permission.set(false);
        PlayerInteractEvent unauthorized = interact(state);
        noPending.rememberSafeAirGesture(unauthorized);
        noPending.onInteract(unauthorized);

        assertThat(approvals).hasValue(1);
        assertThat(unauthorized.useItemInHand()).isEqualTo(Event.Result.DEFAULT);
    }

    @Test
    void requiresSafeOffhandAndMatchesCandidatesByEventIdentity() {
        PlayerState state = new PlayerState(Material.STONE, Material.EGG);
        AtomicInteger approvals = new AtomicInteger();
        ApprovalGestureListener listener = listener(state, approvals, ApprovalManager.ApprovalOutcome.STARTED);
        PlayerInteractEvent unsafeOffhand = interact(state);
        listener.rememberSafeAirGesture(unsafeOffhand);

        state.offHand.set(new TestItemStack(Material.AIR));
        listener.onInteract(unsafeOffhand);

        PlayerInteractEvent candidate = interact(state);
        PlayerInteractEvent differentEvent = interact(state);
        listener.rememberSafeAirGesture(candidate);
        listener.onInteract(differentEvent);
        listener.onInteract(candidate);

        assertThat(approvals).hasValue(1);
    }

    private static boolean gesture(Action action, EquipmentSlot hand, boolean sneaking, float pitch,
                                   boolean untouchedResults, boolean safeItems) {
        return ApprovalGestureListener.isApprovalGesture(
                action, hand, sneaking, pitch, untouchedResults, safeItems);
    }

    private static ApprovalGestureListener listener(PlayerState state, AtomicInteger calls,
                                                     ApprovalManager.ApprovalOutcome outcome) {
        return listener(state, calls, outcome,
                item -> item != null && (item.getType() == Material.STONE
                        || item.getType() == Material.AIR
                        || item.getType() == Material.CAVE_AIR
                        || item.getType() == Material.VOID_AIR));
    }

    private static ApprovalGestureListener listener(PlayerState state, AtomicInteger calls,
                                                     ApprovalManager.ApprovalOutcome outcome,
                                                     ApprovalGestureListener.ItemSafety itemSafety) {
        return new ApprovalGestureListener(playerId -> {
            assertThat(playerId).isEqualTo(PLAYER_ID);
            calls.incrementAndGet();
            return outcome;
        }, itemSafety);
    }

    private static PlayerInteractEvent interact(PlayerState state) {
        return new PlayerInteractEvent(state.player, Action.RIGHT_CLICK_AIR,
                state.mainHand.get(), null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    private static final class PlayerState implements InvocationHandler {
        private final AtomicReference<ItemStack> mainHand;
        private final AtomicReference<ItemStack> offHand;
        private final AtomicBoolean permission = new AtomicBoolean(true);
        private final PlayerInventory inventory;
        private final Player player;

        private PlayerState(Material mainHand, Material offHand) {
            this.mainHand = new AtomicReference<>(new TestItemStack(mainHand));
            this.offHand = new AtomicReference<>(new TestItemStack(offHand));
            this.inventory = proxy(PlayerInventory.class, this::inventoryCall);
            this.player = proxy(Player.class, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isSneaking" -> true;
                case "getLocation" -> new Location(null, 0.0, 0.0, 0.0, 0.0F, -90.0F);
                case "getInventory" -> inventory;
                case "hasPermission" -> permission.get();
                case "getUniqueId" -> PLAYER_ID;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "ApprovalGestureTestPlayer";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object inventoryCall(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getItemInMainHand" -> mainHand.get();
                case "getItemInOffHand" -> offHand.get();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "ApprovalGestureTestInventory";
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    /** Minimal registry-free stack for Paper API unit tests outside a running server. */
    private static final class TestItemStack extends ItemStack {
        private final Material type;

        private TestItemStack(Material type) {
            this.type = type;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public boolean isEmpty() {
            return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
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
            return 0.0F;
        }
        return 0.0D;
    }
}
