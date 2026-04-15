package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class EndAccessListener implements Listener {
    private final MaceGuardPlugin plugin;

    public EndAccessListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEyeUse(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.END_PORTAL_FRAME) {
            return;
        }
        if (plugin.runtime().endAccessService().areEyesAllowed()) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.getPlayer().updateInventory();
        if (event.getHand() == EquipmentSlot.HAND) {
            event.getPlayer().sendMessage(plugin.runtime().endAccessService().statusLine(true));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEyeSpawn(EntitySpawnEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EntityType type = event.getEntityType();
        if (!type.name().equalsIgnoreCase("ENDER_SIGNAL") && !type.name().equalsIgnoreCase("EYE_OF_ENDER")) {
            return;
        }
        if (!plugin.runtime().endAccessService().areEyesAllowed()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        if (plugin.runtime().endAccessService().arePortalsAllowed()) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.runtime().endAccessService().statusLine(false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getFrom().getBlock().getType() != Material.END_PORTAL) {
            return;
        }
        if (!plugin.runtime().endAccessService().arePortalsAllowed()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (plugin.runtime().endAccessService().arePortalsAllowed()) {
            return;
        }
        if (!isNearEndPortal(event.getTo(), 3)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.runtime().endAccessService().statusLine(false));
    }

    private boolean isNearEndPortal(org.bukkit.Location location, int radius) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        for (int x = location.getBlockX() - radius; x <= location.getBlockX() + radius; x++) {
            for (int y = location.getBlockY() - 1; y <= location.getBlockY() + 1; y++) {
                for (int z = location.getBlockZ() - radius; z <= location.getBlockZ() + radius; z++) {
                    if (location.getWorld().getBlockAt(x, y, z).getType() == Material.END_PORTAL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
