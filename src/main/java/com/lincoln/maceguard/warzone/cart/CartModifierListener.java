package com.lincoln.maceguard.warzone.cart;

import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Narrow positive grant for the CARTS Warzone modifier. It deliberately does not grant general
 * building or block breaking: only rails, TNT minecarts, and player flint-and-steel ignition are
 * opened up. Fire propagation and fire block damage remain suppressed.
 */
public final class CartModifierListener implements Listener {
    private static final Set<Material> RAILS = EnumSet.of(
            Material.RAIL,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL);

    private final WarzoneModule warzone;

    public CartModifierListener(WarzoneModule warzone) {
        this.warzone = warzone;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRailPlace(BlockPlaceEvent event) {
        if (!isRail(event.getBlockPlaced().getType()) || !cartsAllowed(event.getBlockPlaced().getLocation())) return;
        // The modifier is an explicit, material-scoped grant. It may override WorldGuard/build or
        // MaceGuard block-place protection for these four rail blocks only.
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartItemUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        Block clicked = event.getClickedBlock();
        if (item == null || clicked == null) return;

        if (item.getType() == Material.TNT_MINECART && isRail(clicked.getType())
                && cartsAllowed(clicked.getLocation())) {
            allowItemUse(event);
            return;
        }

        if (item.getType() == Material.FLINT_AND_STEEL) {
            Location target = clicked.getRelative(event.getBlockFace()).getLocation();
            if (cartsAllowed(clicked.getLocation()) || cartsAllowed(target)) allowItemUse(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCartEntityUse(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.TNT_MINECART
                || !cartsAllowed(event.getRightClicked().getLocation())) return;
        EquipmentSlot hand = event.getHand();
        ItemStack item = hand == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        if (item.getType() == Material.FLINT_AND_STEEL) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartPlace(EntityPlaceEvent event) {
        if (event.getEntityType() == EntityType.TNT_MINECART
                && event.getPlayer() != null
                && cartsAllowed(event.getEntity().getLocation())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartCreate(VehicleCreateEvent event) {
        if (event.getVehicle().getType() == EntityType.TNT_MINECART
                && cartsAllowed(event.getVehicle().getLocation())) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartDamage(VehicleDamageEvent event) {
        if (event.getVehicle().getType() != EntityType.TNT_MINECART
                || !(event.getAttacker() instanceof Player)
                || !cartsAllowed(event.getVehicle().getLocation())) return;
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle().getType() != EntityType.TNT_MINECART
                || !(event.getAttacker() instanceof Player)
                || !cartsAllowed(event.getVehicle().getLocation())) return;
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFlintAndSteelIgnite(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                || event.getPlayer() == null || !cartsAllowed(event.getBlock().getLocation())) return;
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        Material source = event.getSource().getType();
        if (source != Material.FIRE && source != Material.SOUL_FIRE) return;
        if (cartsAllowed(event.getSource().getLocation())
                || cartsAllowed(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (cartsAllowed(event.getBlock().getLocation())
                || source != null && cartsAllowed(source.getLocation())) event.setCancelled(true);
    }

    static boolean isRail(Material material) {
        return RAILS.contains(material);
    }

    private static void allowItemUse(PlayerInteractEvent event) {
        event.setCancelled(false);
        event.setUseItemInHand(Event.Result.ALLOW);
    }

    private boolean cartsAllowed(Location location) {
        WarzoneRuntime runtime = warzone.runtime();
        return runtime != null && runtime.appliesAt(location)
                && runtime.rotations().active().cartsAllowed();
    }
}
