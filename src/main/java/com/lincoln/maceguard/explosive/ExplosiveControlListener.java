package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;

/** Enforces the effective maceguard-explosives DENY flag without changing WorldGuard build policy. */
public final class ExplosiveControlListener implements Listener {
    private final MaceGuardPlugin plugin;
    private final WorldGuardQueryService worldGuard;

    public ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard) { this.plugin = plugin; this.worldGuard = worldGuard; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.TNT && denied(event.getBlockPlaced().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        EntityType type = event.getEntityType();
        if ((type == EntityType.END_CRYSTAL || type == EntityType.TNT_MINECART)
                && denied(event.getEntity().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntMinecartCreate(VehicleCreateEvent event) {
        if (event.getVehicle().getType() == EntityType.TNT_MINECART && denied(event.getVehicle().getLocation(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnchorUse(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR
                && denied(event.getClickedBlock().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (event.getEntityType() == EntityType.END_CRYSTAL && denied(event.getEntity().getLocation(), player(event.getDamager()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (isWindCharge(event.getEntityType())) return;
        if (denied(event.getEntity().getLocation(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (isWindCharge(event.getEntityType())) return;
        if (denied(event.getLocation(), null)) event.setCancelled(true);
        else event.blockList().removeIf(block -> denied(block.getLocation(), null));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (denied(event.getBlock().getLocation(), null)) event.setCancelled(true);
        else event.blockList().removeIf(block -> denied(block.getLocation(), null));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (isWindCharge(event.getDamageSource().getDirectEntity())) return;
        if ((event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)
                && denied(event.getEntity().getLocation(), null)) event.setCancelled(true);
    }

    static boolean isWindCharge(EntityType type) {
        return type == EntityType.WIND_CHARGE || type == EntityType.BREEZE_WIND_CHARGE;
    }

    private static boolean isWindCharge(Entity entity) {
        return entity != null && isWindCharge(entity.getType());
    }

    private boolean denied(org.bukkit.Location location, Player player) { return plugin.isFeatureEnabled() && worldGuard.explosivesDenied(location, player); }
    private Player player(org.bukkit.entity.Entity entity) { return entity instanceof Player value ? value : null; }
}
