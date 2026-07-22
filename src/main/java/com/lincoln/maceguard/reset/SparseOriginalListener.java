package com.lincoln.maceguard.reset;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public final class SparseOriginalListener implements Listener {
    private final ResetCoordinator coordinator;
    public SparseOriginalListener(ResetCoordinator coordinator) { this.coordinator = coordinator; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (coordinator.prepareSparseOriginal(event.getBlockPlaced().getLocation(), event.getBlockReplacedState(), event.getPlayer()::sendMessage))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (coordinator.prepareSparseOriginal(event.getBlock().getLocation(), event.getBlock().getState(false), event.getPlayer()::sendMessage))
            event.setCancelled(true);
    }
}
