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
        Block from = event.getBlock();
        Material type = from.getType();
        if (type != Material.WATER && type != Material.LAVA) {
            return;
        }

        Block to = event.getToBlock();
        for (GameplayZone zone : plugin.runtime().zoneRegistry().allGameplayZones()) {
            if (!zone.confineLiquids()) {
                continue;
            }
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
        for (Block neighbor : List.of(
                target.getRelative(1, 0, 0),
                target.getRelative(-1, 0, 0),
                target.getRelative(0, 0, 1),
                target.getRelative(0, 0, -1))) {
            if (neighbor.getType() == Material.WATER && neighbor.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0) {
                sources++;
                if (sources >= 2) {
                    return true;
                }
            }
        }
        return false;
    }
}
