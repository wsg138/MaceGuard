package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
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

import java.util.Locale;

public final class BlockPolicyListener implements Listener {
    private final MaceGuardConfig config;
    private final WorldGuardQueryService worldGuard;

    public BlockPolicyListener(MaceGuardConfig config, WorldGuardQueryService worldGuard) {
        this.config = config;
        this.worldGuard = worldGuard;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Resolution resolution = resolve(event.getBlockPlaced().getLocation());
        if (!resolution.referenced()) return;
        if (resolution.policy() == null
                || !resolution.policy().place().allows(event.getBlockPlaced().getType()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Resolution resolution = resolve(event.getBlock().getLocation());
        if (!resolution.referenced()) return;
        if (resolution.policy() == null
                || !resolution.policy().breakRule().allows(event.getBlock().getType()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        Resolution resolution = resolve(target.getLocation());
        if (!resolution.referenced()) return;
        Material fluid = fluid(event.getBucket());
        if (resolution.policy() == null
                || !resolution.policy().buckets().empty().contains(fluid))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block source = event.getBlockClicked();
        Resolution resolution = resolve(source.getLocation());
        if (!resolution.referenced()) return;
        Material fluid = source.getType();
        if (resolution.policy() == null
                || !resolution.policy().buckets().fill().contains(fluid))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Resolution source = resolve(event.getBlock().getLocation());
        Resolution target = resolve(event.getToBlock().getLocation());

        if (source.referenced()) {
            if (source.policy() == null) {
                event.setCancelled(true);
                return;
            }
            if (source.policy().liquids().confineToRegion()
                    && !samePolicy(source, target)) {
                event.setCancelled(true);
                return;
            }
        }
        if (target.referenced()) {
            if (target.policy() == null) {
                event.setCancelled(true);
                return;
            }
            if (!samePolicy(source, target)
                    && target.policy().liquids().confineToRegion()) {
                event.setCancelled(true);
                return;
            }
            if (target.policy().liquids().blockInfiniteWaterSources()
                    && event.getBlock().getType() == Material.WATER
                    && createsInfiniteSource(event.getToBlock())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIceMelt(BlockFadeEvent event) {
        Resolution resolution = resolve(event.getBlock().getLocation());
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
        for (Block moved : event.getBlocks()) {
            if (blocksAutomation(moved.getLocation())
                    || blocksAutomation(moved.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block moved : event.getBlocks()) {
            if (blocksAutomation(moved.getLocation())
                    || blocksAutomation(moved.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
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

    public Resolution resolve(Location location) {
        String name = worldGuard.effectiveBlockPolicy(location);
        if (name == null || name.isBlank()) return Resolution.none();
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return new Resolution(normalized, config.blockPolicies().get(normalized), true);
    }

    private boolean blocksAutomation(Location location) {
        Resolution resolution = resolve(location);
        return resolution.referenced()
                && (resolution.policy() == null || !resolution.policy().allowNonPlayerSources());
    }

    private boolean samePolicy(Resolution first, Resolution second) {
        return first.referenced() && second.referenced()
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

    public record Resolution(String name, BlockPolicy policy, boolean referenced) {
        public static Resolution none() { return new Resolution("", null, false); }
    }
}
