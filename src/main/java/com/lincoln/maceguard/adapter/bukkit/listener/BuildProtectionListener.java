package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.CobwebPolicy;
import com.lincoln.maceguard.core.model.BlockKey;
import com.lincoln.maceguard.core.service.ZoneRegistry;
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
import org.bukkit.event.block.BlockDropItemEvent;
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
    private static final Set<String> DENIED_MINECART_KEYS = Set.of(
            "MINECART",
            "TNT_MINECART",
            "CHEST_MINECART",
            "FURNACE_MINECART",
            "HOPPER_MINECART",
            "COMMAND_BLOCK_MINECART"
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
        PluginRuntime runtime = plugin.runtime();
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        if (handleDuelArenaExplosivePlacement(event, player, block)) {
            return;
        }
        ZoneRegistry.ZoneQuery query = runtime.zoneRegistry().query(block.getLocation());
        if (query.externallyManaged()) {
            return;
        }
        List<GameplayZone> zones = query.highestZones();

        if (placementDenied(query, block.getType(), player, block.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (player.hasPermission("maceguard.permanent-edit")) {
            if (!runtime.zoneStateService().preservePermanentEdit(zones, block)) {
                event.setCancelled(true);
                return;
            }
        } else if (!runtime.zoneStateService().captureSparseOriginals(zones, BlockKey.of(block), event.getBlockReplacedState().getBlockData().getAsString(true))) {
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
        PluginRuntime runtime = plugin.runtime();
        Player player = event.getPlayer();
        Block block = event.getBlock();
        ZoneRegistry.ZoneQuery query = runtime.zoneRegistry().query(block.getLocation());
        if (query.externallyManaged()) {
            return;
        }
        List<GameplayZone> zones = query.highestZones();

        if (breakDenied(query, block, player)) {
            event.setCancelled(true);
            return;
        }
        if (player.hasPermission("maceguard.permanent-edit")) {
            if (!runtime.zoneStateService().preservePermanentEdit(zones, block)) {
                event.setCancelled(true);
                return;
            }
        } else if (!runtime.zoneStateService().captureSparseOriginals(zones, block)) {
            event.setCancelled(true);
            return;
        }

        for (GameplayZone zone : zones) {
            plugin.runtime().zoneStateService().markChanged(zone.name(), block);
        }
        if (shouldSuppressSnapshotDrops(block, zones)) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
        if (plugin.runtime().zoneStateService().isPlaced(block)) {
            plugin.runtime().zoneStateService().forgetPlacedAfterDrops(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDropItems(BlockDropItemEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        ZoneRegistry.ZoneQuery query = plugin.runtime().zoneRegistry().query(event.getBlockState().getLocation());
        if (query.externallyManaged()) {
            return;
        }
        if (shouldSuppressSnapshotDrops(event.getBlockState().getBlock(), query.highestZones())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        ZoneRegistry.ZoneQuery query = plugin.runtime().zoneRegistry().query(target.getLocation());
        if (query.externallyManaged()) {
            return;
        }
        List<GameplayZone> zones = query.highestZones();
        if (zones.isEmpty()) {
            if (query.protectedRegion() && !creativeBypass(event.getPlayer())) {
                event.setCancelled(true);
            }
            return;
        }
        Material placed = placedBucketMaterial(event.getBucket());
        if (placed == null) {
            return;
        }

        if (placementDenied(query, placed, event.getPlayer(), target.getLocation())) {
            event.setCancelled(true);
            return;
        }

        scheduleTrackPlacement(target, zones);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        BlockFace face = event.getBlock().getBlockData() instanceof Directional directional ? directional.getFacing() : BlockFace.NORTH;
        Block target = event.getBlock().getRelative(face);
        ZoneRegistry.ZoneQuery query = plugin.runtime().zoneRegistry().query(target.getLocation());
        if (query.externallyManaged()) {
            return;
        }
        List<GameplayZone> zones = query.highestZones();
        String materialName = switch (event.getItem().getType()) {
            case WATER_BUCKET -> Material.WATER.name();
            case LAVA_BUCKET -> Material.LAVA.name();
            default -> event.getItem().getType().name();
        };

        if (placementDenied(query, materialName)) {
            event.setCancelled(true);
            return;
        }

        scheduleTrackPlacement(target, zones);
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
        if (item == null) {
            return;
        }
        if (handleDuelArenaExplosiveInteract(event, item)) {
            return;
        }
        if (item.getType() != Material.END_CRYSTAL && item.getType() != Material.RESPAWN_ANCHOR) {
            return;
        }
        Block target = event.getClickedBlock() != null ? event.getClickedBlock().getRelative(event.getBlockFace()) : event.getPlayer().getLocation().getBlock();
        ZoneRegistry.ZoneQuery query = plugin.runtime().zoneRegistry().query(target.getLocation());
        if (query.externallyManaged()) {
            return;
        }
        if (placementDenied(query, item.getType(), event.getPlayer(), target.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (shouldRemoveDuelArenaTntMinecart(event)) {
            event.getVehicle().remove();
            return;
        }
        if (!MINECART_TYPES.contains(event.getVehicle().getType())) {
            return;
        }
        ZoneRegistry.ZoneQuery query = plugin.runtime().zoneRegistry().query(event.getVehicle().getLocation());
        if (query.externallyManaged()) {
            return;
        }
        if (minecartDenied(query)) {
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
        if (plugin.runtime().zoneRegistry().isExternallyManaged(block.getLocation())) {
            return;
        }
        for (GameplayZone zone : zones) {
            plugin.runtime().zoneStateService().markChanged(zone.name(), block);
        }
        if (!zones.isEmpty()) {
            plugin.runtime().zoneStateService().markPlaced(block);
        }
        int ttl = zones.stream().mapToInt(GameplayZone::ttlSeconds).max().orElse(0);
        if (ttl > 0 && shouldTtlClear(block.getType(), zones)) {
            plugin.runtime().zoneStateService().scheduleTemporaryClear(block, ttl, zones);
        }
    }

    private boolean shouldSuppressSnapshotDrops(Block block, List<GameplayZone> zones) {
        return !zones.isEmpty()
                && zones.stream().anyMatch(GameplayZone::suppressSnapshotDrops)
                && !plugin.runtime().zoneStateService().isPlaced(block);
    }

    private void markChanged(Block block) {
        if (plugin.runtime().zoneRegistry().isExternallyManaged(block.getLocation())) {
            return;
        }
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

    private boolean placementDenied(ZoneRegistry.ZoneQuery query, Material material, Player player, org.bukkit.Location location) {
        if (query.highestZones().isEmpty()) {
            return query.protectedRegion() && !creativeBypass(player);
        }
        if (!creativeBypass(player) && material == Material.COBWEB && delegatedCobwebs(query.highestZones())) {
            return plugin.warzoneRotatorHook() == null || !plugin.warzoneRotatorHook().canPlace(player, location);
        }
        return !creativeBypass(player) && (isDeniedSpecial(material, query.highestZones()) || !canPlaceMaterial(query.highestZones(), material.name()));
    }

    private boolean placementDenied(ZoneRegistry.ZoneQuery query, String materialName) {
        if (query.highestZones().isEmpty()) {
            return query.protectedRegion();
        }
        return !canPlaceMaterial(query.highestZones(), materialName);
    }

    private boolean breakDenied(ZoneRegistry.ZoneQuery query, Block block, Player player) {
        if (query.highestZones().isEmpty()) {
            return query.protectedRegion() && !creativeBypass(player);
        }
        if (creativeBypass(player)) {
            return false;
        }
        if (block.getType() == Material.COBWEB && delegatedCobwebs(query.highestZones())) {
            return plugin.warzoneRotatorHook() == null || !plugin.warzoneRotatorHook().isTracked(block);
        }
        return !canBreakMaterial(query.highestZones(), block.getType().name())
                && !(block.isReplaceable() && query.highestZones().stream().allMatch(GameplayZone::allowBreakReplaceable));
    }

    private boolean canPlaceMaterial(List<GameplayZone> zones, String materialName) {
        return zones.stream().allMatch(GameplayZone::allowAllPlace)
                || zones.stream().anyMatch(zone -> zone.allowAllPlace() || zone.allowedPlace().contains(materialName));
    }

    private boolean canBreakMaterial(List<GameplayZone> zones, String materialName) {
        return zones.stream().allMatch(zone -> zone.allowAllBreak() || zone.allowAllPlace() || zone.allowedBreak().contains(materialName));
    }

    private boolean delegatedCobwebs(List<GameplayZone> zones) {
        return !zones.isEmpty() && zones.stream().allMatch(zone -> zone.cobwebPolicy() == CobwebPolicy.WARZONE_ROTATOR);
    }

    private Material placedBucketMaterial(Material bucket) {
        return switch (bucket) {
            case WATER_BUCKET -> Material.WATER;
            case LAVA_BUCKET -> Material.LAVA;
            default -> null;
        };
    }

    private void scheduleTrackPlacement(Block target, List<GameplayZone> zones) {
        plugin.getServer().getScheduler().runTask(plugin, () -> trackPlacement(target, zones));
    }

    private boolean shouldRemoveDuelArenaTntMinecart(VehicleCreateEvent event) {
        return event.getVehicle().getType() == EntityType.TNT_MINECART
                && plugin.duelArenaFootprint().maybeRelevant(event.getVehicle().getLocation())
                && (!plugin.warzoneDuelsHook().hasActiveDuel() || !plugin.duelArenaFootprint().contains(event.getVehicle().getLocation()));
    }

    private boolean minecartDenied(ZoneRegistry.ZoneQuery query) {
        if (query.highestZones().isEmpty()) {
            return query.protectedRegion();
        }
        return query.highestZones().stream().anyMatch(zone -> containsDeniedMinecart(zone.denyPlace()));
    }

    private boolean containsDeniedMinecart(Set<String> denyPlace) {
        for (String minecartKey : DENIED_MINECART_KEYS) {
            if (denyPlace.contains(minecartKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean canMoveBlock(Block source, Block target) {
        if (plugin.runtime().zoneRegistry().isExternallyManaged(source.getLocation())
                || plugin.runtime().zoneRegistry().isExternallyManaged(target.getLocation())) {
            return true;
        }
        String materialName = source.getType().name();

        if (!canPistonBreakSource(source, materialName)) {
            return false;
        }

        return canPistonPlaceTarget(source, target, materialName);
    }

    private boolean canPistonBreakSource(Block source, String materialName) {
        List<GameplayZone> sourceZones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(source.getLocation());
        if (sourceZones.isEmpty()) {
            return !plugin.runtime().zoneRegistry().isProtected(source.getLocation());
        }
        return sourceZones.stream().allMatch(zone ->
                zone.allowAllBreak() || zone.allowAllPlace() || zone.allowedPlace().contains(materialName));
    }

    private boolean canPistonPlaceTarget(Block source, Block target, String materialName) {
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
        for (GameplayZone zone : zones) {
            if (zone.denyPlace().contains(materialName)) {
                return true;
            }
        }
        if (materialName.equals("RESPAWN_ANCHOR")) {
            return plugin.runtime().settings().protection().denyRespawnAnchor();
        }
        if (materialName.equals("END_CRYSTAL")) {
            return plugin.runtime().settings().protection().denyEndCrystal();
        }
        return false;
    }

    private boolean handleDuelArenaExplosivePlacement(BlockPlaceEvent event, Player player, Block block) {
        Material type = block.getType();
        if (type != Material.TNT && type != Material.RESPAWN_ANCHOR) {
            return false;
        }
        if (!plugin.duelArenaFootprint().maybeRelevant(block.getLocation())) {
            return false;
        }
        if (shouldCancelDuelArenaExplosive(player, block)) {
            event.setCancelled(true);
        }
        return true;
    }

    private boolean handleDuelArenaExplosiveInteract(PlayerInteractEvent event, ItemStack item) {
        Player player = event.getPlayer();
        Material type = item.getType();
        Block clicked = event.getClickedBlock();

        if (type == Material.END_CRYSTAL) {
            Block support = clicked != null ? clicked : player.getLocation().getBlock();
            return handleDuelArenaExplosiveInteract(event, player, support);
        }

        if (type == Material.RESPAWN_ANCHOR) {
            Block target = clicked != null ? clicked.getRelative(event.getBlockFace()) : player.getLocation().getBlock();
            return handleDuelArenaExplosiveInteract(event, player, target);
        }

        if (type == Material.TNT_MINECART) {
            Block rail = clicked != null ? clicked : player.getLocation().getBlock();
            return handleDuelArenaExplosiveInteract(event, player, rail);
        }

        if (type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE) {
            if (clicked == null || clicked.getType() != Material.TNT || !plugin.duelArenaFootprint().maybeRelevant(clicked.getLocation())) {
                return false;
            }
            return handleDuelArenaExplosiveInteract(event, player, clicked);
        }

        return false;
    }

    private boolean handleDuelArenaExplosiveInteract(PlayerInteractEvent event, Player player, Block target) {
        if (!plugin.duelArenaFootprint().maybeRelevant(target.getLocation())) {
            return false;
        }
        if (shouldCancelDuelArenaExplosive(player, target)) {
            event.setCancelled(true);
        }
        return true;
    }

    private boolean shouldCancelDuelArenaExplosive(Player player, Block target) {
        return !plugin.warzoneDuelsHook().hasActiveDuel()
                || !plugin.warzoneDuelsHook().isActiveParticipant(player.getUniqueId())
                || !plugin.duelArenaFootprint().contains(target);
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
