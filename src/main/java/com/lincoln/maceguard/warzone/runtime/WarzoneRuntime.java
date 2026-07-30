package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.temporary.TemporaryBlock;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneMessages;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.restriction.CooldownService;
import com.lincoln.maceguard.warzone.restriction.ItemRestrictionListener;
import com.lincoln.maceguard.warzone.restriction.LungeVelocityGate;
import com.lincoln.maceguard.warzone.restriction.RestrictionDecision;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionService;
import com.lincoln.maceguard.warzone.restriction.VisualCooldownService;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.WarzoneStateStore;
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
import java.util.function.Predicate;

public final class WarzoneRuntime {
    private final JavaPlugin plugin;
    private final TemporaryBlockService temporaryBlocks;
    private final WarzoneConfig config;
    private final WarzoneRegionService region;
    private final WarzoneMessageService messages;
    private final CooldownService cooldowns;
    private final VisualCooldownService visualCooldowns;
    private final RestrictionService restrictions;
    private final RotationManager rotations;
    private final ItemRestrictionListener restrictionListener;
    private final Path pendingCobwebClearMarker;
    private BukkitTask clockTask;
    private BukkitTask regionRefreshTask;
    private volatile boolean pendingWarzoneCobwebClear;

    public WarzoneRuntime(JavaPlugin plugin, TemporaryBlockService temporaryBlocks, WarzoneConfig config,
                          WarzoneMessages templates, WarzoneStateStore store, Clock clock) {
        validateCooldownTargets(config);
        this.plugin = plugin;
        this.temporaryBlocks = temporaryBlocks;
        this.config = config;
        this.pendingCobwebClearMarker = plugin.getDataFolder().toPath().resolve("state")
                .resolve("warzone-cobweb-clear.pending");
        this.pendingWarzoneCobwebClear = Files.exists(pendingCobwebClearMarker);
        this.region = new WarzoneRegionService(config.region(), plugin.getLogger());
        this.messages = new WarzoneMessageService(clock, region, config, templates);
        this.cooldowns = new CooldownService(clock::millis);
        this.visualCooldowns = new VisualCooldownService(plugin.getServer(), clock::millis,
                () -> plugin.getServer().getCurrentTick());
        this.rotations = new RotationManager(config, store, clock, this::transition, this::warning);
        this.messages.bind(rotations);
        this.restrictions = new RestrictionService(rotations::active, cooldowns);
        this.restrictionListener = new ItemRestrictionListener(restrictions, cooldowns, visualCooldowns, region,
                messages, new LungeVelocityGate(System::nanoTime, Duration.ofMillis(250)));
        clearCobwebsAfterOfflineTransition();
        if (pendingWarzoneCobwebClear && region.regionResolved()) clearTrackedCobwebs();
    }

