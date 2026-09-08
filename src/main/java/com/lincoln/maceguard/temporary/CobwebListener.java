package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

public final class CobwebListener implements Listener {
    private static final String BLOCK_POLICY_BYPASS_PERMISSION = "maceguard.block-policy.bypass";
    private static final String TEMPORARY_COBWEB_BYPASS_PERMISSION =
            "maceguard.temporary-cobweb.bypass";

    private final WorldGuardQueryService worldGuard;
    private final WarzoneModule warzone;
    private final TemporaryBlockService temporary;
    private final MaceGuardConfig config;
    private final BlockPolicyResolver policies;
    private final TemporaryBlockAdmissionJournal admissions;

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone,
                          TemporaryBlockService temporary, MaceGuardConfig config) {
        this(worldGuard, warzone, temporary, config,
                new BlockPolicyResolver(config, worldGuard), null);
    }

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone,
                          TemporaryBlockService temporary, MaceGuardConfig config,
                          BlockPolicyResolver policies) {
        this(worldGuard, warzone, temporary, config, policies, null);
    }

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone,
                          TemporaryBlockService temporary, MaceGuardConfig config,
                          BlockPolicyResolver policies,
                          TemporaryBlockAdmissionJournal admissions) {
        this.worldGuard = worldGuard;
        this.warzone = warzone;
        this.temporary = temporary;
        this.config = config;
        this.policies = policies;
        this.admissions = admissions;
    }

    /**
     * Pre-allows only WorldGuard's region-protection decision for the active Warzone COBWEBS
     * modifier. Other WorldGuard safety listeners and every unrelated Bukkit protection plugin
     * still retain their own cancellation authority.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardWarzoneCobweb(
            com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent event) {
        if (!(event.getOriginalEvent() instanceof BlockPlaceEvent original)) return;
        if (original.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return;

        var location = original.getBlockPlaced().getLocation();
        if (policies.resolve(location).referenced() || !warzone.appliesAt(location)) return;
        boolean temporaryBypass = original.getPlayer().hasPermission(
                TEMPORARY_COBWEB_BYPASS_PERMISSION);
        var decision = warzone.cobwebDecision(original.getPlayer(), location);
        if (!temporaryBypass && !decision.allowed()) return;
        if (!worldGuard.warzoneCobwebsAllowed(location)
                || !replacementAllowed(config, original.getBlockReplacedState().getType())) return;

        if (!worldGuard.buildAllowed(location, original.getPlayer())) event.setAllowed(true);
    }

    /** Allows players to break only a durably tracked Warzone cobweb, including after reload. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardWarzoneCobwebBreak(
            com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent event) {
        if (!(event.getOriginalEvent() instanceof BlockBreakEvent original)) return;
        if (original.getBlock().getType() != Material.COBWEB) return;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return;
        if (!isTrackedWarzoneCobweb(original.getBlock())) return;
        if (!worldGuard.blockBreakAllowed(original.getBlock().getLocation(), original.getPlayer()))
            event.setAllowed(true);
    }

    /**
     * WorldGuard abstracts bucket-empty into both block-placement and item-use delegates. When a
     * player is physically trapped in one of our Warzone cobwebs, allow those two WorldGuard
     * delegates for a nearby water placement so vanilla water can free the player. The source must
     * be at or directly beside the trapped block; this is not a general water-placement bypass.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCobwebEscapePlace(
            com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent event) {
        if (event.getOriginalEvent() instanceof PlayerBucketEmptyEvent original
                && waterEscapeAllowed(original)) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCobwebEscapeItem(
            com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent event) {
        if (event.getOriginalEvent() instanceof PlayerBucketEmptyEvent original
                && waterEscapeAllowed(original)) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRestriction(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return;
        var location = event.getBlockPlaced().getLocation();
        boolean policyBypass = event.getPlayer().hasPermission(
                BLOCK_POLICY_BYPASS_PERMISSION);
        boolean temporaryBypass = event.getPlayer().hasPermission(
                TEMPORARY_COBWEB_BYPASS_PERMISSION);

        BlockPolicyResolver.Resolution policy = policies.resolve(location);
        if (policy.referenced()) {
            if (event.isCancelled()) return;
            boolean worldGuardAllowed = worldGuard.buildAllowed(location, event.getPlayer())
                    && worldGuard.cobwebsAllowed(location, event.getPlayer());
            boolean allowed = worldGuardAllowed && (policyBypass
                    || policyTemporaryAllowed(policy, true, true));
            if (!allowed || !replacementAllowed(config, event.getBlockReplacedState().getType())) {
                event.setCancelled(true);
                warzone.sendBlockPlaceDenied(event.getPlayer(), Material.COBWEB);
            }
            return;
        }

        if (!warzone.appliesAt(location)) return;
        if (event.isCancelled()) return;

        var decision = warzone.cobwebDecision(event.getPlayer(), location);
        boolean effectiveCobwebDenied = !temporaryBypass && !decision.allowed();
        boolean blockPlacementDenied =
                !worldGuard.warzoneCobwebsAllowed(location)
                || !replacementAllowed(config, event.getBlockReplacedState().getType());

        if (!effectiveCobwebDenied && !blockPlacementDenied) return;
        event.setCancelled(true);
        if (effectiveCobwebDenied) warzone.sendCobwebDenial(event.getPlayer(), decision);
        else warzone.sendBlockPlaceDenied(event.getPlayer(), Material.COBWEB);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return;

        var location = event.getBlockPlaced().getLocation();
        BlockPolicyResolver.Resolution policy = policies.resolve(location);
        boolean policyBypass = event.getPlayer().hasPermission(
                BLOCK_POLICY_BYPASS_PERMISSION);
        boolean temporaryBypass = event.getPlayer().hasPermission(
                TEMPORARY_COBWEB_BYPASS_PERMISSION);
        boolean worldGuardAllowed = worldGuard.buildAllowed(location, event.getPlayer())
                && worldGuard.cobwebsAllowed(location, event.getPlayer());
        boolean policyAllowed = worldGuardAllowed && (policyBypass
                || policyTemporaryAllowed(policy, true, true));
        boolean warzoneApplies = warzone.appliesAt(location);
        WarzoneRuntime.CobwebDecision decision = null;

        if (policy.referenced()) {
            if (!policyAllowed) return;
        } else {
            if (!warzoneApplies) return;
            decision = warzone.cobwebDecision(event.getPlayer(), location);
            if (!worldGuard.warzoneCobwebsAllowed(location)
                    || !temporaryBypass && !decision.allowed()) return;
        }
        if (!replacementAllowed(config, event.getBlockReplacedState().getType())) {
            rollbackUnmanagedPlacement(event);
            warzone.sendBlockPlaceDenied(event.getPlayer(), Material.COBWEB);
            return;
        }

        String original = event.getBlockReplacedState().getBlockData().getAsString(true);
        long expiresAt;
        try {
            expiresAt = Math.addExact(System.currentTimeMillis(),
                    warzone.cobwebLifetime(
                            java.time.Duration.ofSeconds(config.temporary().cobwebTtlSeconds()),
                            location).toMillis());
        } catch (ArithmeticException ex) {
            rollbackUnmanagedPlacement(event);
            warzone.sendBlockPlaceDenied(event.getPlayer(), Material.COBWEB);
            return;
        }
        boolean warzoneOwned = warzoneApplies && !policy.referenced();
        boolean tracked = admissions == null
                ? temporary.track(event.getBlockPlaced(), original, expiresAt, warzoneOwned)
                : admissions.track(temporary, event.getBlockPlaced(), original, expiresAt,
                        warzoneOwned);
        if (!tracked) {
            rollbackUnmanagedPlacement(event);
            warzone.sendBlockPlaceDenied(event.getPlayer(), Material.COBWEB);
            return;
        }
        if (decision != null && !temporaryBypass)
            warzone.successfulCobweb(event.getPlayer(), decision.restriction());
    }

    /** Drop the persistence record immediately so the coordinate can be reused without waiting TTL. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.COBWEB) return;
        temporary.discardMatching(entry -> entry.isKind(TemporaryBlock.Kind.COBWEB)
                && sameCoordinate(entry, event.getBlock()));
    }

    private boolean waterEscapeAllowed(PlayerBucketEmptyEvent event) {
        if (event.isCancelled() || event.getBucket() != Material.WATER_BUCKET) return false;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return false;

        Block clicked = event.getBlockClicked();
        Block target = clicked.getBlockData() instanceof Waterlogged
                ? clicked : clicked.getRelative(event.getBlockFace());
        if (policies.resolve(target.getLocation()).referenced()
                || !warzone.appliesAt(target.getLocation())) return false;

        Block feet = event.getPlayer().getLocation().getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        return isEscapePlacement(target, feet) || isEscapePlacement(target, head);
    }

    private boolean isEscapePlacement(Block target, Block trapped) {
        if (!isTrackedWarzoneCobweb(trapped)) return false;
        if (!target.getWorld().getUID().equals(trapped.getWorld().getUID())) return false;
        int distance = Math.abs(target.getX() - trapped.getX())
                + Math.abs(target.getY() - trapped.getY())
                + Math.abs(target.getZ() - trapped.getZ());
        return distance <= 1;
    }

    private boolean isTrackedWarzoneCobweb(Block block) {
        if (block.getType() != Material.COBWEB) return false;
        return temporary.countMatching(entry -> entry.warzoneOwned()
                && entry.isKind(TemporaryBlock.Kind.COBWEB)
                && sameCoordinate(entry, block)) > 0;
    }

    private static boolean sameCoordinate(TemporaryBlock entry, Block block) {
        return entry.worldUuid().equals(block.getWorld().getUID().toString())
                && entry.x() == block.getX() && entry.y() == block.getY()
                && entry.z() == block.getZ();
    }

    private static void rollbackUnmanagedPlacement(BlockPlaceEvent event) {
        event.setCancelled(true);
        if (event.getBlockPlaced().getType() == Material.COBWEB)
            event.getBlockPlaced().setBlockData(event.getBlockReplacedState().getBlockData(), false);
    }

    static boolean handlersEnabled(boolean enabled, boolean validSchema) {
        return enabled && validSchema;
    }

    static boolean replacementAllowed(MaceGuardConfig config, Material original) {
        return config.temporary().replacements().contains(original.name());
    }

    static boolean policyTemporaryAllowed(BlockPolicyResolver.Resolution policy,
                                          boolean buildAllowed, boolean cobwebFlagAllowed) {
        return policy.referenced() && policy.policy() != null
                && policy.policy().place().allows(Material.COBWEB)
                && buildAllowed && cobwebFlagAllowed;
    }
}
