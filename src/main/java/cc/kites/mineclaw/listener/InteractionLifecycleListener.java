package cc.kites.mineclaw.listener;

import cc.kites.mineclaw.approval.ApprovalManager;
import cc.kites.mineclaw.interaction.InteractionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/** Completes token-bound interactions when their exact target UUID leaves the server. */
public final class InteractionLifecycleListener implements Listener {
    private final InteractionManager interactions;

    public InteractionLifecycleListener(ApprovalManager approvals) {
        this(Objects.requireNonNull(approvals, "approvals").interactions());
    }

    public InteractionLifecycleListener(InteractionManager interactions) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        interactions.playerOffline(event.getPlayer().getUniqueId());
    }
}
