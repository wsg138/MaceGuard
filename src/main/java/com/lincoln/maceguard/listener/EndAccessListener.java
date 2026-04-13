package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.entity.EntitySpawnEvent;

public final class EndAccessListener implements Listener {

    private final MaceGuardPlugin plugin;

    public EndAccessListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEyeUse(PlayerInteractEvent event) {
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // Allow slotting eyes into portal frames even while throws are locked.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                event.getClickedBlock() != null &&
                event.getClickedBlock().getType() == Material.END_PORTAL_FRAME) {
            return;
        }

        if (plugin.areEndEyesAllowed()) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.getPlayer().updateInventory();
        if (event.getHand() == EquipmentSlot.HAND) {
            event.getPlayer().sendMessage(plugin.statusLine(true));
        }
    }

    // Catch any eye entity that somehow still spawns.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEyeSpawn(EntitySpawnEvent event) {
        EntityType type = event.getEntityType();
        // Different server versions may use ENDER_SIGNAL or EYE_OF_ENDER; check by name to be safe.
        if (!type.name().equalsIgnoreCase("ENDER_SIGNAL") && !type.name().equalsIgnoreCase("EYE_OF_ENDER")) return;
        if (plugin.areEndEyesAllowed()) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) return;
        if (plugin.areEndPortalsAllowed()) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.statusLine(false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityEndPortal(EntityPortalEvent event) {
        // Catch any non-player entities using end portals.
        Material fromType = event.getFrom().getBlock().getType();
        if (fromType != Material.END_PORTAL) return;
        if (plugin.areEndPortalsAllowed()) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        if (plugin.areEndPortalsAllowed()) return;
        // Only block pearls that would land in/near an end portal block (prevent portal bypass),
        // allow normal pearls elsewhere.
        if (isNearEndPortal(event.getTo(), 3)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.statusLine(false));
        }
    }

    private boolean isNearEndPortal(org.bukkit.Location loc, int radius) {
        if (loc == null || loc.getWorld() == null) return false;
        int r = Math.max(0, radius);
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();
        for (int x = baseX - r; x <= baseX + r; x++) {
            for (int y = baseY - 1; y <= baseY + 1; y++) { // portal blocks lie in a thin layer
                for (int z = baseZ - r; z <= baseZ + r; z++) {
                    if (loc.getWorld().getBlockAt(x, y, z).getType() == Material.END_PORTAL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
