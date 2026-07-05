package com.lincoln.maceguard;

import com.lincoln.maceguard.adapter.bukkit.command.MaceGuardCommand;
import com.lincoln.maceguard.adapter.bukkit.listener.BuildProtectionListener;
import com.lincoln.maceguard.adapter.bukkit.listener.DuelArenaExplosiveListener;
import com.lincoln.maceguard.adapter.bukkit.listener.EndAccessListener;
import com.lincoln.maceguard.adapter.bukkit.listener.EndIslandListener;
import com.lincoln.maceguard.adapter.bukkit.listener.LiquidControlListener;
import com.lincoln.maceguard.adapter.bukkit.listener.MaceDurabilityListener;
import com.lincoln.maceguard.adapter.storage.FileSnapshotRepository;
import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.config.ConfigMigrator;
import com.lincoln.maceguard.config.PluginConfigLoader;
import com.lincoln.maceguard.config.PluginSettings;
import com.lincoln.maceguard.core.service.EndAccessService;
import com.lincoln.maceguard.core.service.DuelArenaFootprintService;
import com.lincoln.maceguard.core.service.PerformanceCounters;
import com.lincoln.maceguard.core.service.SnapshotService;
import com.lincoln.maceguard.core.service.ZoneRegistry;
import com.lincoln.maceguard.core.service.ZoneStateService;
import com.lincoln.maceguard.integration.WarzoneDuelsHook;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MaceGuardPlugin extends JavaPlugin {
    private PluginRuntime pluginRuntime;
    private DuelArenaFootprintService duelArenaFootprintService;
    private WarzoneDuelsHook duelsHook;

    @Override
    public void onEnable() {
        migrateConfig();
        saveBundledFootprint();
        bootstrapRuntime(null);

        Bukkit.getPluginManager().registerEvents(new BuildProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DuelArenaExplosiveListener(this), this);
        Bukkit.getPluginManager().registerEvents(new LiquidControlListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MaceDurabilityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EndAccessListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EndIslandListener(this), this);

        MaceGuardCommand command = new MaceGuardCommand(this);
        command.register();

        if (!Compat.isMaceSupported()) {
            getLogger().warning("Material.MACE is not available on this server build. Mace-specific logic will no-op.");
        }
    }

    @Override
    public void onDisable() {
        if (pluginRuntime != null) {
            pluginRuntime.shutdownForDisable();
            pluginRuntime = null;
        }
    }

    public void reloadPlugin() {
        ZoneStateService.ZoneStateSnapshot stateSnapshot = null;
        if (pluginRuntime != null) {
            stateSnapshot = pluginRuntime.zoneStateService().snapshotState();
            if (!pluginRuntime.settings().reload().preserveTemporaryBlocks()) {
                pluginRuntime.zoneStateService().clearTemporaryBlocksForReload();
            }
            getLogger().info("Reload preserving reset queue size " + pluginRuntime.zoneStateService().resetQueueSize()
                    + " and temporary blocks " + pluginRuntime.zoneStateService().temporaryBlockCount()
                    + " (preserve-temporary-blocks=" + pluginRuntime.settings().reload().preserveTemporaryBlocks() + ").");
            pluginRuntime.shutdownForReload();
        }
        migrateConfig();
        reloadConfig();
        bootstrapRuntime(stateSnapshot);
    }

    public void toggleDebug() {
        boolean next = !getConfig().getBoolean("debug", false);
        getConfig().set("debug", next);
        saveConfig();
        reloadPlugin();
    }

    public PluginRuntime runtime() {
        return pluginRuntime;
    }

    public DuelArenaFootprintService duelArenaFootprint() {
        return duelArenaFootprintService;
    }

    public WarzoneDuelsHook warzoneDuelsHook() {
        return duelsHook;
    }

    public boolean isFeatureEnabled() {
        return pluginRuntime != null && pluginRuntime.settings().enabled();
    }

    private void bootstrapRuntime(ZoneStateService.ZoneStateSnapshot stateSnapshot) {
        reloadConfig();
        refreshIntegrations();
        PluginConfigLoader loader = new PluginConfigLoader(getLogger());
        PluginSettings settings = loader.load(getConfig());
        getLogger().info("End island spear blocking is " + (settings.endIsland().blockSpears() ? "enabled" : "disabled") + ".");
        PerformanceCounters counters = pluginRuntime != null ? pluginRuntime.counters() : new PerformanceCounters();

        ExecutorService ioExecutor = newSnapshotExecutor();
        FileSnapshotRepository repository = createSnapshotRepository();
        ZoneRegistry zoneRegistry = new ZoneRegistry(settings, counters);
        SnapshotService snapshotService = new SnapshotService(this, getLogger(), repository, ioExecutor, counters);
        snapshotService.loadAll(zoneRegistry.allGameplayZones());
        ZoneStateService zoneStateService = new ZoneStateService(this, zoneRegistry, snapshotService, settings.performance().resetBatchSize(), settings.performance().fullRestoreBatchSize(), settings.performance().liquidDrainBatchSize(), counters);
        if (stateSnapshot != null) {
            zoneStateService.restoreState(stateSnapshot, settings.reload().clearInvalidZoneState(), settings.reload().preserveTemporaryBlocks());
        }
        EndAccessService endAccessService = new EndAccessService(getConfig(), loader, this::saveConfig, getLogger(), settings.endAccess());
        BukkitTask resetTicker = startResetTicker(zoneStateService);
        BukkitTask backstopTicker = startBackstopTicker(settings, zoneStateService);
        BukkitTask debugTicker = startDebugTicker(settings, counters, snapshotService, zoneStateService);
        counters.reloadTaskRestart();

        pluginRuntime = new PluginRuntime(settings, zoneRegistry, zoneStateService, snapshotService, endAccessService, counters, ioExecutor, resetTicker, backstopTicker, debugTicker);
    }

    private void refreshIntegrations() {
        if (duelsHook == null) {
            duelsHook = new WarzoneDuelsHook(this);
        }
        duelsHook.refresh();
        if (duelArenaFootprintService == null) {
            duelArenaFootprintService = new DuelArenaFootprintService(this);
        }
        duelArenaFootprintService.reload();
    }

    private ExecutorService newSnapshotExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "MaceGuard-IO");
            thread.setDaemon(true);
            return thread;
        });
    }

    private FileSnapshotRepository createSnapshotRepository() {
        FileSnapshotRepository repository = new FileSnapshotRepository(Path.of(getDataFolder().getAbsolutePath(), "snapshots"));
        try {
            repository.ensureDirectory();
        } catch (IOException ex) {
            getLogger().warning("Failed to create snapshot directory: " + ex.getMessage());
        }
        return repository;
    }

    private BukkitTask startResetTicker(ZoneStateService zoneStateService) {
        return Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (isFeatureEnabled()) {
                zoneStateService.tickResets();
            }
        }, 100L, 100L);
    }

    private BukkitTask startBackstopTicker(PluginSettings settings, ZoneStateService zoneStateService) {
        if (!settings.backstopScan().enabled()) {
            return null;
        }
        long intervalTicks = settings.backstopScan().intervalMinutes() * 60L * 20L;
        return Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (isFeatureEnabled()) {
                zoneStateService.runBackstopPass(settings.backstopScan().maxZonesPerTick(), settings.backstopScan().maxBlocksPerTick(), settings.backstopScan().repairMode(), settings.backstopScan().reportOnly());
            }
        }, intervalTicks, intervalTicks);
    }

    private BukkitTask startDebugTicker(PluginSettings settings, PerformanceCounters counters, SnapshotService snapshotService, ZoneStateService zoneStateService) {
        if (!settings.debugPerformance().enabled()) {
            return null;
        }
        long intervalTicks = settings.debugPerformance().logIntervalSeconds() * 20L;
        return Bukkit.getScheduler().runTaskTimer(this, () -> getLogger().info("Performance counters: " + counters.summary()
                + ", snapshotLoading=" + snapshotService.loadingZones()
                + ", resetQueueSize=" + zoneStateService.resetQueueSize()
                + ", activeZoneTasks=" + zoneStateService.activeZoneTaskCount()
                + ", activeDrainTasks=" + zoneStateService.activeDrainTaskCount()
                + ", drainQueueSize=" + zoneStateService.drainQueueSize()
                + ", temporaryBlocks=" + zoneStateService.temporaryBlockCount()), intervalTicks, intervalTicks);
    }

    private void saveBundledFootprint() {
        File output = new File(getDataFolder(), "duel-arena-footprint.yml");
        if (!output.exists()) {
            saveResource("duel-arena-footprint.yml", false);
        }
    }

    private void migrateConfig() {
        new ConfigMigrator(this).migrateBundledConfig("config.yml");
    }
}
