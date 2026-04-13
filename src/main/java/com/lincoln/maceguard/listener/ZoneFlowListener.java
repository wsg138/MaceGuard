package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.region.Zone;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

import java.util.List;

public final class ZoneFlowListener implements Listener {
    private final MaceGuardPlugin plugin;
    @SuppressWarnings("unused")
    private final ZoneGuardListener guard;

    public ZoneFlowListener(MaceGuardPlugin plugin, ZoneGuardListener guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();

        Material type = from.getType();
        if (type != Material.WATER && type != Material.LAVA) return;

        // GEOMETRY-BASED two-way confinement:
        // For any zone with confine_liquids, if flow crosses that cuboid boundary (either direction), cancel.
        for (Zone z : plugin.gameplay().getZones()) {
            if (!z.confineLiquids) continue;
            boolean fromIn = z.cuboid.contains(from.getLocation());
            boolean toIn   = z.cuboid.contains(to.getLocation());
            if (fromIn != toIn) { // crossing boundary of a confining zone
                event.setCancelled(true);
                return;
            }
        }

        // Infinite source prevention in the target highest-priority zones
        if (type == Material.WATER) {
            List<Zone> toZones = plugin.gameplay().zonesAt(to.getLocation());
            boolean blockSources = false;
            for (Zone z : toZones) {
                if (z.blockInfiniteSources) { blockSources = true; break; }
            }
            if (blockSources && wouldFormInfiniteSource(to)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean wouldFormInfiniteSource(Block to) {
        int sources = 0;
        Block[] neighbors = new Block[] {
                to.getRelative(1, 0, 0),
                to.getRelative(-1, 0, 0),
                to.getRelative(0, 0, 1),
                to.getRelative(0, 0, -1)
        };
        for (Block n : neighbors) {
            if (n.getType() == Material.WATER && n.getBlockData() instanceof Levelled l && l.getLevel() == 0) {
                sources++;
                if (sources >= 2) return true;
            }
        }
        return false;
    }
}
