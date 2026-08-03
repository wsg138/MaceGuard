package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.runtime.RuntimeSafetyPolicy;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Locale;

public final class CobwebListener implements Listener {
    private final WorldGuardQueryService worldGuard;
    private final WarzoneModule warzone;
    private final TemporaryBlockService temporary;
    private final MaceGuardConfig config;

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone,
                          TemporaryBlockService temporary, MaceGuardConfig config) {
        this.worldGuard = worldGuard;
        this.warzone = warzone;
        this.temporary = temporary;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRestriction(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!RuntimeSafetyPolicy.allowsTemporaryTracking(config.enabled())) return;
        var location = event.getBlockPlaced().getLocation();

        PolicyDecision policy = policy(location);
        if (policy.referenced()) {
            boolean allowed = worldGuard.buildAllowed(location, event.getPlayer())
                    && worldGuard.cobwebsAllowed(location, event.getPlayer())
                    && policy.policy() != null
                    && policy.policy().place().allows(Material.COBWEB);
            if (!allowed) event.setCancelled(true);
            return;
        }

        boolean exactWarzone = warzone.appliesAt(location);
        if (!exactWarzone) return;
        var decision = warzone.cobwebDecision(event.getPlayer(), location);
        boolean allowed = worldGuard.buildAllowed(location, event.getPlayer())
                && worldGuard.cobwebsAllowed(location, event.getPlayer())
                && worldGuard.warzoneCobwebsAllowed(location)
                && decision.allowed();
        if (allowed) return;
        event.setCancelled(true);
        if (!decision.allowed()) warzone.sendCobwebDenial(event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!RuntimeSafetyPolicy.allowsTemporaryTracking(config.enabled())) return;
        var location = event.getBlockPlaced().getLocation();
        PolicyDecision policy = policy(location);
        boolean policyOverride = policy.referenced()
                && policy.policy() != null
                && policy.policy().place().allows(Material.COBWEB);
        boolean warzoneApplies = warzone.appliesAt(location);

        if (policy.referenced()) {
            if (!policyOverride
                    || !worldGuard.buildAllowed(location, event.getPlayer())
                    || !worldGuard.cobwebsAllowed(location, event.getPlayer())) return;
        } else {
            var decision = warzone.cobwebDecision(event.getPlayer(), location);
            if (!warzoneApplies
                    || !worldGuard.buildAllowed(location, event.getPlayer())
                    || !worldGuard.cobwebsAllowed(location, event.getPlayer())
                    || !worldGuard.warzoneCobwebsAllowed(location)
                    || !decision.allowed()) return;
            warzone.successfulCobweb(event.getPlayer(), decision.restriction());
        }

        String original = event.getBlockReplacedState().getBlockData().getAsString(true);
        if (!config.temporary().replacements()
                .contains(event.getBlockReplacedState().getType().name())) return;
        long expiresAt = Math.addExact(System.currentTimeMillis(),
                warzone.cobwebLifetime(
                        java.time.Duration.ofSeconds(config.temporary().cobwebTtlSeconds()),
                        location).toMillis());
        temporary.track(event.getBlockPlaced(), original, expiresAt,
                warzoneApplies && !policyOverride);
    }

    private PolicyDecision policy(org.bukkit.Location location) {
        String name = worldGuard.effectiveBlockPolicy(location);
        if (name == null || name.isBlank()) return PolicyDecision.none();
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return new PolicyDecision(true, config.blockPolicies().get(normalized));
    }

    private record PolicyDecision(boolean referenced, BlockPolicy policy) {
        static PolicyDecision none() { return new PolicyDecision(false, null); }
    }
}
