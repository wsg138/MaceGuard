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
import com.lincoln.maceguard.config.PluginConfigLoader;
import com.lincoln.maceguard.config.PluginSettings;
import com.lincoln.maceguard.core.service.EndAccessService;
import com.lincoln.maceguard.core.service.DuelArenaFootprintService;
import com.lincoln.maceguard.core.service.SnapshotService;
import com.lincoln.maceguard.core.service.ZoneRegistry;
import com.lincoln.maceguard.core.service.ZoneStateService;
import com.lincoln.maceguard.integration.WarzoneDuelsHook;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MaceGuardPlugin extends JavaPlugin {
    private PluginRuntime runtime;
    private MaceGuardCommand command;
    private DuelArenaFootprintService duelArenaFootprintService;
    private WarzoneDuelsHook warzoneDuelsHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeConfigDefaults();
        saveBundledFootprint();
        bootstrapRuntime();

        Bukkit.getPluginManager().registerEvents(new BuildProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DuelArenaExplosiveListener(this), this);
        Bukkit.getPluginManager().registerEvents(new LiquidControlListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MaceDurabilityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EndAccessListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EndIslandListener(this), this);

        command = new MaceGuardCommand(this);
        command.register();

        if (!Compat.isMaceSupported()) {
            getLogger().warning("Material.MACE is not available on this server build. Mace-specific logic will no-op.");
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.shutdownForDisable();
            runtime = null;
        }
    }

    public void reloadPlugin() {
        if (runtime != null) {
            runtime.shutdownForReload();
        }
        mergeConfigDefaults();
        reloadConfig();
        bootstrapRuntime();
    }

    public void toggleDebug() {
        boolean next = !getConfig().getBoolean("debug", false);
        getConfig().set("debug", next);
        saveConfig();
        reloadPlugin();
    }

    public PluginRuntime runtime() {
        return runtime;
    }

    public DuelArenaFootprintService duelArenaFootprint() {
        return duelArenaFootprintService;
    }

    public WarzoneDuelsHook warzoneDuelsHook() {
        return warzoneDuelsHook;
    }

    public boolean isFeatureEnabled() {
        return runtime != null && runtime.settings().enabled();
    }

    private void bootstrapRuntime() {
        reloadConfig();
        if (warzoneDuelsHook == null) {
            warzoneDuelsHook = new WarzoneDuelsHook(this);
        }
        warzoneDuelsHook.refresh();
        if (duelArenaFootprintService == null) {
            duelArenaFootprintService = new DuelArenaFootprintService(this);
        }
        duelArenaFootprintService.reload();
        PluginConfigLoader loader = new PluginConfigLoader(getLogger());
        PluginSettings settings = loader.load(getConfig());

        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "MaceGuard-IO");
            thread.setDaemon(true);
            return thread;
        });

        FileSnapshotRepository repository = new FileSnapshotRepository(Path.of(getDataFolder().getAbsolutePath(), "snapshots"));
        try {
            repository.ensureDirectory();
        } catch (IOException ex) {
            getLogger().warning("Failed to create snapshot directory: " + ex.getMessage());
        }

        ZoneRegistry zoneRegistry = new ZoneRegistry(settings);
        SnapshotService snapshotService = new SnapshotService(this, getLogger(), repository, ioExecutor);
        snapshotService.loadAll(zoneRegistry.allGameplayZones());
        ZoneStateService zoneStateService = new ZoneStateService(this, zoneRegistry, snapshotService);
        EndAccessService endAccessService = new EndAccessService(getConfig(), loader, settings.endAccess());
        BukkitTask resetTicker = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (isFeatureEnabled()) {
                zoneStateService.tickResets();
            }
        }, 100L, 100L);

        runtime = new PluginRuntime(settings, zoneRegistry, zoneStateService, snapshotService, endAccessService, ioExecutor, resetTicker);
    }

    private void saveBundledFootprint() {
        File output = new File(getDataFolder(), "duel-arena-footprint.yml");
        if (!output.exists()) {
            saveResource("duel-arena-footprint.yml", false);
        }
    }

    private void mergeConfigDefaults() {
        saveDefaultConfig();
        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        try (InputStream input = getResource("config.yml")) {
            if (input == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException ex) {
            getLogger().warning("Failed to merge config defaults: " + ex.getMessage());
        }
    }
}
