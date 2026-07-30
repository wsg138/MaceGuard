package com.lincoln.maceguard;

import com.lincoln.maceguard.end.EndRestrictionListener;
import com.lincoln.maceguard.explosive.ExplosiveControlListener;
import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.command.MaceGuardCommand;
import com.lincoln.maceguard.config.ConfigLoader;
import com.lincoln.maceguard.config.LegacyMigrationReporter;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.mace.MaceDurabilityListener;
import com.lincoln.maceguard.reset.ResetCoordinator;
import com.lincoln.maceguard.reset.SparseOriginalListener;
import com.lincoln.maceguard.storage.ArmStateRepository;
import com.lincoln.maceguard.storage.ResetJournalRepository;
import com.lincoln.maceguard.storage.SnapshotRepository;
import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import com.lincoln.maceguard.temporary.CobwebListener;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.util.Compat;
import com.lincoln.maceguard.worldguard.MaceGuardFlags;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.worldguard.WorldGuardRegionService;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class MaceGuardPlugin extends JavaPlugin {
    private final MaceGuardFlags flags = new MaceGuardFlags();
    private PluginRuntime runtime;
    private MaceDurabilityListener durabilityListener;

    @Override public void onLoad() { flags.register(getLogger()); }

    @Override public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!new java.io.File(getDataFolder(), "config.yml").exists()) saveDefaultConfig();
        startRuntime();
        runtime.io().execute(() -> new LegacyMigrationReporter(this).inspect());
        if (!Compat.isMaceSupported()) getLogger().warning("Material.MACE is unavailable; mace durability behavior is inactive.");
    }

    @Override public void onDisable() { stopRuntime(true); }

    public PluginRuntime runtime() { return runtime; }
    public boolean isFeatureEnabled() { return runtime != null && runtime.settings().enabled(); }

    public void reloadPlugin(org.bukkit.command.CommandSender feedback) {
        if (runtime != null && runtime.resets().hasActiveOperation()) { feedback.sendMessage("Reload refused while a capture or restore is active."); return; }
        Validation validation = validateReload();
        if (!validation.valid()) {
            validation.errors().forEach(error -> getLogger().severe("Reload validation: " + error));
            feedback.sendMessage("Reload rejected; the current MaceGuard and warzone runtimes remain active. Check the console.");
            return;
        }
        stopRuntime(false);
        reloadConfig();
        startRuntime();
        feedback.sendMessage("MaceGuard and integrated warzone configuration reloaded. Reset state was revalidated and never re-armed automatically.");
    }

    private void startRuntime() {
        MaceGuardConfig settings = new ConfigLoader().load(getConfig());
        settings.errors().forEach(error -> getLogger().warning("Configuration: " + error));
        ExecutorService io = Executors.newSingleThreadExecutor(runnable -> { Thread thread = new Thread(runnable, "MaceGuard-IO"); thread.setDaemon(true); return thread; });
        Path data = getDataFolder().toPath();
        WorldGuardQueryService queries = new WorldGuardQueryService(flags);
        WorldGuardRegionService regions = new WorldGuardRegionService();
        ResetCoordinator resets = new ResetCoordinator(this, settings, flags, regions, new SnapshotRepository(data.resolve("snapshots-v1")),
                new ArmStateRepository(data.resolve("state").resolve("armed.json")), new ResetJournalRepository(data.resolve("state").resolve("restore-journal.json")), io);
        TemporaryBlockService temporary = new TemporaryBlockService(this, new TemporaryBlockRepository(data.resolve("state").resolve("temporary-blocks.json")),
                io, settings.temporary().maxTrackedBlocks());
        resets.onSuccessfulReset((world, regionId) -> regions.cuboid(world, regionId).ifPresent(region ->
                temporary.discardMatching(entry -> entry.worldUuid().equals(world.getUID().toString())
                        && region.contains(entry.x(), entry.y(), entry.z()))));
        WarzoneModule warzone = new WarzoneModule(this, temporary, io);
        runtime = new PluginRuntime(settings, queries, resets, temporary, warzone, io);
        warzone.start();
        getServer().getScheduler().runTaskTimer(this, resets::tickAutomaticResets, 1200L, 1200L);

        durabilityListener = new MaceDurabilityListener(this, settings, queries);
        getServer().getPluginManager().registerEvents(durabilityListener, this);
        getServer().getPluginManager().registerEvents(new CobwebListener(queries, warzone, temporary, settings), this);
        getServer().getPluginManager().registerEvents(new SparseOriginalListener(resets), this);
        getServer().getPluginManager().registerEvents(new EndRestrictionListener(this), this);
        getServer().getPluginManager().registerEvents(new ExplosiveControlListener(this, queries), this);
        MaceGuardCommand command = new MaceGuardCommand(this);
        java.util.Objects.requireNonNull(getCommand("maceguard")).setExecutor(command);
        java.util.Objects.requireNonNull(getCommand("maceguard")).setTabCompleter(command);
    }

    private void stopRuntime(boolean pluginDisable) {
        HandlerList.unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
        if (durabilityListener != null) durabilityListener.clear();
        if (runtime != null) {
            runtime.warzone().shutdown(pluginDisable);
            runtime.temporaryBlocks().shutdown();
            runtime.io().shutdown();
            try { if (!runtime.io().awaitTermination(5, TimeUnit.SECONDS)) getLogger().warning("Timed out waiting for storage writes during shutdown; journals remain for recovery."); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        durabilityListener = null;
        runtime = null;
    }

    private Validation validateReload() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        try {
            YamlConfiguration proposed = new YamlConfiguration();
            proposed.load(new java.io.File(getDataFolder(), "config.yml"));
            MaceGuardConfig settings = new ConfigLoader().load(proposed);
            if (!settings.validSchema()) errors.addAll(settings.errors());
        } catch (java.io.IOException | InvalidConfigurationException ex) {
            errors.add("config.yml is malformed: " + ex.getMessage());
        }
        if (runtime != null) errors.addAll(runtime.warzone().validateFiles(true).errors());
        return new Validation(errors.isEmpty(), java.util.List.copyOf(errors));
    }

    private record Validation(boolean valid, java.util.List<String> errors) { }

}
