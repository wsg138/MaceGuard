package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.temporary.TemporaryBlock;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.combat.CombatIntegrationListener;
import com.lincoln.maceguard.warzone.combat.CombatPositionListener;
import com.lincoln.maceguard.warzone.combat.StasisPearlListener;
import com.lincoln.maceguard.warzone.combat.CombatScopeService;
import com.lincoln.maceguard.warzone.combat.CombatLogXGateway;
import com.lincoln.maceguard.warzone.combat.CombatLogXGatewayFactory;
import com.lincoln.maceguard.warzone.combat.StasisPearlTracker;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneMessages;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.gui.WarzoneGuiManager;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.restriction.CooldownService;
import com.lincoln.maceguard.warzone.restriction.ItemRestrictionListener;
import com.lincoln.maceguard.warzone.restriction.LungeVelocityGate;
import com.lincoln.maceguard.warzone.restriction.RestrictionDecision;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionService;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.restriction.VisualCooldownService;
import com.lincoln.maceguard.warzone.rotation.ModifierSelector;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.RotationState;
import com.lincoln.maceguard.warzone.rotation.WarzoneStateStore;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

public final class WarzoneRuntime {
    private final JavaPlugin plugin;
    private final TemporaryBlockService temporaryBlocks;
    private final WarzoneControlConfig controlConfig;
    private final WarzoneConfig config;
    private final WarzoneRegionService region;
    private final WarzoneMessageService messages;
    private final CooldownService cooldowns;
    private final VisualCooldownService visualCooldowns;
    private final RestrictionService restrictions;
    private final CombatScopeService combatScopes;
    private final CombatIntegrationListener combatIntegration;
    private final CombatPositionListener combatPositionListener;
    private final StasisPearlListener stasisPearlListener;
    private final CombatLogXGateway combatLogX;
    private final RotationManager rotations;
    private final WarzoneGuiManager guis;
    private final ItemRestrictionListener restrictionListener;
    private final Path pendingCobwebClearMarker;
    private BukkitTask clockTask;
    private BukkitTask regionRefreshTask;
    private volatile boolean pendingWarzoneCobwebClear;
    private boolean pendingCobwebRecoveryActivated;

    public WarzoneRuntime(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, WarzoneConfig config,
                          WarzoneMessages templates, WarzoneStateStore store, Clock clock) {
        this(plugin, temporaryBlocks, WarzoneControlConfig.legacy(config), templates, store, clock,
                RandomGenerator.getDefault(), null);
    }

    public WarzoneRuntime(JavaPlugin plugin, TemporaryBlockService temporaryBlocks,
                          WarzoneControlConfig controlConfig, WarzoneMessages templates,
                          WarzoneStateStore store, Clock clock) {
        this(plugin, temporaryBlocks, controlConfig, templates, store, clock,
                RandomGenerator.getDefault(), null);
    }

    public WarzoneRuntime(JavaPlugin plugin, TemporaryBlockService temporaryBlocks,
                          WarzoneControlConfig controlConfig, WarzoneMessages templates,
                          WarzoneStateStore store, Clock clock, WorldGuardQueryService queries) {
        this(plugin, temporaryBlocks, controlConfig, templates, store, clock,
                RandomGenerator.getDefault(), queries);
    }

    WarzoneRuntime(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, WarzoneConfig config,
                   WarzoneMessages templates, WarzoneStateStore store, Clock clock, RandomGenerator random) {
        this(plugin, temporaryBlocks, WarzoneControlConfig.legacy(config), templates, store, clock, random, null);
    }

    WarzoneRuntime(JavaPlugin plugin, TemporaryBlockService temporaryBlocks,
                   WarzoneControlConfig controlConfig, WarzoneMessages templates,
                   WarzoneStateStore store, Clock clock, RandomGenerator random,
                   WorldGuardQueryService queries) {
        WarzoneConfig config = controlConfig.gameplay();
        validateCooldownTargets(config);
        this.plugin = plugin;
        this.temporaryBlocks = temporaryBlocks;
        this.controlConfig = controlConfig;
        this.config = config;
        this.pendingCobwebClearMarker = plugin.getDataFolder().toPath().resolve("state")
                .resolve("warzone-cobweb-clear.pending");
        this.pendingWarzoneCobwebClear = Files.exists(pendingCobwebClearMarker);
        this.region = new WarzoneRegionService(config.region(),
                config.enabled() ? plugin.getLogger() : null);
        this.messages = new WarzoneMessageService(clock, region, config, templates);
        this.cooldowns = new CooldownService(clock::millis);
        this.visualCooldowns = new VisualCooldownService(plugin.getServer(), clock::millis,
                () -> plugin.getServer().getCurrentTick());
        this.rotations = new RotationManager(controlConfig, store, clock, random, this::transition, this::warning);
        this.messages.bind(rotations);
        this.guis = new WarzoneGuiManager(plugin, this);
        this.combatLogX = CombatLogXGatewayFactory.discover(plugin);
        this.combatScopes = new CombatScopeService(combatLogX, queries);
        StasisPearlTracker pearls = new StasisPearlTracker();
        this.combatIntegration = new CombatIntegrationListener(combatScopes, pearls);
        this.combatPositionListener = new CombatPositionListener(combatScopes, combatIntegration);
        this.stasisPearlListener = new StasisPearlListener(combatScopes, pearls, messages,
                config.combat().stasis().minimumAge());
        this.restrictions = new RestrictionService(rotations::active, cooldowns,
                combatScopes::carryoverEligible);
        this.restrictionListener = new ItemRestrictionListener(plugin, restrictions, combatScopes,
                cooldowns, visualCooldowns, region, messages, rotations::active,
                new LungeVelocityGate(System::nanoTime, Duration.ofMillis(250)));
    }

