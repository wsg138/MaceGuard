package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.GameplayZone;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

public final class BuildProtectionListener implements Listener {
    private static final Set<EntityType> MINECART_TYPES = Set.of(
            EntityType.MINECART,
            EntityType.TNT_MINECART,
            EntityType.CHEST_MINECART,
            EntityType.FURNACE_MINECART,
            EntityType.HOPPER_MINECART,
            EntityType.COMMAND_BLOCK_MINECART
    );

    private final MaceGuardPlugin plugin;

    public BuildProtectionListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(block.getLocation());

        if (!zones.isEmpty()) {
            if (isDeniedSpecial(block.getType(), zones) && !creativeBypass(player)) {
                event.setCancelled(true);
                return;
            }
            if (!zones.stream().allMatch(GameplayZone::allowAllPlace)) {
                boolean allowed = zones.stream().anyMatch(zone -> zone.allowAllPlace() || zone.allowedPlace().contains(block.getType().name()));
                if (!allowed && !creativeBypass(player)) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else if (plugin.runtime().zoneRegistry().isProtected(block.getLocation()) && !creativeBypass(player)) {
            event.setCancelled(true);
            return;
        }

        trackPlacement(block, zones);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(block.getLocation());

        if (!zones.isEmpty()) {
            boolean allowed = zones.stream().allMatch(zone -> zone.allowAllBreak() || zone.allowAllPlace() || zone.allowedPlace().contains(block.getType().name()));
            if (!allowed && !creativeBypass(player)) {
                event.setCancelled(true);
                return;
            }
        } else if (plugin.runtime().zoneRegistry().isProtected(block.getLocation()) && !creativeBypass(player)) {
            event.setCancelled(true);
            return;
        }

        for (GameplayZone zone : zones) {
            plugin.runtime().zoneStateService().markChanged(zone.name(), block);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(target.getLocation());
        if (zones.isEmpty()) {
            if (plugin.runtime().zoneRegistry().isProtected(target.getLocation()) && !creativeBypass(event.getPlayer())) {
                event.setCancelled(true);
            }
            return;
        }

        Material placed = switch (event.getBucket()) {
            case WATER_BUCKET -> Material.WATER;
            case LAVA_BUCKET -> Material.LAVA;
            default -> null;
        };
        if (placed == null) {
            return;
        }
        if (!zones.stream().allMatch(GameplayZone::allowAllPlace)) {
            boolean allowed = zones.stream().anyMatch(zone -> zone.allowAllPlace() || zone.allowedPlace().contains(placed.name()));
            if (!allowed && !creativeBypass(event.getPlayer())) {
                event.setCancelled(true);
                return;
            }
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> trackPlacement(target, zones));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        BlockFace face = event.getBlock().getBlockData() instanceof Directional directional ? directional.getFacing() : BlockFace.NORTH;
        Block target = event.getBlock().getRelative(face);
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(target.getLocation());
        if (zones.isEmpty()) {
            if (plugin.runtime().zoneRegistry().isProtected(target.getLocation())) {
                event.setCancelled(true);
            }
            return;
        }

        String materialName = switch (event.getItem().getType()) {
            case WATER_BUCKET -> Material.WATER.name();
            case LAVA_BUCKET -> Material.LAVA.name();
            default -> event.getItem().getType().name();
        };

        if (!zones.stream().allMatch(GameplayZone::allowAllPlace)
                && zones.stream().noneMatch(zone -> zone.allowAllPlace() || zone.allowedPlace().contains(materialName))) {
            event.setCancelled(true);
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> trackPlacement(target, zones));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpecialInteract(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || (item.getType() != Material.END_CRYSTAL && item.getType() != Material.RESPAWN_ANCHOR)) {
            return;
        }
        Block target = event.getClickedBlock() != null ? event.getClickedBlock().getRelative(event.getBlockFace()) : event.getPlayer().getLocation().getBlock();
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(target.getLocation());
        if (zones.isEmpty()) {
            if (plugin.runtime().zoneRegistry().isProtected(target.getLocation()) && !creativeBypass(event.getPlayer())) {
                event.setCancelled(true);
            }
            return;
        }
        if (isDeniedSpecial(item.getType(), zones) && !creativeBypass(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (!MINECART_TYPES.contains(event.getVehicle().getType())) {
            return;
        }
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(event.getVehicle().getLocation());
        if (zones.isEmpty()) {
            if (plugin.runtime().zoneRegistry().isProtected(event.getVehicle().getLocation())) {
                event.getVehicle().remove();
            }
            return;
        }
        boolean denied = zones.stream().anyMatch(zone ->
                zone.denyPlace().contains("MINECART")
                        || zone.denyPlace().contains("TNT_MINECART")
                        || zone.denyPlace().contains("CHEST_MINECART")
                        || zone.denyPlace().contains("FURNACE_MINECART")
                        || zone.denyPlace().contains("HOPPER_MINECART")
                        || zone.denyPlace().contains("COMMAND_BLOCK_MINECART"));
        if (denied) {
            event.getVehicle().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtendGuard(BlockPistonExtendEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        for (Block block : event.getBlocks()) {
            if (!canMoveBlock(block, block.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetractGuard(BlockPistonRetractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        for (Block block : event.getBlocks()) {
            if (!canMoveBlock(block, block.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        markChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        markChanged(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        for (Block block : event.getBlocks()) {
            markChanged(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        for (Block block : event.getBlocks()) {
            markChanged(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        markChanged(event.getBlock());
    }

    private void trackPlacement(Block block, List<GameplayZone> zones) {
        for (GameplayZone zone : zones) {
            plugin.runtime().zoneStateService().markChanged(zone.name(), block);
        }
        int ttl = zones.stream().mapToInt(GameplayZone::ttlSeconds).max().orElse(0);
        if (ttl > 0 && shouldTtlClear(block.getType(), zones)) {
            plugin.runtime().zoneStateService().scheduleTemporaryClear(block, ttl, zones);
        }
    }

    private void markChanged(Block block) {
        for (GameplayZone zone : plugin.runtime().zoneRegistry().highestPriorityZonesAt(block.getLocation())) {
            plugin.runtime().zoneStateService().markChanged(zone.name(), block);
            if (zone.ttlSeconds() > 0 && (block.getType() == Material.WATER || block.getType() == Material.LAVA)) {
                plugin.runtime().zoneStateService().markTemporary(block);
            }
        }
    }

    private boolean creativeBypass(Player player) {
        return player != null && player.getGameMode() == GameMode.CREATIVE && player.hasPermission("maceguard.edit");
    }

    private boolean canMoveBlock(Block source, Block target) {
        String materialName = source.getType().name();

        List<GameplayZone> sourceZones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(source.getLocation());
        if (!sourceZones.isEmpty()) {
            boolean canBreak = sourceZones.stream().allMatch(zone ->
                    zone.allowAllBreak() || zone.allowAllPlace() || zone.allowedPlace().contains(materialName));
            if (!canBreak) {
                return false;
            }
        } else if (plugin.runtime().zoneRegistry().isProtected(source.getLocation())) {
            return false;
        }

        List<GameplayZone> targetZones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(target.getLocation());
        if (!targetZones.isEmpty()) {
            if (isDeniedSpecial(source.getType(), targetZones)) {
                return false;
            }
            if (!targetZones.stream().allMatch(GameplayZone::allowAllPlace)) {
                return targetZones.stream().anyMatch(zone -> zone.allowAllPlace() || zone.allowedPlace().contains(materialName));
            }
            return true;
        }

        return !plugin.runtime().zoneRegistry().isProtected(target.getLocation());
    }

    private boolean isDeniedSpecial(Material material, List<GameplayZone> zones) {
        String materialName = material.name();
        return zones.stream().anyMatch(zone -> zone.denyPlace().contains(materialName));
    }

    private boolean shouldTtlClear(Material material, List<GameplayZone> zones) {
        for (GameplayZone zone : zones) {
            if (zone.ttlSeconds() <= 0) {
                continue;
            }
            if (zone.allowAllPlace() || zone.allowedPlace().contains(material.name())) {
                return true;
            }
        }
        return false;
    }
}
