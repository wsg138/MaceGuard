package com.lincoln.maceguard;

import com.lincoln.maceguard.adapter.bukkit.command.MaceGuardCommand;
import com.lincoln.maceguard.adapter.bukkit.listener.BuildProtectionListener;
import com.lincoln.maceguard.adapter.bukkit.listener.DuelArenaExplosiveListener;
import com.lincoln.maceguard.adapter.bukkit.listener.EndAccessListener;
import com.lincoln.maceguard.adapter.bukkit.listener.EndIslandListener;
import com.lincoln.maceguard.adapter.bukkit.listener.LiquidControlListener;
import com.lincoln.maceguard.adapter.bukkit.listener.MaceDurabilityListener;
import com.lincoln.maceguard.adapter.storage.FileSnapshotRepository;
import com.lincoln.maceguard.adapter.storage.SparseBaselineRepository;
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
import com.lincoln.maceguard.integration.WarzoneRotatorHook;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class MaceGuardPlugin extends JavaPlugin {
    private Optional<PluginRuntime> pluginRuntime = Optional.empty();
    private DuelArenaFootprintService duelArenaFootprintService;
    private WarzoneDuelsHook duelsHook;
    private WarzoneRotatorHook warzoneRotatorHook;

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
        pluginRuntime.ifPresent(PluginRuntime::shutdownForDisable);
        pluginRuntime = Optional.empty();
    }

    public void reloadPlugin() {
        ZoneStateService.ZoneStateSnapshot stateSnapshot = null;
        PluginRuntime currentRuntime = pluginRuntime.orElse(null);
        if (currentRuntime != null) {
            stateSnapshot = currentRuntime.zoneStateService().snapshotState();
            if (!currentRuntime.settings().reload().preserveTemporaryBlocks()) {
                currentRuntime.zoneStateService().clearTemporaryBlocksForReload();
            }
            getLogger().info("Reload preserving reset queue size " + currentRuntime.zoneStateService().resetQueueSize()
                    + " and temporary blocks " + currentRuntime.zoneStateService().temporaryBlockCount()
                    + " (preserve-temporary-blocks=" + currentRuntime.settings().reload().preserveTemporaryBlocks() + ").");
            currentRuntime.shutdownForReload();
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
        return pluginRuntime.orElse(null);
    }

    public DuelArenaFootprintService duelArenaFootprint() {
        return duelArenaFootprintService;
    }

    public WarzoneDuelsHook warzoneDuelsHook() {
        return duelsHook;
    }

    public WarzoneRotatorHook warzoneRotatorHook() {
        return warzoneRotatorHook;
    }

    public boolean isFeatureEnabled() {
        return pluginRuntime.map(runtime -> runtime.settings().enabled()).orElse(false);
    }

    private void bootstrapRuntime(ZoneStateService.ZoneStateSnapshot stateSnapshot) {
        reloadConfig();
        refreshIntegrations();
        PluginConfigLoader loader = new PluginConfigLoader(getLogger());
        PluginSettings settings = loader.load(getConfig());
        getLogger().info("End island spear blocking is " + (settings.endIsland().blockSpears() ? "enabled" : "disabled") + ".");
        PerformanceCounters counters = pluginRuntime.map(PluginRuntime::counters).orElseGet(PerformanceCounters::new);

        ExecutorService ioExecutor = newSnapshotExecutor();
        FileSnapshotRepository repository = createSnapshotRepository();
        ZoneRegistry zoneRegistry = new ZoneRegistry(settings, counters);
        SnapshotService snapshotService = new SnapshotService(this, getLogger(), repository, ioExecutor, counters);
        snapshotService.loadAll(zoneRegistry.allGameplayZones());
        SparseBaselineRepository sparseBaseline = new SparseBaselineRepository(Path.of(getDataFolder().getAbsolutePath(), "sparse-baselines"));
        ZoneStateService zoneStateService = new ZoneStateService(this, zoneRegistry, snapshotService, sparseBaseline, settings.performance().resetBatchSize(), settings.performance().fullRestoreBatchSize(), settings.performance().liquidDrainBatchSize(), counters, warzoneRotatorHook);
        if (stateSnapshot != null) {
            zoneStateService.restoreState(stateSnapshot, settings.reload().clearInvalidZoneState(), settings.reload().preserveTemporaryBlocks());
        }
        EndAccessService endAccessService = new EndAccessService(getConfig(), loader, this::saveConfig, getLogger(), settings.endAccess());
        BukkitTask resetTicker = startResetTicker(zoneStateService);
        BukkitTask backstopTicker = startBackstopTicker(settings, zoneStateService);
        BukkitTask debugTicker = startDebugTicker(settings, counters, snapshotService, zoneStateService);
        counters.reloadTaskRestart();

        pluginRuntime = Optional.of(new PluginRuntime(settings, zoneRegistry, zoneStateService, snapshotService, endAccessService, counters, ioExecutor, resetTicker, backstopTicker, debugTicker));
    }

    private void refreshIntegrations() {
        if (duelsHook == null) {
            duelsHook = new WarzoneDuelsHook(this);
        }
        duelsHook.refresh();
        if (warzoneRotatorHook == null) {
            warzoneRotatorHook = new WarzoneRotatorHook(this);
        }
        warzoneRotatorHook.refresh();
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
