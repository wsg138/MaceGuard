package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Acquires a latch when a tagged player crosses into an effective combat-zone flag. */
public final class CombatPositionListener implements Listener {
    private final CombatScopeService scopes;
    private final CombatIntegrationListener lifecycle;
    private final WarzoneRegionService region;
    private final Map<UUID, Long> lastEntryDenial = new HashMap<>();

    public CombatPositionListener(CombatScopeService scopes, CombatIntegrationListener lifecycle) {
        this(scopes, lifecycle, null);
    }

    public CombatPositionListener(CombatScopeService scopes, CombatIntegrationListener lifecycle,
                                  WarzoneRegionService region) {
        this.scopes = scopes;
        this.lifecycle = lifecycle;
        this.region = region;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location destination = event.getTo();
        if (destination != null && changedBlock(event.getFrom(), destination))
            reconcilePosition(event.getPlayer(), destination);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockedRegionMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null
                || !changedBlock(event.getFrom(), event.getTo())) return;
        blockRegionEntry(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockedRegionTeleport(PlayerTeleportEvent event) {
        blockRegionEntry(event);
    }

    private void blockRegionEntry(PlayerMoveEvent event) {
        if (region != null && !region.inConfiguredWorld(event.getTo())) return;
        String regionId = scopes.blockedRegionOnEntry(event.getPlayer(), event.getFrom(), event.getTo());
        if (regionId == null) return;
        event.setCancelled(true);
        UUID playerId = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = lastEntryDenial.get(playerId);
        if (previous == null || now - previous >= 1000L) {
            lastEntryDenial.put(playerId, now);
            event.getPlayer().sendMessage(Component.text("You cannot enter " + regionId
                    + " while in Warzone combat."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportComplete(PlayerTeleportEvent event) {
        reconcilePosition(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        lifecycle.clear(event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        lastEntryDenial.remove(event.getPlayer().getUniqueId());
        lifecycle.clear(event.getPlayer());
    }

    private void reconcilePosition(Player player, Location destination) {
        lifecycle.positionChanged(player, destination);
    }

    private boolean changedBlock(Location from, Location to) {
        return from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().getUID().equals(to.getWorld().getUID())
                || from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }
}
