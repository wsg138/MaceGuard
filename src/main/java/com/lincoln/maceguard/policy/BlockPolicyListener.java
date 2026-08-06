package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BlockPolicyListener implements Listener {
    private static final String BYPASS_PERMISSION = "warzonerotator.bypass";

    private final BlockPolicyResolver resolver;
    private final WarzoneModule warzone;

    public BlockPolicyListener(MaceGuardConfig config, WorldGuardQueryService worldGuard) {
        this(new BlockPolicyResolver(config, worldGuard), null);
    }

    public BlockPolicyListener(BlockPolicyResolver resolver) {
        this(resolver, null);
    }

    public BlockPolicyListener(BlockPolicyResolver resolver, WarzoneModule warzone) {
        this.resolver = resolver;
        this.warzone = warzone;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_PERMISSION)) return;
        if (placeAllowed(resolve(event.getBlockPlaced().getLocation()),
                event.getBlockPlaced().getType())) return;
        event.setCancelled(true);
        if (warzone != null) warzone.sendBlockPlaceDenied(event.getPlayer(),
                event.getBlockPlaced().getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_PERMISSION)) return;
        if (breakAllowed(resolve(event.getBlock().getLocation()),
                event.getBlock().getType())) return;
        event.setCancelled(true);
        if (warzone != null) warzone.sendBlockBreakDenied(event.getPlayer(),
                event.getBlock().getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_PERMISSION)) return;
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        Material fluid = fluid(event.getBucket());
        if (bucketEmptyAllowed(resolve(target.getLocation()), fluid)) return;
        event.setCancelled(true);
        if (warzone != null) warzone.sendBucketUseDenied(event.getPlayer(), fluid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_PERMISSION)) return;
        Block source = event.getBlockClicked();
        if (bucketFillAllowed(resolve(source.getLocation()), source.getType())) return;
        event.setCancelled(true);
        if (warzone != null) warzone.sendBucketUseDenied(event.getPlayer(), source.getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        BlockPolicyResolver.Resolution source = resolve(event.getBlock().getLocation());
        BlockPolicyResolver.Resolution target = resolve(event.getToBlock().getLocation());
        boolean createsInfiniteWater = event.getBlock().getType() == Material.WATER
                && createsInfiniteSource(event.getToBlock());
        if (flowDenied(source, target, createsInfiniteWater)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIceMelt(BlockFadeEvent event) {
        BlockPolicyResolver.Resolution resolution = resolve(event.getBlock().getLocation());
        if (!resolution.referenced()) return;
        if (resolution.policy() == null) {
            event.setCancelled(true);
            return;
        }
        if (event.getBlock().getType() == Material.ICE
                && event.getNewState().getType() == Material.WATER
                && !resolution.policy().buckets().empty().contains(Material.WATER))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonTouchesPolicy(event.getBlock(), event.getDirection(), event.getBlocks()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonTouchesPolicy(event.getBlock(), event.getDirection(), event.getBlocks()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        BlockFace face = event.getBlock().getBlockData() instanceof Directional directional
                ? directional.getFacing() : BlockFace.SELF;
        Location target = event.getBlock().getRelative(face).getLocation();
        if (blocksAutomation(event.getBlock().getLocation()) || blocksAutomation(target))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (blocksAutomation(event.getBlock().getLocation())) event.setCancelled(true);
    }

    public BlockPolicyResolver.Resolution resolve(Location location) {
        return resolver.resolve(location);
    }

    private boolean pistonTouchesPolicy(Block piston, BlockFace direction,
                                         Iterable<? extends Block> movedBlocks) {
        Map<BlockKey, Boolean> cache = new HashMap<>();
        if (blocksAutomation(piston.getLocation(), cache)
                || blocksAutomation(piston.getRelative(direction).getLocation(), cache)) return true;
        BlockFace opposite = direction.getOppositeFace();
        for (Block moved : movedBlocks) {
            if (blocksAutomation(moved.getLocation(), cache)
                    || blocksAutomation(moved.getRelative(direction).getLocation(), cache)
                    || blocksAutomation(moved.getRelative(opposite).getLocation(), cache)) return true;
        }
        return false;
    }

    private boolean blocksAutomation(Location location, Map<BlockKey, Boolean> cache) {
        UUID worldId = location.getWorld() == null ? null : location.getWorld().getUID();
        BlockKey key = new BlockKey(worldId, location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
        return cache.computeIfAbsent(key, ignored -> automationDenied(resolve(location)));
    }

    private boolean blocksAutomation(Location location) {
        return automationDenied(resolve(location));
    }

    static boolean placeAllowed(BlockPolicyResolver.Resolution resolution, Material material) {
        return !resolution.referenced() || resolution.policy() != null
                && resolution.policy().place().allows(material);
    }

    static boolean breakAllowed(BlockPolicyResolver.Resolution resolution, Material material) {
        return !resolution.referenced() || resolution.policy() != null
                && resolution.policy().breakRule().allows(material);
    }

    static boolean bucketEmptyAllowed(BlockPolicyResolver.Resolution resolution, Material fluid) {
        return !resolution.referenced() || resolution.policy() != null
                && resolution.policy().buckets().empty().contains(fluid);
    }

    static boolean bucketFillAllowed(BlockPolicyResolver.Resolution resolution, Material fluid) {
        return !resolution.referenced() || resolution.policy() != null
                && resolution.policy().buckets().fill().contains(fluid);
    }

    static boolean flowDenied(BlockPolicyResolver.Resolution source,
                              BlockPolicyResolver.Resolution target,
                              boolean createsInfiniteWaterSource) {
        if (source.referenced()) {
            if (source.policy() == null) return true;
            if (source.policy().liquids().confineToRegion() && !samePolicyScope(source, target))
                return true;
        }
        if (target.referenced()) {
            if (target.policy() == null) return true;
            if (target.policy().liquids().confineToRegion() && !samePolicyScope(source, target))
                return true;
            if (target.policy().liquids().blockInfiniteWaterSources()
                    && createsInfiniteWaterSource) return true;
        }
        return false;
    }

    static boolean automationDenied(BlockPolicyResolver.Resolution resolution) {
        return resolution.referenced()
                && (resolution.policy() == null || !resolution.policy().allowNonPlayerSources());
    }

    static boolean samePolicyScope(BlockPolicyResolver.Resolution first,
                                   BlockPolicyResolver.Resolution second) {
        return first.referenced() && second.referenced()
                && first.scopeId().equals(second.scopeId())
                && first.name().equals(second.name())
                && first.policy() != null && second.policy() != null;
    }

    private boolean createsInfiniteSource(Block destination) {
        if (destination.getType() != Material.AIR
                && destination.getType() != Material.WATER) return false;
        int sources = 0;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            Block adjacent = destination.getRelative(face);
            if (adjacent.getType() == Material.WATER
                    && adjacent.getBlockData() instanceof org.bukkit.block.data.Levelled level
                    && level.getLevel() == 0
                    && ++sources >= 2) return true;
        }
        return false;
    }

    private Material fluid(Material bucket) {
        if (bucket == Material.WATER_BUCKET) return Material.WATER;
        if (bucket == Material.LAVA_BUCKET) return Material.LAVA;
        return bucket;
    }

    private record BlockKey(UUID worldId, int x, int y, int z) { }
}
