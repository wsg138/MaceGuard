package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.service.DuelArenaFootprintService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class DuelArenaExplosiveListener implements Listener {
    private final MaceGuardPlugin plugin;

    public DuelArenaExplosiveListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        if (!footprint.maybeRelevant(crystal.getLocation()) && !footprint.contains(crystal.getLocation().clone().subtract(0, 1, 0))) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (!plugin.warzoneDuelsHook().hasActiveDuel() || attacker == null || !plugin.warzoneDuelsHook().isActiveParticipant(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle().getType() != org.bukkit.entity.EntityType.TNT_MINECART) {
            return;
        }
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        if (!footprint.maybeRelevant(event.getVehicle().getLocation())) {
            return;
        }
        if (!plugin.warzoneDuelsHook().hasActiveDuel()) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getAttacker() instanceof Player attacker) || !plugin.warzoneDuelsHook().isActiveParticipant(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        if (!footprint.maybeRelevant(event.getEntity().getLocation()) && !sourceTouchesArena(event.getEntity())) {
            return;
        }
        if (!plugin.warzoneDuelsHook().hasActiveDuel() || !isExplosionSourceAllowed(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        if (!footprint.maybeRelevant(event.getLocation()) && !containsArenaBlocks(event.blockList())) {
            return;
        }
        if (!plugin.warzoneDuelsHook().hasActiveDuel() || !isExplosionSourceAllowed(event.getEntity())) {
            event.blockList().clear();
            return;
        }
        filterToArenaBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        if (!footprint.maybeRelevant(event.getBlock().getLocation()) && !containsArenaBlocks(event.blockList())) {
            return;
        }
        if (!plugin.warzoneDuelsHook().hasActiveDuel() || !isArenaSourceBlockAllowed(event.getBlock())) {
            event.blockList().clear();
            return;
        }
        filterToArenaBlocks(event.blockList());
    }

    private boolean containsArenaBlocks(List<Block> blocks) {
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        for (Block block : blocks) {
            if (footprint.contains(block)) {
                return true;
            }
        }
        return false;
    }

    private void filterToArenaBlocks(List<Block> blocks) {
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        for (Iterator<Block> iterator = blocks.iterator(); iterator.hasNext(); ) {
            Block block = iterator.next();
            if (!footprint.contains(block)) {
                iterator.remove();
            }
        }
    }

    private boolean isExplosionSourceAllowed(Entity entity) {
        if (entity == null) {
            return false;
        }
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        if (entity instanceof EnderCrystal) {
            return isArenaSourceLocationAllowed(entity.getLocation()) || isArenaSourceLocationAllowed(entity.getLocation().clone().subtract(0, 1, 0));
        }
        if (entity instanceof TNTPrimed) {
            return isArenaSourceLocationAllowed(entity.getLocation());
        }
        if (entity.getType() == org.bukkit.entity.EntityType.TNT_MINECART) {
            return isArenaSourceLocationAllowed(entity.getLocation());
        }
        return footprint.maybeRelevant(entity.getLocation());
    }

    private boolean sourceTouchesArena(Entity entity) {
        if (entity == null) {
            return false;
        }
        DuelArenaFootprintService footprint = plugin.duelArenaFootprint();
        return footprint.maybeRelevant(entity.getLocation())
            || footprint.contains(entity.getLocation())
            || footprint.contains(entity.getLocation().clone().subtract(0, 1, 0));
    }

    private boolean isArenaSourceBlockAllowed(Block block) {
        if (block == null) {
            return false;
        }
        if (block.getType() != Material.TNT && block.getType() != Material.RESPAWN_ANCHOR) {
            return false;
        }
        return plugin.duelArenaFootprint().contains(block);
    }

    private boolean isArenaSourceLocationAllowed(Location location) {
        return plugin.duelArenaFootprint().contains(location);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
