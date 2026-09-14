package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

public final class BlockPolicyListener implements Listener {
    private static final String BYPASS_PERMISSION = "maceguard.block-policy.bypass";

    private final BlockPolicyResolver resolver;
    private final WarzoneModule warzone;
    private final BiPredicate<Location, Player> globalLighterBlocked;
    private final Set<BlockIgniteEvent> cartIgniteClearBeforeWorldGuard =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public BlockPolicyListener(MaceGuardConfig config, WorldGuardQueryService worldGuard) {
        this(new BlockPolicyResolver(config, worldGuard), null);
    }

    public BlockPolicyListener(BlockPolicyResolver resolver) {
        this(resolver, null);
    }

    public BlockPolicyListener(BlockPolicyResolver resolver, WarzoneModule warzone) {
        this(resolver, warzone, BlockPolicyListener::worldGuardGlobalLighterBlocked);
    }

    BlockPolicyListener(BlockPolicyResolver resolver, WarzoneModule warzone,
                        BiPredicate<Location, Player> globalLighterBlocked) {
        this.resolver = resolver;
        this.warzone = warzone;
        this.globalLighterBlocked = globalLighterBlocked;
    }

    /**
     * WorldGuard translates bucket-empty and liquid-flow Bukkit events into PlaceBlockEvent
     * delegates before this listener's Bukkit handlers run. Pre-allow only the delegate when the
     * effective MaceGuard policy explicitly permits the operation; blacklist/build-permission
     * listeners and unrelated plugins retain their own vetoes.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardPolicyPlace(
            com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent event) {
        if (event.getOriginalEvent() instanceof PlayerBucketEmptyEvent original) {
            if (original.isCancelled()) return;
            Block target = original.getBlock();
            Material fluid = fluid(original.getBucket());
            BlockPolicyResolver.Resolution resolution = resolve(target.getLocation());
            if (resolution.referenced() && bucketEmptyAllowed(resolution, fluid))
                event.setAllowed(true);
            return;
        }
        if (!(event.getOriginalEvent() instanceof BlockFromToEvent original)
                || original.isCancelled()) return;
        Block sourceBlock = original.getBlock();
        Block targetBlock = original.getToBlock();
        BlockPolicyResolver.Resolution source = resolve(sourceBlock.getLocation());
        BlockPolicyResolver.Resolution target = resolve(targetBlock.getLocation());
        if (!source.referenced() && !target.referenced()) return;
        boolean createsInfiniteWater = sourceBlock.getType() == Material.WATER
                && createsInfiniteSource(targetBlock);
        if (!flowDenied(source, target, createsInfiniteWater)) event.setAllowed(true);
    }

    /** Same scoped exception for WorldGuard's bucket-fill BreakBlockEvent delegate. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardPolicyBreak(
            com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent event) {
        if (!(event.getOriginalEvent() instanceof PlayerBucketFillEvent original)
                || original.isCancelled()) return;
        Block source = original.getBlock();
        BlockPolicyResolver.Resolution resolution = resolve(source.getLocation());
        if (resolution.referenced() && bucketFillAllowed(resolution, source.getType()))
            event.setAllowed(true);
    }

    /**
     * Snapshot whether a CARTS flint-and-steel ignition was still clear before WorldGuard's
     * HIGH-priority global block-lighter hook runs. This is used only to reopen that one known
     * WorldGuard veto, never a cancellation that already existed beforehand.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onCartIgniteBeforeWorldGuard(BlockIgniteEvent event) {
        if (!cartFlintIgnition(event)) return;
        if (event.isCancelled()) cartIgniteClearBeforeWorldGuard.remove(event);
        else cartIgniteClearBeforeWorldGuard.add(event);
    }

    /**
     * WorldGuard is a hard dependency and registers before MaceGuard. Its global block-lighter
     * check is HIGH priority, so this HIGH handler runs after it. Region lighter/build decisions
     * remain handled by the CARTS delegate grant in ExplosiveControlListener, while unrelated
     * HIGHEST/MONITOR protection plugins can still veto the ignition afterward.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onCartIgniteWorldGuardBypass(BlockIgniteEvent event) {
        if (!cartFlintIgnition(event)) {
            cartIgniteClearBeforeWorldGuard.remove(event);
            return;
        }
        boolean wasClear = cartIgniteClearBeforeWorldGuard.remove(event);
        if (event.isCancelled() && wasClear
                && globalLighterBlocked.test(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(false);
        }
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
        Block target = event.getBlock();
        Material fluid = fluid(event.getBucket());
        if (bucketEmptyAllowed(resolve(target.getLocation()), fluid)) return;
        event.setCancelled(true);
        if (warzone != null) warzone.sendBucketEmptyDenied(event.getPlayer(), fluid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (event.getPlayer().hasPermission(BYPASS_PERMISSION)) return;
        Block source = event.getBlock();
        if (bucketFillAllowed(resolve(source.getLocation()), source.getType())) return;
        event.setCancelled(true);
        if (warzone != null) warzone.sendBucketFillDenied(event.getPlayer(), source.getType());
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

    private boolean cartFlintIgnition(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        return player != null && event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                && cartsActiveAt(event.getBlock().getLocation());
    }

    private boolean cartsActiveAt(Location location) {
        if (warzone == null || warzone.runtime() == null) return false;
        var runtime = warzone.runtime();
        return runtime.appliesAt(location) && runtime.rotations().active().cartsAllowed();
    }

    private static boolean worldGuardGlobalLighterBlocked(Location location, Player player) {
        if (location.getWorld() == null) return false;
        var global = WorldGuard.getInstance().getPlatform().getGlobalStateManager();
        if (global.activityHaltToggle) return false;
        return global.get(BukkitAdapter.adapt(location.getWorld())).blockLighter
                && !WorldGuardPlugin.inst().hasPermission(player, "worldguard.override.lighter");
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