    public void start() {
        if (!config.enabled()) return;
        plugin.getServer().getPluginManager().registerEvents(restrictionListener, plugin);
        restrictionListener.reconcileVisualCooldowns(plugin.getServer().getOnlinePlayers());
        clockTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            rotations.tick();
            cooldowns.discardExpired();
            messages.cleanup();
            restrictionListener.cleanup();
            if (pendingWarzoneCobwebClear && region.regionResolved()) clearTrackedCobwebs();
        }, 20L, 20L);
        regionRefreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            boolean resolved = region.refresh();
            restrictionListener.reconcileVisualCooldowns(plugin.getServer().getOnlinePlayers());
            if (resolved && pendingWarzoneCobwebClear) clearTrackedCobwebs();
        }, 20L, 100L);
    }

    public void shutdown(boolean pluginDisable) {
        if (clockTask != null) clockTask.cancel();
        if (regionRefreshTask != null) regionRefreshTask.cancel();
        clockTask = null;
        regionRefreshTask = null;
        HandlerList.unregisterAll(restrictionListener);
        restrictionListener.clear();
        visualCooldowns.clearOwned();
        cooldowns.clear();
        if (pluginDisable && config.cobwebs().clearOnDisable()) clearTrackedCobwebs();
    }

    public CobwebDecision cobwebDecision(Player player, Location location) {
        if (!config.enabled() || !region.contains(location)) return CobwebDecision.permit();
        if (player.hasPermission("warzonerotator.bypass")) return CobwebDecision.permit();
        if (!rotations.active().cobwebsAllowed()) return CobwebDecision.unavailable();
        RestrictionDecision restriction = restrictions.material(player.getUniqueId(), Material.COBWEB,
                false, true, false);
        return restriction.denied() ? CobwebDecision.restricted(restriction)
                : new CobwebDecision(true, false, restriction);
    }

    public void successfulCobweb(Player player, RestrictionDecision decision) {
        restrictions.success(player.getUniqueId(), decision);
        visualCooldowns.apply(player, decision);
    }

    public void sendCobwebDenial(Player player, CobwebDecision decision) {
        if (decision.rotationUnavailable()) messages.cobwebUnavailable(player);
        else if (decision.restriction() != null) messages.denial(player, decision.restriction());
    }

    private void transition(WarzoneConfig.Rotation previous, WarzoneConfig.Rotation current, boolean announce) {
        restrictionListener.clearTransientState();
        visualCooldowns.clearOwned();
        cooldowns.clear();
        if (previous.cobwebsAllowed() && !current.cobwebsAllowed() && config.cobwebs().clearOnMetaChange())
            clearTrackedCobwebs();
        if (!announce) return;
        if (previous.endMessage() != null && !previous.endMessage().isBlank())
            messages.broadcast(previous.endMessage(), config.messages().transitionAudience());
        messages.broadcast(current.startMessage(), config.messages().transitionAudience());
    }

    private void warning(WarzoneConfig.Rotation rotation, Duration remaining) {
        String template = rotation.warningMessage() == null ? messages.rotationWarning() : rotation.warningMessage();
        messages.broadcast(template, config.messages().warningAudience());
    }

    private void clearCobwebsAfterOfflineTransition() {
        if (!config.cobwebs().clearOnMetaChange() || !rotations.advancedDuringRestore()) return;
        WarzoneConfig.Rotation stored = config.rotationsById().get(rotations.storedActiveRotationId());
        if (stored != null && stored.cobwebsAllowed() && !rotations.active().cobwebsAllowed())
            clearTrackedCobwebs();
    }

    private void validateCooldownTargets(WarzoneConfig config) {
        config.targetPolicies().forEach((target, policy) -> {
            if (policy.canCooldown() && !target.supportsCooldown())
                throw new IllegalArgumentException("restriction-targets." + target.id()
                        + " enables cooldowns but has no reliable success event");
        });
        for (WarzoneConfig.Rotation rotation : config.rotations()) {
            rotation.restrictions().forEach((target, restriction) -> {
                if (restriction.mode() == RestrictionMode.COOLDOWN && !target.supportsCooldown())
                    throw new IllegalArgumentException("rotations." + rotation.id() + ".restrictions."
                            + target.id() + " uses COOLDOWN without a reliable success event");
            });
        }
    }

    public WarzoneConfig config() { return config; }
    public WarzoneRegionService region() { return region; }
    public WarzoneMessageService messages() { return messages; }
    public RotationManager rotations() { return rotations; }
    public CooldownService cooldowns() { return cooldowns; }
    public boolean schedulerActive() { return clockTask != null && !clockTask.isCancelled(); }

    public int clearTrackedCobwebs() {
        ensurePendingCobwebMarker();
        pendingWarzoneCobwebClear = true;

        if (!region.regionResolved()) {
            return temporaryBlocks.clearMatching(TemporaryBlock::warzoneOwned);
        }

        Predicate<TemporaryBlock> selected = this::isWarzoneCobweb;
        int affected = temporaryBlocks.clearMatching(selected);
        if (temporaryBlocks.countMatching(selected) == 0) {
            temporaryBlocks.persistCurrentState().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    plugin.getLogger().severe("Could not durably finish the warzone cobweb clear; the pending marker was retained: "
                            + failure.getMessage());
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
        return world != null && region.containsResolved(new Location(world, entry.x(), entry.y(), entry.z()));
    }

    private void ensurePendingCobwebMarker() {
        try {
            Files.createDirectories(pendingCobwebClearMarker.getParent());
            Files.writeString(pendingCobwebClearMarker, "pending\n", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not persist the pending warzone cobweb clear marker: " + ex.getMessage());
        }
    }

    private void clearPendingCobwebMarker() {
        try {
            Files.deleteIfExists(pendingCobwebClearMarker);
            pendingWarzoneCobwebClear = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Warzone cobwebs were cleared, but the pending marker could not be removed; "
                    + "the clear will be checked again after restart: " + ex.getMessage());
        }
    }

    public record CobwebDecision(boolean allowed, boolean rotationUnavailable, RestrictionDecision restriction) {
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
