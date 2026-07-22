package com.lincoln.maceguard.end;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

/** Global End weapon policy only. WorldGuard remains responsible for blocks, interactions, and explosions. */
public final class EndRestrictionListener implements Listener {
    private final MaceGuardPlugin plugin;
    public EndRestrictionListener(MaceGuardPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeaponDamage(EntityDamageByEntityEvent event) {
        EndIslandSettings settings = settings();
        if (settings == null || !(event.getDamager() instanceof Player player) || !inside(event.getEntity().getLocation(), settings)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if ((settings.blockMaces() && isMace(held)) || (settings.blockSpears() && isSpear(held))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpearUse(PlayerInteractEvent event) {
        EndIslandSettings settings = settings();
        if (settings != null && settings.blockSpears() && isSpear(event.getItem()) && inside(event.getPlayer().getLocation(), settings)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpearLaunch(ProjectileLaunchEvent event) {
        EndIslandSettings settings = settings();
        ProjectileSource source = event.getEntity().getShooter();
        if (settings != null && settings.blockSpears() && source instanceof Player player && inside(player.getLocation(), settings)
                && (Compat.isSpearEntity(event.getEntityType().name()) || isSpear(player.getInventory().getItemInMainHand()) || isSpear(player.getInventory().getItemInOffHand()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpearLunge(PlayerRiptideEvent event) {
        EndIslandSettings settings = settings();
        if (settings != null && settings.blockSpears() && isSpear(event.getItem()) && inside(event.getPlayer().getLocation(), settings)) event.getPlayer().setVelocity(event.getPlayer().getVelocity().zero());
    }

    private EndIslandSettings settings() { return plugin.isFeatureEnabled() && plugin.runtime().settings().endIsland().enabled() ? plugin.runtime().settings().endIsland() : null; }
    private boolean inside(Location location, EndIslandSettings settings) {
        if (location == null || location.getWorld() == null || location.getWorld().getEnvironment() != World.Environment.THE_END) return false;
        return location.getX() * location.getX() + location.getZ() * location.getZ() <= (double) settings.islandRadius() * settings.islandRadius();
    }
    private boolean isMace(ItemStack item) { return item != null && item.getType() != Material.AIR && Compat.isMace(item.getType()); }
    private boolean isSpear(ItemStack item) { return item != null && Compat.isSpear(item.getType()); }
}
