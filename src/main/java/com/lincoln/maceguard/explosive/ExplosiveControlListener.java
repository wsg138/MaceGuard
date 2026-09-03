package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/** Enforces explosive controls and the narrowly-scoped Warzone carts grant. */
public final class ExplosiveControlListener implements Listener {
    private static final Set<Material> CART_RAILS = EnumSet.of(
            Material.RAIL,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL);

    private final MaceGuardPlugin plugin;
    private final WorldGuardQueryService worldGuard;
    private final Predicate<Entity> windBurstSource;

    public ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard) {
        this(plugin, worldGuard, ExplosiveControlListener::isWindBurstSource);
    }

    ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard,
                             Predicate<Entity> windBurstSource) {
        this.plugin = plugin;
        this.worldGuard = worldGuard;
        this.windBurstSource = windBurstSource;
    }

    /**
     * WorldGuard represents its region decisions as delegate events. Pre-allowing those delegates
     * is deliberately narrower than uncancelling the original Bukkit event: RegionProtectionListener
     * stands down, while WorldGuard blacklist/build-permission listeners and unrelated plugins still
     * retain their own vetoes.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartBlockPlace(
            com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent event) {
        if (event.getOriginalEvent() instanceof BlockPlaceEvent original) {
            if (original.isCancelled()) return;
            Location location = original.getBlockPlaced().getLocation();
            if (isCartRail(original.getBlockPlaced().getType()) && cartModifierActive(location)
                    && !worldGuard.blockPlaceAllowed(location, original.getPlayer())) {
                event.setAllowed(true);
            }
            return;
        }
        if (event.getOriginalEvent() instanceof BlockIgniteEvent original) {
            if (original.isCancelled()
                    || original.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                    || original.getPlayer() == null) return;
            Location location = original.getBlock().getLocation();
            if (cartModifierActive(location)
                    && !worldGuard.lighterAllowed(location, original.getPlayer())) {
                event.setAllowed(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartUseBlock(
            com.sk89q.worldguard.bukkit.event.block.UseBlockEvent event) {
        if (event.getOriginalEvent() instanceof PlayerInteractEvent original)
            preAllowWorldGuardCartInteraction(event, original);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartUseItem(
            com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent event) {
        if (event.getOriginalEvent() instanceof PlayerInteractEvent original)
            preAllowWorldGuardCartInteraction(event, original);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartSpawn(
            com.sk89q.worldguard.bukkit.event.entity.SpawnEntityEvent event) {
        if (event.getEffectiveType() != EntityType.TNT_MINECART) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null || !cartModifierActive(event.getTarget())) return;
        if (!worldGuard.vehiclePlaceAllowed(event.getTarget(), player)) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartUseEntity(
            com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent event) {
        if (event.getEntity().getType() != EntityType.TNT_MINECART
                || !(event.getOriginalEvent() instanceof PlayerInteractEntityEvent original)
                || original.isCancelled() || !cartModifierActive(event.getTarget())) return;
        EquipmentSlot hand = original.getHand();
        ItemStack item = hand == EquipmentSlot.HAND
                ? original.getPlayer().getInventory().getItemInMainHand()
                : original.getPlayer().getInventory().getItemInOffHand();
        if (item.getType() == Material.FLINT_AND_STEEL
                && !worldGuard.lighterAllowed(event.getTarget(), original.getPlayer())) {
            event.setAllowed(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartDamageEntity(
            com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent event) {
        if (event.getEntity().getType() != EntityType.TNT_MINECART
                || !(event.getOriginalEvent() instanceof VehicleDamageEvent original)
                || original.isCancelled()
                || !(original.getAttacker() instanceof Player player)
                || !cartModifierActive(event.getTarget())) return;
        if (!worldGuard.vehicleDestroyAllowed(event.getTarget(), player)) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartDestroyEntity(
            com.sk89q.worldguard.bukkit.event.entity.DestroyEntityEvent event) {
        if (event.getEntity().getType() != EntityType.TNT_MINECART
                || !(event.getOriginalEvent() instanceof VehicleDestroyEvent original)
                || original.isCancelled()
                || !(original.getAttacker() instanceof Player player)
                || !cartModifierActive(event.getTarget())) return;
        if (!worldGuard.vehicleDestroyAllowed(event.getTarget(), player)) event.setAllowed(true);
    }

    private void preAllowWorldGuardCartInteraction(
            com.sk89q.worldguard.bukkit.event.DelegateEvent delegate,
            PlayerInteractEvent original) {
        if (original.isCancelled()) return;
        ItemStack item = original.getItem();
        Block clicked = original.getClickedBlock();
        if (item == null || clicked == null) return;

        if (item.getType() == Material.TNT_MINECART && isCartRail(clicked.getType())
                && cartModifierActive(clicked.getLocation())
                && !worldGuard.vehiclePlaceAllowed(clicked.getLocation(), original.getPlayer())) {
            delegate.setAllowed(true);
            return;
        }

        Location fire = clicked.getRelative(original.getBlockFace()).getLocation();
        if (item.getType() == Material.FLINT_AND_STEEL && cartModifierActive(fire)
                && !worldGuard.lighterAllowed(fire, original.getPlayer())) {
            delegate.setAllowed(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.TNT
                && denied(event.getBlockPlaced().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        EntityType type = event.getEntityType();
        if (type == EntityType.TNT_MINECART && event.getPlayer() != null
                && cartModifierActive(event.getEntity().getLocation())) {
            // WorldGuard's region grant is handled before its own delegate decision. Never clear a
            // cancellation that reaches this Bukkit event because it can belong to another plugin.
            return;
        }
        if (event.isCancelled()) return;
        if ((type == EntityType.END_CRYSTAL || type == EntityType.TNT_MINECART)
                && denied(event.getEntity().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartCreate(VehicleCreateEvent event) {
        if (event.getVehicle().getType() != EntityType.TNT_MINECART) return;
        if (cartModifierActive(event.getVehicle().getLocation())) return;
        if (!event.isCancelled() && denied(event.getVehicle().getLocation(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCartFireSpread(BlockSpreadEvent event) {
        Material source = event.getSource().getType();
        if ((source == Material.FIRE || source == Material.SOUL_FIRE)
                && (cartModifierActive(event.getSource().getLocation())
                || cartModifierActive(event.getBlock().getLocation()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCartFireBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (cartModifierActive(event.getBlock().getLocation())
                || source != null && cartModifierActive(source.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnchorUse(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR
                && denied(event.getClickedBlock().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (event.getEntityType() == EntityType.END_CRYSTAL
                && denied(event.getEntity().getLocation(), player(event.getDamager()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrime(ExplosionPrimeEvent event) {
        // Carts bypass MaceGuard's generic explosives deny, but they do not override any cancellation
        // already applied by WorldGuard global settings or another protection plugin.
        if (isCartExplosion(event.getEntity(), event.getEntity().getLocation())) return;
        if (event.isCancelled()) return;
        if (isWindCharge(event.getEntityType()) || windBurstSource.test(event.getEntity())) return;
        if (denied(event.getEntity().getLocation(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (isCartExplosion(event.getEntity(), event.getLocation())) {
            if (!event.isCancelled()) event.blockList().clear();
            return;
        }
        if (event.isCancelled()) return;
        if (isWindCharge(event.getEntityType()) || windBurstSource.test(event.getEntity())) return;
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
        Entity direct = event.getDamageSource().getDirectEntity();
        if (isWindCharge(direct) || windBurstSource.test(direct) || cartExplosionSource(direct)) return;
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

    static boolean isWindBurstSource(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        var held = player.getInventory().getItemInMainHand();
        return isWindBurstMace(held.getType(), held.containsEnchantment(Enchantment.WIND_BURST));
    }

    static boolean isWindBurstMace(Material material, boolean hasWindBurst) {
        return material == Material.MACE && hasWindBurst;
    }

    static boolean isCartRail(Material material) {
        return CART_RAILS.contains(material);
    }

    private boolean cartExplosionSource(Entity entity) {
        return entity != null && isCartExplosion(entity, entity.getLocation());
    }

    private boolean isCartExplosion(Entity entity, Location location) {
        return entity != null && entity.getType() == EntityType.TNT_MINECART
                && cartModifierActive(location);
    }

    private boolean cartModifierActive(Location location) {
        if (!plugin.isFeatureEnabled()) return false;
        var pluginRuntime = plugin.runtime();
        if (pluginRuntime == null || pluginRuntime.warzone() == null) return false;
        WarzoneRuntime warzoneRuntime = pluginRuntime.warzone().runtime();
        return warzoneRuntime != null && warzoneRuntime.appliesAt(location)
                && warzoneRuntime.rotations().active().cartsAllowed();
    }

    private boolean denied(Location location, Player player) {
        return plugin.isFeatureEnabled() && worldGuard.explosivesDenied(location, player);
    }

    private Player player(Entity entity) {
        return entity instanceof Player value ? value : null;
    }
}
