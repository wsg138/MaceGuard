package com.lincoln.maceguard;

import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.command.MaceGuardCommand;
import com.lincoln.maceguard.config.ConfigLoader;
import com.lincoln.maceguard.config.LegacyMigrationReporter;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.config.MainConfigMigrationService;
import com.lincoln.maceguard.end.EndRestrictionListener;
import com.lincoln.maceguard.explosive.ExplosiveControlListener;
import com.lincoln.maceguard.mace.MaceDurabilityListener;
import com.lincoln.maceguard.policy.BlockPolicyListener;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.reset.ResetCoordinator;
import com.lincoln.maceguard.reset.SparseOriginalListener;
import com.lincoln.maceguard.storage.ArmStateRepository;
import com.lincoln.maceguard.storage.ResetJournalRepository;
import com.lincoln.maceguard.storage.SnapshotRepository;
import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import com.lincoln.maceguard.temporary.CobwebListener;
import com.lincoln.maceguard.temporary.TemporaryBlockAdmissionJournal;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.util.Compat;
import com.lincoln.maceguard.warzone.combat.PearlEventDiagnostics;
import com.lincoln.maceguard.warzone.combat.PearlTraceCommand;
import com.lincoln.maceguard.warzone.restriction.AttributeSwapRestrictionListener;
import com.lincoln.maceguard.warzone.rotation.WarzoneStateStore;
import com.lincoln.maceguard.warzone.runtime.ReloadGuard;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.worldguard.MaceGuardFlags;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.worldguard.WorldGuardRegionService;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class MaceGuardPlugin extends JavaPlugin {
    private final MaceGuardFlags flags = new MaceGuardFlags();
    private PluginRuntime runtime;

    @Override public void onLoad() { flags.register(getLogger()); }

    @Override public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!new java.io.File(getDataFolder(), "config.yml").exists()) saveDefaultConfig();
        new LegacyMigrationReporter(this).inspect();
        new MainConfigMigrationService(this).prepare();
        reloadConfig();
        startRuntime();
        logIntegrationVersions();
        if (!Compat.isMaceSupported())
            getLogger().warning("Material.MACE is unavailable; mace durability behavior is inactive.");
    }

    @Override public void onDisable() {
        PearlEventDiagnostics.forPlugin(this).clear();
        PluginRuntime current = runtime;
        runtime = null;
        stopRuntime(current, true, true, true);
    }

    public PluginRuntime runtime() { return runtime; }
    public boolean isFeatureEnabled() {
        return runtime != null && runtime.settings().enabled();
    }

    public void reloadPlugin(org.bukkit.command.CommandSender feedback) {
        PluginRuntime current = runtime;
        if (current != null && current.resets().hasActiveOperation()) {
            feedback.sendMessage("Reload refused while a capture or restore is active.");
            return;
        }
        try {
            new MainConfigMigrationService(this).prepare();
        } catch (RuntimeException ex) {
            getLogger().severe("Reload migration failed: " + ex.getMessage());
            feedback.sendMessage("Reload rejected; configuration migration failed. Check the console.");
            return;
        }
        Validation validation = validateReload();
        if (!validation.valid()) {
            validation.errors().forEach(error ->
                    getLogger().severe("Reload validation: " + error));
            feedback.sendMessage("Reload rejected; the current MaceGuard and warzone "
                    + "runtimes remain active. Check the console.");
            return;
        }

        MaceGuardConfig proposed;
        try {
            proposed = loadProposedConfig();
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            getLogger().severe("Reload could not load the replacement configuration: "
                    + ex.getMessage());
            feedback.sendMessage("Reload rejected; the current runtime remains active.");
            return;
        }
        if (current != null && proposed.temporary().maxTrackedBlocks()
                != current.settings().temporary().maxTrackedBlocks()) {
            feedback.sendMessage("Reload rejected; temporary-blocks.max-tracked-blocks owns "
                    + "shared persistence capacity and requires a full server restart to change.");
            return;
        }

        WarzoneModule.ReloadState reloadState = current == null
                ? null : current.warzone().snapshotReloadState();
        ReloadGuard.Result<PluginRuntime> candidate = ReloadGuard.prepare(current, true,
                () -> buildRuntime(proposed, reloadState, true, current));
        if (!candidate.accepted()) {
            if (candidate.failure() != null)
                getLogger().severe("Full MaceGuard reload could not start the replacement runtime: "
                        + candidate.failure().getMessage());
            feedback.sendMessage("Reload rejected; the previous listeners, runtime, restrictions, "
                    + "and cooldowns remain authoritative.");
            return;
        }

        PluginRuntime replacement = candidate.value();
        if (current != null) current.warzone().releaseReloadState();
        runtime = replacement;
        if (current != null) stopRuntime(current, false, false, false);
        replacement.warzone().activateReloadCandidate();
        reloadConfig();
        logIntegrationVersions();
        feedback.sendMessage("MaceGuard and integrated Warzone configuration reloaded atomically. "
                + "Authoritative and visual cooldown state was handed to the replacement runtime.");
    }

    private void startRuntime() {
        MaceGuardConfig settings = new ConfigLoader().load(getConfig());
        runtime = buildRuntime(settings, null, false, null);
    }

    private PluginRuntime buildRuntime(MaceGuardConfig settings,
                                       WarzoneModule.ReloadState reloadState,
                                       boolean reloadCandidate,
                                       PluginRuntime sharedRuntime) {
        settings.errors().forEach(error ->
                getLogger().warning("Configuration: " + error));
        boolean ownsSharedStorage = sharedRuntime == null;
        ExecutorService io = ownsSharedStorage ? createStorageExecutor() : sharedRuntime.io();
        TemporaryBlockService temporary = ownsSharedStorage
                ? null : sharedRuntime.temporaryBlocks();
        TemporaryBlockAdmissionJournal admissions = ownsSharedStorage
                ? null : sharedRuntime.temporaryAdmissions();
        BukkitTask admissionTask = ownsSharedStorage
                ? null : sharedRuntime.temporaryAdmissionTask();
        WarzoneStateStore warzoneStateStore = sharedRuntime == null
                ? null : sharedRuntime.warzone().stateStore();
        WarzoneModule warzone = null;
        MaceDurabilityListener durability = null;
        BukkitTask resetTask = null;
        List<Listener> listeners = new ArrayList<>();
        try {
            Path data = getDataFolder().toPath();
            WorldGuardQueryService queries = new WorldGuardQueryService(flags);
            BlockPolicyResolver policyResolver = new BlockPolicyResolver(settings, queries,
                    getLogger());
            WorldGuardRegionService regions = new WorldGuardRegionService();
            ResetCoordinator resets = new ResetCoordinator(this, settings, flags, regions,
                    new SnapshotRepository(data.resolve("snapshots-v1")),
                    new ArmStateRepository(data.resolve("state").resolve("armed.json")),
                    new ResetJournalRepository(data.resolve("state")
                            .resolve("restore-journal.json")), io);

            if (ownsSharedStorage) {
                TemporaryBlockRepository primary = new TemporaryBlockRepository(data.resolve("state")
                        .resolve("temporary-blocks.json"));
                TemporaryBlockRepository emergency = new TemporaryBlockRepository(data.resolve("state")
                        .resolve("temporary-blocks-emergency.json"));
                TemporaryBlockRepository admissionRepository = new TemporaryBlockRepository(
                        data.resolve("state").resolve("temporary-blocks-admission.json"));
                int recovered = recoverTemporaryAdmissions(primary, admissionRepository);
                if (recovered > 0)
                    getLogger().warning("Recovered " + recovered + " temporary block admission"
                            + (recovered == 1 ? "" : "s")
                            + " from the durable write-ahead journal before runtime startup.");
                admissions = new TemporaryBlockAdmissionJournal(admissionRepository, getLogger(),
                        settings.temporary().maxTrackedBlocks());
                temporary = new TemporaryBlockService(this, primary, emergency, io,
                        settings.temporary().maxTrackedBlocks());
                getServer().getPluginManager().registerEvents(temporary, this);
                TemporaryBlockService sharedTemporary = temporary;
                TemporaryBlockAdmissionJournal sharedAdmissions = admissions;
                admissionTask = getServer().getScheduler().runTaskTimer(this,
                        () -> sharedAdmissions.reconcile(sharedTemporary), 20L, 20L);
            }

            TemporaryBlockService runtimeTemporary = temporary;
            resets.onSuccessfulReset((world, regionId) ->
                    regions.cuboid(world, regionId).ifPresent(region ->
                            runtimeTemporary.discardResetRestored(entry ->
                                    entry.worldUuid().equals(world.getUID().toString())
                                            && region.contains(entry.x(), entry.y(), entry.z()))));

            warzone = new WarzoneModule(this, temporary, io, policyResolver, queries,
                    warzoneStateStore);
            if (reloadCandidate) warzone.startReloadCandidate(reloadState);
            else warzone.start();

            resetTask = getServer().getScheduler().runTaskTimer(this,
                    resets::tickAutomaticResets, 1200L, 1200L);

            durability = new MaceDurabilityListener(this, settings, queries);
            registerListener(listeners, durability);
            registerListener(listeners, new AttributeSwapRestrictionListener(this, warzone));
            registerListener(listeners, new BlockPolicyListener(policyResolver, warzone));
            registerListener(listeners,
                    new CobwebListener(queries, warzone, temporary, settings, policyResolver,
                            admissions));
            registerListener(listeners, new SparseOriginalListener(resets));
            registerListener(listeners, new EndRestrictionListener(this));
            registerListener(listeners, new ExplosiveControlListener(this, queries));

            MaceGuardCommand command = new MaceGuardCommand(this);
            java.util.Objects.requireNonNull(getCommand("maceguard")).setExecutor(command);
            java.util.Objects.requireNonNull(getCommand("maceguard")).setTabCompleter(command);
            PearlTraceCommand pearlTrace = new PearlTraceCommand(this);
            java.util.Objects.requireNonNull(getCommand("maceguardpearltrace"))
                    .setExecutor(pearlTrace);
            java.util.Objects.requireNonNull(getCommand("maceguardpearltrace"))
                    .setTabCompleter(pearlTrace);

            return new PluginRuntime(settings, queries, policyResolver, resets, temporary,
                    admissions, warzone, io, List.copyOf(listeners), durability, resetTask,
                    admissionTask);
        } catch (RuntimeException | IOException ex) {
            cleanupPartialRuntime(listeners, durability, resetTask, warzone, temporary,
                    admissions, admissionTask, io, ownsSharedStorage);
            if (ex instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Temporary-block durable admission recovery failed", ex);
        }
    }

    private ExecutorService createStorageExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MaceGuard-IO");
            thread.setDaemon(true);
            return thread;
        });
    }

    private int recoverTemporaryAdmissions(TemporaryBlockRepository primary,
                                           TemporaryBlockRepository admissions)
            throws IOException {
        return TemporaryBlockAdmissionJournal.recoverIntoPrimary(primary, admissions);
    }

    private MaceGuardConfig loadProposedConfig()
            throws IOException, InvalidConfigurationException {
        YamlConfiguration proposed = new YamlConfiguration();
        proposed.load(new java.io.File(getDataFolder(), "config.yml"));
        return new ConfigLoader().load(proposed);
    }

    private void registerListener(List<Listener> listeners, Listener listener) {
        listeners.add(listener);
        getServer().getPluginManager().registerEvents(listener, this);
    }

    private void cleanupPartialRuntime(List<Listener> listeners,
                                       MaceDurabilityListener durability,
                                       BukkitTask resetTask, WarzoneModule warzone,
                                       TemporaryBlockService temporary,
                                       TemporaryBlockAdmissionJournal admissions,
                                       BukkitTask admissionTask, ExecutorService io,
                                       boolean ownsSharedStorage) {
        listeners.forEach(HandlerList::unregisterAll);
        if (resetTask != null) resetTask.cancel();
        if (durability != null) durability.clear();
        if (warzone != null) {
            try { warzone.shutdown(false); }
            catch (RuntimeException ex) {
                getLogger().severe("Candidate Warzone cleanup failed: " + ex.getMessage());
            }
        }
        if (!ownsSharedStorage) return;
        shutdownSharedStorage(temporary, admissions, admissionTask, io, false);
    }

    private void stopRuntime(PluginRuntime target, boolean pluginDisable,
                             boolean cancelAllPluginTasks, boolean shutdownSharedStorage) {
        if (target == null) return;
        target.listeners().forEach(HandlerList::unregisterAll);
        if (target.resetTask() != null) target.resetTask().cancel();
        if (target.durabilityListener() != null) target.durabilityListener().clear();
        try {
            target.warzone().shutdown(pluginDisable);
        } catch (RuntimeException ex) {
            getLogger().severe("Warzone cleanup failed while retiring a runtime: "
                    + ex.getMessage());
        }
        if (shutdownSharedStorage)
            shutdownSharedStorage(target.temporaryBlocks(), target.temporaryAdmissions(),
                    target.temporaryAdmissionTask(), target.io(), true);
        if (cancelAllPluginTasks) getServer().getScheduler().cancelTasks(this);
    }

    private void shutdownSharedStorage(TemporaryBlockService temporary,
                                       TemporaryBlockAdmissionJournal admissions,
                                       BukkitTask admissionTask, ExecutorService io,
                                       boolean logTimeout) {
        if (admissionTask != null) admissionTask.cancel();
        if (temporary != null) {
            HandlerList.unregisterAll(temporary);
            if (admissions != null) admissions.reconcile(temporary);
            try { temporary.shutdown(); }
            catch (RuntimeException ex) {
                getLogger().severe("Temporary-block cleanup failed while retiring shared storage: "
                        + ex.getMessage());
            }
        }
        io.shutdown();
        awaitStorage(io, temporary, logTimeout);
    }

    private void awaitStorage(ExecutorService io, TemporaryBlockService temporary,
                              boolean logTimeout) {
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS) && logTimeout)
                getLogger().warning("Timed out waiting for storage writes during shutdown; "
                        + "accepted temporary placements remain protected by the synchronous "
                        + "admission journal, while unfinished storage work will be retried or "
                        + "reported by its owning subsystem on restart.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (temporary != null) temporary.finishShutdownRecovery();
    }

    private void logIntegrationVersions() {
        getLogger().info("Runtime versions: server=" + getServer().getName() + " "
                + getServer().getVersion() + ", minecraft=" + getServer().getMinecraftVersion()
                + ", WorldGuard=" + pluginVersion("WorldGuard")
                + ", WorldEdit=" + pluginVersion("WorldEdit")
                + ", CombatLogX=" + pluginVersion("CombatLogX")
                + ", BlueSlimeCore=" + pluginVersion("BlueSlimeCore")
                + ", PlaceholderAPI=" + pluginVersion("PlaceholderAPI") + ".");
    }

    private String pluginVersion(String name) {
        Plugin plugin = getServer().getPluginManager().getPlugin(name);
        return plugin == null ? "absent" : plugin.getDescription().getVersion();
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
