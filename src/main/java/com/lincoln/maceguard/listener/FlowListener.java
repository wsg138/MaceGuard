package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

public final class FlowListener implements Listener {
    private final MaceGuardPlugin plugin;
    private final BuildGuardListener buildGuard;

    public FlowListener(MaceGuardPlugin plugin, BuildGuardListener buildGuard) {
        this.plugin = plugin;
        this.buildGuard = buildGuard;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!plugin.buildBoxes().isEnabled() || !plugin.blockInfiniteSources()) return;

        Block to = event.getToBlock();
        // Only care inside our build boxes
        if (!plugin.buildBoxes().isInAny(to.getLocation())) return;

        // Only moderate water
        if (event.getBlock().getType() != Material.WATER) return;

        // Prevent forming infinite source
        if (wouldFormInfiniteSource(to)) {
            if (plugin.isDebug()) plugin.getLogger().info("[BuildBoxes] Prevented infinite water source at " + to.getLocation());
            event.setCancelled(true);
            return;
        }

        // Track water spread so it can be cleared later
        // Defer 1 tick to let the flow update the block
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (to.getType() == Material.WATER) {
                buildGuard.trackFlowAt(to.getLocation());
            }
        });
    }

    private boolean wouldFormInfiniteSource(Block to) {
        // Check 4 horizontal neighbors being source level (level 0)
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
