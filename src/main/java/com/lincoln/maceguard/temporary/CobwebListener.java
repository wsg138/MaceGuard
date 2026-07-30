package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.runtime.RuntimeSafetyPolicy;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
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

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneModule warzone, TemporaryBlockService temporary, MaceGuardConfig config) {
        this.worldGuard = worldGuard; this.warzone = warzone; this.temporary = temporary; this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRestriction(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!RuntimeSafetyPolicy.allowsTemporaryTracking(config.enabled())) return;
        var location = event.getBlockPlaced().getLocation();
        if (!warzone.appliesAt(location)) return;
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
        boolean warzoneApplies = warzone.appliesAt(event.getBlockPlaced().getLocation());
        var decision = warzone.cobwebDecision(event.getPlayer(), event.getBlockPlaced().getLocation());
        if (!worldGuard.buildAllowed(event.getBlockPlaced().getLocation(), event.getPlayer())
                || !worldGuard.cobwebsAllowed(event.getBlockPlaced().getLocation(), event.getPlayer())
                || (warzoneApplies && (!worldGuard.warzoneCobwebsAllowed(event.getBlockPlaced().getLocation())
                || !decision.allowed()))) return;
        if (warzoneApplies) warzone.successfulCobweb(event.getPlayer(), decision.restriction());
        String original = event.getBlockReplacedState().getBlockData().getAsString(true);
        if (!config.temporary().replacements().contains(event.getBlockReplacedState().getType().name())) return;
        long expiresAt = Math.addExact(System.currentTimeMillis(),
                warzone.cobwebLifetime(java.time.Duration.ofSeconds(config.temporary().cobwebTtlSeconds()),
                        event.getBlockPlaced().getLocation()).toMillis());
        temporary.track(event.getBlockPlaced(), original, expiresAt);
    }
}
