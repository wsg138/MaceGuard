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
    private static final int INFINITE_SOURCE_THRESHOLD = 2;
    private static final int[][] HORIZONTAL_OFFSETS = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 0, 1},
            {0, 0, -1}
    };

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
            skipLiquidEvent();
            return;
        }

        Block to = event.getToBlock();
        if (isExternallyManagedFlow(from, to)) {
            skipLiquidEvent();
            return;
        }
        if (crossesConfinedZoneBoundary(from, to)) {
            event.setCancelled(true);
            return;
        }

        if (type == Material.WATER && shouldBlockInfiniteSource(to)) {
            event.setCancelled(true);
        }
    }

    private void skipLiquidEvent() {
        plugin.runtime().counters().liquidSkipped();
    }

    private boolean isExternallyManagedFlow(Block from, Block to) {
        return plugin.runtime().zoneRegistry().query(from.getLocation()).externallyManaged()
                || plugin.runtime().zoneRegistry().query(to.getLocation()).externallyManaged();
    }

    private boolean crossesConfinedZoneBoundary(Block from, Block to) {
        for (GameplayZone zone : confinedCandidates(from, to)) {
            boolean fromInside = zone.region().contains(from.getLocation());
            boolean toInside = zone.region().contains(to.getLocation());
            if (fromInside != toInside) {
                return true;
            }
        }
        return false;
    }

    private Set<GameplayZone> confinedCandidates(Block from, Block to) {
        Set<GameplayZone> candidates = new LinkedHashSet<>(plugin.runtime().zoneRegistry().confinedLiquidZonesAt(from.getLocation()));
        candidates.addAll(plugin.runtime().zoneRegistry().confinedLiquidZonesAt(to.getLocation()));
        return candidates;
    }

    private boolean shouldBlockInfiniteSource(Block target) {
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(target.getLocation());
        return zones.stream().anyMatch(GameplayZone::blockInfiniteSources) && wouldFormInfiniteSource(target);
    }

    private boolean wouldFormInfiniteSource(Block target) {
        int sources = 0;
        for (int[] offset : HORIZONTAL_OFFSETS) {
            if (sourceWater(target.getRelative(offset[0], offset[1], offset[2]))) {
                sources++;
            }
            if (sources >= INFINITE_SOURCE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private boolean sourceWater(Block block) {
        return block.getType() == Material.WATER && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }
}
