package com.lincoln.maceguard.bootstrap;

import com.lincoln.maceguard.config.PluginSettings;
import com.lincoln.maceguard.core.service.EndAccessService;
import com.lincoln.maceguard.core.service.SnapshotService;
import com.lincoln.maceguard.core.service.ZoneRegistry;
import com.lincoln.maceguard.core.service.ZoneStateService;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;

public record PluginRuntime(
        PluginSettings settings,
        ZoneRegistry zoneRegistry,
        ZoneStateService zoneStateService,
        SnapshotService snapshotService,
        EndAccessService endAccessService,
        ExecutorService ioExecutor,
        BukkitTask resetTicker
) {

    public void shutdownForReload() {
        resetTicker.cancel();
        snapshotService.cancelAll();
        zoneStateService.onReloadCleanup();
        ioExecutor.shutdownNow();
    }

    public void shutdownForDisable() {
        resetTicker.cancel();
        snapshotService.cancelAll();
        zoneStateService.onDisableCleanup();
        ioExecutor.shutdownNow();
    }
}
