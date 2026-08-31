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
import org.bukkit.event.Event;
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
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/** Enforces explosive controls and the narrowly-scoped Warzone CARTS grant. */
public final class ExplosiveControlListener implements Listener {
    private static final Set<Material> CART_RAILS = EnumSet.of(
            Material.RAIL,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL);

    private final MaceGuardPlugin plugin;
    private final WorldGuardQueryService worldGuard;

    public ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard) {
        this.plugin = plugin;
        this.worldGuard = worldGuard;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartRailPlace(BlockPlaceEvent event) {
        if (isCartRail(event.getBlockPlaced().getType())
                && cartModifierActive(event.getBlockPlaced().getLocation())) {
            // CARTS is an explicit material-scoped positive grant. This intentionally opens only
            // the four rail blocks even when ordinary WorldGuard/MaceGuard building is denied.
            event.setCancelled(false);
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
            event.setCancelled(false);
            return;
        }
        if (event.isCancelled()) return;
        if ((type == EntityType.END_CRYSTAL || type == EntityType.TNT_MINECART)
                && denied(event.getEntity().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartCreate(VehicleCreateEvent event) {
        if (event.getVehicle().getType() != EntityType.TNT_MINECART) return;
        if (cartModifierActive(event.getVehicle().getLocation())) {
            event.setCancelled(false);
            return;
        }
        if (!event.isCancelled() && denied(event.getVehicle().getLocation(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartItemUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        Block clicked = event.getClickedBlock();
        if (item == null || clicked == null) return;

        if (item.getType() == Material.TNT_MINECART && isCartRail(clicked.getType())
                && cartModifierActive(clicked.getLocation())) {
            allowItemUse(event);
            return;
        }

        if (item.getType() == Material.FLINT_AND_STEEL) {
            Location fire = clicked.getRelative(event.getBlockFace()).getLocation();
            if (cartModifierActive(clicked.getLocation()) || cartModifierActive(fire)) {
                allowItemUse(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartEntityUse(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.TNT_MINECART
                || !cartModifierActive(event.getRightClicked().getLocation())) return;
        EquipmentSlot hand = event.getHand();
        ItemStack item = hand == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        if (item.getType() == Material.FLINT_AND_STEEL) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartDamage(VehicleDamageEvent event) {
        if (event.getVehicle().getType() == EntityType.TNT_MINECART
                && event.getAttacker() instanceof Player
                && cartModifierActive(event.getVehicle().getLocation())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle().getType() == EntityType.TNT_MINECART
                && event.getAttacker() instanceof Player
                && cartModifierActive(event.getVehicle().getLocation())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                && event.getPlayer() != null
                && cartModifierActive(event.getBlock().getLocation())) event.setCancelled(false);
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
        if (isCartExplosion(event.getEntity(), event.getEntity().getLocation())) {
            // CARTS deliberately bypasses WorldGuard/MaceGuard TNT-explosion denial for TNT
            // minecarts only. The later EntityExplodeEvent removes every block from destruction.
            event.setCancelled(false);
            return;
        }
        if (event.isCancelled()) return;
        if (isWindCharge(event.getEntityType()) || isWindBurstSource(event.getEntity())) return;
        if (denied(event.getEntity().getLocation(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (isCartExplosion(event.getEntity(), event.getLocation())) {
            event.setCancelled(false);
            event.blockList().clear();
            return;
        }
        if (event.isCancelled()) return;
        if (isWindCharge(event.getEntityType()) || isWindBurstSource(event.getEntity())) return;
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
        if (isWindCharge(direct) || isWindBurstSource(direct)
                || isCartExplosion(direct, direct == null ? event.getEntity().getLocation()
                : direct.getLocation())) return;
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

    /**
     * Wind Burst is implemented by vanilla as a post-attack explosion sourced from the attacker.
     * It is movement/enchantment behavior, not one of the destructive explosive mechanics governed
     * by maceguard-explosives, so do not cancel that player-sourced explosion merely because the
     * region denies TNT/crystals/anchors.
     */
    static boolean isWindBurstSource(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        var held = player.getInventory().getItemInMainHand();
        return held.getType() == Material.MACE
                && held.containsEnchantment(Enchantment.WIND_BURST);
    }

    static boolean isCartRail(Material material) {
        return CART_RAILS.contains(material);
    }

    private static void allowItemUse(PlayerInteractEvent event) {
        event.setCancelled(false);
        event.setUseItemInHand(Event.Result.ALLOW);
    }

    private boolean isCartExplosion(Entity entity, Location location) {
        return entity != null && entity.getType() == EntityType.TNT_MINECART
                && cartModifierActive(location);
    }

    private boolean cartModifierActive(Location location) {
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