    public void start() {
        startInternal(true);
    }

    void startStaged() {
        startInternal(false);
    }

    private void startInternal(boolean activatePendingRecovery) {
        if (activatePendingRecovery) activatePendingCobwebRecovery();
        plugin.getServer().getPluginManager().registerEvents(guis, plugin);
        clockTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            rotations.tick();
            cooldowns.discardExpired();
            messages.cleanup();
            restrictionListener.cleanup();
            combatIntegration.cleanup();
            guis.cleanup();
            if (pendingCobwebRecoveryActivated && pendingWarzoneCobwebClear
                    && region.fullyResolved()) clearTrackedCobwebs();
        }, 20L, 20L);
        if (!config.enabled()) return;
        plugin.getServer().getPluginManager().registerEvents(restrictionListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(combatPositionListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(stasisPearlListener, plugin);
        combatLogX.register(combatIntegration);
        combatIntegration.reconcile(plugin.getServer().getOnlinePlayers());
        restrictionListener.reconcileVisualCooldowns(plugin.getServer().getOnlinePlayers());
        regionRefreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            boolean resolved = region.refresh();
            restrictionListener.reconcileVisualCooldowns(plugin.getServer().getOnlinePlayers());
            if (pendingCobwebRecoveryActivated && resolved && pendingWarzoneCobwebClear)
                clearTrackedCobwebs();
        }, 20L, 100L);
    }

    void activatePendingCobwebRecovery() {
        if (pendingCobwebRecoveryActivated) return;
        pendingCobwebRecoveryActivated = true;
        clearCobwebsAfterOfflineTransition();
        if (pendingWarzoneCobwebClear && region.fullyResolved()) clearTrackedCobwebs();
    }

    public void shutdown(boolean pluginDisable) {
        if (clockTask != null) clockTask.cancel();
        if (regionRefreshTask != null) regionRefreshTask.cancel();
        clockTask = null;
        regionRefreshTask = null;
        HandlerList.unregisterAll(restrictionListener);
        HandlerList.unregisterAll(combatPositionListener);
        HandlerList.unregisterAll(stasisPearlListener);
        combatLogX.close();
        HandlerList.unregisterAll(guis);
        guis.clear();
        restrictionListener.clear();
        combatIntegration.clear();
        visualCooldowns.clearOwned();
        cooldowns.clear();
        if (pluginDisable && config.cobwebs().clearOnDisable()) clearTrackedCobwebs();
    }

    ReloadState snapshotReloadState() {
        return new ReloadState(cooldowns.snapshot(), visualCooldowns.snapshot(), rotations.state());
    }

    void adoptReloadState(ReloadState state) {
        cooldowns.restore(state.cooldowns(), currentCooldownDurations());
        visualCooldowns.restore(state.visualCooldowns());
        // Staged startup may already have recorded players as inside while cooldown state was empty.
        // Drop only the candidate's transient listener bookkeeping so final reconciliation must
        // project the transferred authoritative state onto Bukkit again.
        restrictionListener.clearTransientState();
    }

    void adoptStateStore(WarzoneStateStore stateStore) {
        rotations.adoptStateStore(stateStore);
    }

    void releaseReloadState() {
        visualCooldowns.releaseOwnership();
        cooldowns.clear();
    }

    void abortReloadState() {
        visualCooldowns.releaseOwnership();
        cooldowns.clear();
    }

    void reconcileVisualCooldowns() {
        restrictionListener.reconcileVisualCooldowns(plugin.getServer().getOnlinePlayers());
    }

    // Bukkit-main-thread snapshot; sorted iteration makes reload reconciliation deterministic.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private Map<RestrictionTarget, Duration> currentCooldownDurations() {
        return reloadCooldownDurations(config.enabled(), rotations.active());
    }

    static Map<RestrictionTarget, Duration> reloadCooldownDurations(
            boolean enabled, WarzoneConfig.ActiveSet active) {
        if (!enabled) return Map.of();
        Map<RestrictionTarget, Duration> result = new TreeMap<>();
        active.restrictions().forEach((target, restriction) -> {
            if (restriction.mode() == RestrictionMode.COOLDOWN)
                result.put(target, restriction.cooldown());
        });
        active.carriedRestrictions().forEach((target, restriction) -> {
            if (restriction.mode() == RestrictionMode.COOLDOWN)
                result.merge(target, restriction.cooldown(), (left, right) ->
                        left.compareTo(right) <= 0 ? left : right);
        });
        return Map.copyOf(result);
    }

    public boolean gameplayScopeActive() {
        return config.enabled() && region.fullyResolved();
    }

    public boolean appliesAt(Location location) {
        return gameplayScopeActive() && region.contains(location);
    }

    public CobwebDecision cobwebDecision(Player player, Location location) {
        if (!appliesAt(location) || player.hasPermission("warzonerotator.bypass"))
            return CobwebDecision.permit();
        if (!rotations.active().cobwebsAllowed()) return CobwebDecision.unavailable();
        RestrictionDecision restriction = restrictions.material(player.getUniqueId(), Material.COBWEB,
                false, true, false);
        return restriction.denied() ? CobwebDecision.restricted(restriction)
                : new CobwebDecision(true, false, restriction);
    }

    public void successfulCobweb(Player player, RestrictionDecision decision) {
        if (!gameplayScopeActive()) return;
        restrictions.success(player.getUniqueId(), decision, Material.COBWEB);
        messages.cooldownStarted(player, decision, Material.COBWEB);
        visualCooldowns.apply(player, decision, Material.COBWEB);
    }

    public void sendCobwebDenial(Player player, CobwebDecision decision) {
        if (decision.rotationUnavailable()) messages.cobwebUnavailable(player);
        else if (decision.restriction() != null) messages.denial(player, decision.restriction());
    }

    private void transition(WarzoneConfig.ActiveSet previous, WarzoneConfig.ActiveSet current,
                            boolean announce) {
        restrictionListener.clearTransientState();
        Set<RestrictionTarget> changedTargets = changedRestrictionTargets(previous, current);
        visualCooldowns.clearOwned(changedTargets);
        cooldowns.clearTargets(changedTargets);
        if (shouldClearCobwebs(previous, current, config.cobwebs())) clearTrackedCobwebs();
        if (!announce || !gameplayScopeActive()) return;

        Set<String> removed = new LinkedHashSet<>(previous.modifierIds());
        removed.removeAll(current.modifierIds());
        for (String id : removed) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier != null && modifier.endMessage() != null && !modifier.endMessage().isBlank())
                messages.broadcast(modifier.endMessage(), config.messages().transitionAudience());
        }

        Set<String> added = new LinkedHashSet<>(current.modifierIds());
        added.removeAll(previous.modifierIds());
        for (String id : added) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier != null && modifier.startMessage() != null && !modifier.startMessage().isBlank())
                messages.broadcast(modifier.startMessage(), config.messages().transitionAudience());
        }
        if (added.isEmpty())
            messages.broadcast("<gold>The Warzone modifiers are now <meta><gold>.",
                    config.messages().transitionAudience());
    }

    static Set<RestrictionTarget> changedRestrictionTargets(WarzoneConfig.ActiveSet previous,
                                                               WarzoneConfig.ActiveSet current) {
        Set<RestrictionTarget> targets = new LinkedHashSet<>();
        targets.addAll(previous.restrictions().keySet());
        targets.addAll(current.restrictions().keySet());
        targets.addAll(previous.carriedRestrictions().keySet());
        targets.addAll(current.carriedRestrictions().keySet());
        targets.removeIf(target -> java.util.Objects.equals(
                previous.restrictions().get(target), current.restrictions().get(target))
                && java.util.Objects.equals(previous.carriedRestrictions().get(target),
                current.carriedRestrictions().get(target)));
        return Set.copyOf(targets);
    }

    static boolean shouldClearCobwebs(WarzoneConfig.ActiveSet previous,
                                      WarzoneConfig.ActiveSet current,
                                      WarzoneConfig.Cobwebs policy) {
        return policy.clearOnMetaChange() && previous.cobwebsAllowed() && !current.cobwebsAllowed();
    }

    private void warning(WarzoneConfig.ActiveSet active, Duration remaining) {
        if (!gameplayScopeActive()) return;
        String template = active.modifierIds().stream().map(config.modifiers()::get)
                .filter(java.util.Objects::nonNull).map(WarzoneConfig.Modifier::warningMessage)
                .filter(value -> value != null && !value.isBlank()).findFirst()
                .orElse(messages.rotationWarning());
        messages.broadcast(template, config.messages().warningAudience());
    }

    private void clearCobwebsAfterOfflineTransition() {
        if (!config.cobwebs().clearOnMetaChange() || !rotations.advancedDuringRestore()) return;
        try {
            WarzoneConfig.ActiveSet stored = new ModifierSelector(new java.util.Random(0L))
                    .composeExact(config, rotations.storedActiveModifierIds());
            if (stored.cobwebsAllowed() && !rotations.active().cobwebsAllowed())
                clearTrackedCobwebs();
        } catch (IllegalArgumentException ignored) {
            // Invalid old state cannot safely identify a prior cobweb-enabled set.
        }
    }

    private void validateCooldownTargets(WarzoneConfig config) {
        config.targetPolicies().forEach((target, policy) -> {
            if (policy.canCooldown() && !target.supportsCooldown())
                throw new IllegalArgumentException("restriction-targets." + target.id()
                        + " enables cooldowns but has no reliable success event");
        });
        for (WarzoneConfig.Modifier modifier : config.modifiers().values()) {
            modifier.restrictions().forEach((target, restriction) -> {
                if (restriction.mode() == RestrictionMode.COOLDOWN && !target.supportsCooldown())
                    throw new IllegalArgumentException("modifiers." + modifier.id() + ".restrictions."
                            + target.id() + " uses COOLDOWN without a reliable success event");
            });
        }
    }

    public WarzoneConfig config() { return config; }
    public WarzoneControlConfig controlConfig() { return controlConfig; }
    public WarzoneRegionService region() { return region; }
    public WarzoneMessageService messages() { return messages; }
    public RotationManager rotations() { return rotations; }
    public CooldownService cooldowns() { return cooldowns; }
    public CombatScopeService combatScopes() { return combatScopes; }
    public WarzoneGuiManager guis() { return guis; }
    public boolean schedulerActive() { return clockTask != null && !clockTask.isCancelled(); }

    public int clearTrackedCobwebs() {
        if (!pendingWarzoneCobwebClear || !Files.exists(pendingCobwebClearMarker))
            ensurePendingCobwebMarker();
        pendingWarzoneCobwebClear = true;

        if (!region.fullyResolved())
            return temporaryBlocks.clearMatching(TemporaryBlock::warzoneOwned);

        Predicate<TemporaryBlock> selected = this::isWarzoneCobweb;
        int affected = temporaryBlocks.clearMatching(selected);
        if (temporaryBlocks.countMatching(selected) == 0) {
            temporaryBlocks.persistCurrentState().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    plugin.getLogger().severe("Could not durably finish the warzone cobweb clear; "
                            + "the pending marker was retained: " + failure.getMessage());
                    return;
                }
                clearPendingCobwebMarker();
            });
        }
        return affected;
    }

    private boolean isWarzoneCobweb(TemporaryBlock entry) {
        if (entry.warzoneOwned()) return true;
        org.bukkit.World world;
        try { world = org.bukkit.Bukkit.getWorld(java.util.UUID.fromString(entry.worldUuid())); }
        catch (IllegalArgumentException ex) { return false; }
        return world != null && region.contains(new Location(world, entry.x(), entry.y(), entry.z()));
    }

    private void ensurePendingCobwebMarker() {
        try {
            Files.createDirectories(pendingCobwebClearMarker.getParent());
            Files.writeString(pendingCobwebClearMarker, "pending\n", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not persist the pending warzone cobweb clear marker: "
                    + ex.getMessage());
        }
    }

    private void clearPendingCobwebMarker() {
        try {
            Files.deleteIfExists(pendingCobwebClearMarker);
            pendingWarzoneCobwebClear = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Warzone cobwebs were cleared, but the pending marker could not "
                    + "be removed; the clear will be checked again after restart: " + ex.getMessage());
        }
    }

    record ReloadState(CooldownService.Snapshot cooldowns,
                       VisualCooldownService.Snapshot visualCooldowns,
                       RotationState rotationState) { }

    public record CobwebDecision(boolean allowed, boolean rotationUnavailable,
                                 RestrictionDecision restriction) {
        public static CobwebDecision permit() {
            return new CobwebDecision(true, false, RestrictionDecision.unrestricted());
        }
        public static CobwebDecision unavailable() {
            return new CobwebDecision(false, true, null);
        }
        public static CobwebDecision restricted(RestrictionDecision restriction) {
            return new CobwebDecision(false, false, restriction);
        }
    }
}
