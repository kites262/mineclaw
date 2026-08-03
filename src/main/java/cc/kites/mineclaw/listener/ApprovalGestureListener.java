package cc.kites.mineclaw.listener;

import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.commandexec.CommandExecutor;
import cc.kites.mineclaw.interaction.InteractionManager;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Accepts the current approval through an intentional, otherwise inert player gesture. */
public final class ApprovalGestureListener implements Listener {
    static final float UPWARD_PITCH_THRESHOLD = -89.0F;

    private final Approver approvals;
    private final ItemSafety itemSafety;
    private final Set<PlayerInteractEvent> safeAirCandidates = ConcurrentHashMap.newKeySet();

    public ApprovalGestureListener(CommandExecutor commands) {
        this(commands::approveCurrent, ApprovalGestureListener::safeNoOpAirItem);
    }

    public ApprovalGestureListener(InteractionManager interactions) {
        this(playerId -> interactions.approveCurrentConfirm(playerId)
                        == InteractionManager.Outcome.APPROVED
                        ? ApprovalManager.ApprovalOutcome.STARTED
                        : ApprovalManager.ApprovalOutcome.NONE,
                ApprovalGestureListener::safeNoOpAirItem);
    }

    ApprovalGestureListener(Approver approvals, ItemSafety itemSafety) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.itemSafety = Objects.requireNonNull(itemSafety, "itemSafety");
    }

    /** Records the server's original no-op prediction before later plugins can rewrite the event. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void rememberSafeAirGesture(PlayerInteractEvent event) {
        if (isApprovalGesture(event, itemSafety)) {
            safeAirCandidates.add(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        boolean originallySafeAir = safeAirCandidates.remove(event);
        var player = event.getPlayer();
        if (!originallySafeAir
                || !isApprovalGesture(event, itemSafety)
                || !player.hasPermission("mineclaw.command.approve")) {
            return;
        }
        if (approvals.approveCurrent(player.getUniqueId()) != ApprovalManager.ApprovalOutcome.STARTED) {
            return;
        }

        // The classified click is already inert. Deny both branches as a final guard against
        // the held item or another plugin changing after the HIGHEST snapshot.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private static boolean isApprovalGesture(PlayerInteractEvent event, ItemSafety itemSafety) {
        var player = event.getPlayer();
        return isApprovalGesture(event.getAction(), event.getHand(), player.isSneaking(),
                player.getLocation().getPitch(), untouchedNoOpResults(
                        event.useInteractedBlock(), event.useItemInHand()),
                safeNoOpItems(itemSafety, event.getItem(), player.getInventory().getItemInMainHand(),
                        player.getInventory().getItemInOffHand()));
    }

    static boolean isApprovalGesture(Action action, EquipmentSlot hand, boolean sneaking, float pitch,
                                     boolean untouchedNoOpResults, boolean safeNoOpItems) {
        return action == Action.RIGHT_CLICK_AIR
                && hand == EquipmentSlot.HAND
                && sneaking
                && Float.isFinite(pitch)
                && pitch <= UPWARD_PITCH_THRESHOLD
                && untouchedNoOpResults
                && safeNoOpItems;
    }

    static boolean untouchedNoOpResults(Event.Result block, Event.Result item) {
        return block == Event.Result.DENY && item == Event.Result.DEFAULT;
    }

    private static boolean safeNoOpItems(ItemSafety itemSafety, ItemStack eventItem,
                                         ItemStack currentMainHand, ItemStack currentOffHand) {
        return itemSafety.safe(eventItem)
                && itemSafety.safe(currentMainHand)
                && (air(currentOffHand) || itemSafety.safe(currentOffHand));
    }

    private static boolean safeNoOpAirItem(ItemStack item) {
        if (air(item)) {
            return false;
        }
        try {
            boolean activeComponent = item.hasData(DataComponentTypes.CONSUMABLE)
                    || item.hasData(DataComponentTypes.EQUIPPABLE)
                    || item.hasData(DataComponentTypes.BLOCKS_ATTACKS)
                    || item.hasData(DataComponentTypes.KINETIC_WEAPON);
            boolean customPluginItem = !item.getPersistentDataContainer().isEmpty();
            return safeNoOpAirItem(item.getType(), activeComponent, customPluginItem);
        } catch (RuntimeException exception) {
            // An item whose behavior cannot be classified must not become an approval shortcut.
            return false;
        }
    }

    static boolean safeNoOpAirItem(Material material, boolean activeComponent, boolean customPluginItem) {
        return material != null
                && !air(material)
                && !activeComponent
                && !customPluginItem
                && !hasNativeAirUse(material);
    }

    /**
     * Vanilla item classes whose {@code use()} method can produce an effect while clicking air.
     * Block-only tools are intentionally absent: RIGHT_CLICK_BLOCK is rejected by the gesture gate.
     */
    static boolean hasNativeAirUse(Material material) {
        String name = Objects.requireNonNull(material, "material").name();
        if (name.endsWith("_BOAT")
                || name.endsWith("_RAFT")
                || name.endsWith("_SPAWN_EGG")
                || name.endsWith("_BUCKET")
                || name.equals("BUCKET")
                || name.endsWith("_BUNDLE")
                || name.equals("BUNDLE")) {
            return true;
        }
        return switch (material) {
            case BLUE_EGG, BROWN_EGG, EGG,
                    BOW, CROSSBOW, TRIDENT,
                    ENDER_EYE, ENDER_PEARL, EXPERIENCE_BOTTLE,
                    FIREWORK_ROCKET, FISHING_ROD, SNOWBALL, WIND_CHARGE,
                    CARROT_ON_A_STICK, WARPED_FUNGUS_ON_A_STICK,
                    GLASS_BOTTLE, GOAT_HORN, KNOWLEDGE_BOOK,
                    LINGERING_POTION, SPLASH_POTION,
                    FROGSPAWN, LILY_PAD, MAP, SPYGLASS,
                    WRITABLE_BOOK, WRITTEN_BOOK -> true;
            default -> false;
        };
    }

    private static boolean air(ItemStack item) {
        return item == null || air(item.getType());
    }

    private static boolean air(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    @FunctionalInterface
    interface Approver {
        ApprovalManager.ApprovalOutcome approveCurrent(java.util.UUID playerId);
    }

    @FunctionalInterface
    interface ItemSafety {
        boolean safe(ItemStack item);
    }
}
