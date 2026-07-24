package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.integration.WarzoneRotatorAdapter;
import com.lincoln.maceguard.runtime.RuntimeSafetyPolicy;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.worldguard.CustomBehaviorDecision;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public final class CobwebListener implements Listener {
    private final WorldGuardQueryService worldGuard;
    private final WarzoneRotatorAdapter rotator;
    private final TemporaryBlockService temporary;
    private final MaceGuardConfig config;

    public CobwebListener(WorldGuardQueryService worldGuard, WarzoneRotatorAdapter rotator, TemporaryBlockService temporary, MaceGuardConfig config) {
        this.worldGuard = worldGuard; this.rotator = rotator; this.temporary = temporary; this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.COBWEB) return;
        if (!RuntimeSafetyPolicy.allowsTemporaryTracking(config.enabled())) return;
        if (!CustomBehaviorDecision.enabled(worldGuard.buildAllowed(event.getBlockPlaced().getLocation(), event.getPlayer()),
                worldGuard.cobwebsAllowed(event.getBlockPlaced().getLocation(), event.getPlayer()),
                rotator.allows(event.getPlayer(), event.getBlockPlaced().getLocation()))) return;
        String original = event.getBlockReplacedState().getBlockData().getAsString(true);
        if (!config.temporary().replacements().contains(event.getBlockReplacedState().getType().name())) return;
        temporary.track(event.getBlockPlaced(), original, System.currentTimeMillis() + config.temporary().cobwebTtlSeconds() * 1000L);
    }
}
