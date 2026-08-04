package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public final class CobwebListener implements Listener {
    private final WorldGuardQueryService worldGuard;
    private final WarzoneModule warzone;
    private final TemporaryBlockService temporary;
    private final MaceGuardConfig config;
    private final BlockPolicyResolver policies;

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone,
                          TemporaryBlockService temporary, MaceGuardConfig config) {
        this(worldGuard, warzone, temporary, config,
                new BlockPolicyResolver(config, worldGuard));
    }

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone,
                          TemporaryBlockService temporary, MaceGuardConfig config,
                          BlockPolicyResolver policies) {
        this.worldGuard = worldGuard;
        this.warzone = warzone;
        this.temporary = temporary;
        this.config = config;
        this.policies = policies;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRestriction(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return;
        var location = event.getBlockPlaced().getLocation();

        BlockPolicyResolver.Resolution policy = policies.resolve(location);
        if (policy.referenced()) {
            boolean allowed = policyTemporaryAllowed(policy,
                    worldGuard.buildAllowed(location, event.getPlayer()),
                    worldGuard.cobwebsAllowed(location, event.getPlayer()));
            if (!allowed || !replacementAllowed(config, event.getBlockReplacedState().getType()))
                event.setCancelled(true);
            return;
        }

        if (!warzone.appliesAt(location)) return;
        var decision = warzone.cobwebDecision(event.getPlayer(), location);
        boolean allowed = worldGuard.buildAllowed(location, event.getPlayer())
                && worldGuard.cobwebsAllowed(location, event.getPlayer())
                && worldGuard.warzoneCobwebsAllowed(location)
                && decision.allowed();
        if (allowed && !replacementAllowed(config, event.getBlockReplacedState().getType()))
            allowed = false;
        if (allowed) return;
        event.setCancelled(true);
        if (!decision.allowed()) warzone.sendCobwebDenial(event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!handlersEnabled(config.enabled(), config.validSchema())) return;

        var location = event.getBlockPlaced().getLocation();
        BlockPolicyResolver.Resolution policy = policies.resolve(location);
        boolean policyOverride = policyTemporaryAllowed(policy,
                worldGuard.buildAllowed(location, event.getPlayer()),
                worldGuard.cobwebsAllowed(location, event.getPlayer()));
        boolean warzoneApplies = warzone.appliesAt(location);
        WarzoneRuntime.CobwebDecision decision = null;

        if (policy.referenced()) {
            if (!policyOverride) return;
        } else {
            decision = warzone.cobwebDecision(event.getPlayer(), location);
            if (!warzoneApplies
                    || !worldGuard.buildAllowed(location, event.getPlayer())
                    || !worldGuard.cobwebsAllowed(location, event.getPlayer())
                    || !worldGuard.warzoneCobwebsAllowed(location)
                    || !decision.allowed()) return;
        }
        if (!replacementAllowed(config, event.getBlockReplacedState().getType())) {
            rollbackUnmanagedPlacement(event);
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
            return;
        }
        boolean tracked = temporary.track(event.getBlockPlaced(), original, expiresAt,
                warzoneApplies && !policyOverride);
        if (!tracked) {
            rollbackUnmanagedPlacement(event);
            return;
        }
        if (decision != null) warzone.successfulCobweb(event.getPlayer(), decision.restriction());
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
