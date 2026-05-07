package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.GameplayZone;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LiquidControlListener implements Listener {
    private final MaceGuardPlugin plugin;

    public LiquidControlListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        plugin.runtime().counters().liquidEvent();
        Block from = event.getBlock();
        Material type = from.getType();
        if (type != Material.WATER && type != Material.LAVA) {
            plugin.runtime().counters().liquidSkipped();
            return;
        }

        Block to = event.getToBlock();
        if (plugin.runtime().zoneRegistry().query(from.getLocation()).externallyManaged()
                || plugin.runtime().zoneRegistry().query(to.getLocation()).externallyManaged()) {
            plugin.runtime().counters().liquidSkipped();
            return;
        }
        Set<GameplayZone> candidates = new LinkedHashSet<>(plugin.runtime().zoneRegistry().confinedLiquidZonesAt(from.getLocation()));
        candidates.addAll(plugin.runtime().zoneRegistry().confinedLiquidZonesAt(to.getLocation()));
        for (GameplayZone zone : candidates) {
            boolean fromInside = zone.region().contains(from.getLocation());
            boolean toInside = zone.region().contains(to.getLocation());
            if (fromInside != toInside) {
                event.setCancelled(true);
                return;
            }
        }

        if (type == Material.WATER) {
            List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(to.getLocation());
            if (zones.stream().anyMatch(GameplayZone::blockInfiniteSources) && wouldFormInfiniteSource(to)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean wouldFormInfiniteSource(Block target) {
        int sources = 0;
        sources += sourceWater(target.getRelative(1, 0, 0)) ? 1 : 0;
        if (sources >= 2) return true;
        sources += sourceWater(target.getRelative(-1, 0, 0)) ? 1 : 0;
        if (sources >= 2) return true;
        sources += sourceWater(target.getRelative(0, 0, 1)) ? 1 : 0;
        if (sources >= 2) return true;
        sources += sourceWater(target.getRelative(0, 0, -1)) ? 1 : 0;
        return sources >= 2;
    }

    private boolean sourceWater(Block block) {
        return block.getType() == Material.WATER && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }
}
