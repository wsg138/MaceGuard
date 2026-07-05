package com.lincoln.maceguard.bootstrap;

import com.lincoln.maceguard.config.PluginSettings;
import com.lincoln.maceguard.core.service.EndAccessService;
import com.lincoln.maceguard.core.service.PerformanceCounters;
import com.lincoln.maceguard.core.service.SnapshotService;
import com.lincoln.maceguard.core.service.ZoneRegistry;
import com.lincoln.maceguard.core.service.ZoneStateService;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;

@SuppressWarnings("PMD.DoNotUseThreads")
public record PluginRuntime(
        PluginSettings settings,
        ZoneRegistry zoneRegistry,
        ZoneStateService zoneStateService,
        SnapshotService snapshotService,
        EndAccessService endAccessService,
        PerformanceCounters counters,
        ExecutorService ioExecutor,
        BukkitTask resetTicker,
        BukkitTask backstopTicker,
        BukkitTask debugTicker
) {

    public void shutdownForReload() {
        resetTicker.cancel();
        if (backstopTicker != null) {
            backstopTicker.cancel();
        }
        if (debugTicker != null) {
            debugTicker.cancel();
        }
        snapshotService.cancelAll();
        zoneStateService.onReloadCleanup();
        snapshotService.shutdownExecutorGracefully();
    }

    public void shutdownForDisable() {
        resetTicker.cancel();
        if (backstopTicker != null) {
            backstopTicker.cancel();
        }
        if (debugTicker != null) {
            debugTicker.cancel();
        }
        snapshotService.cancelAll();
        zoneStateService.onDisableCleanup();
        snapshotService.shutdownExecutorGracefully();
    }
}
