package com.lincoln.maceguard.warzone.combat;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Tracks stasis pearls and consumes exactly one impact for each Ender Pearl teleport. */
public final class StasisPearlListener implements Listener {
    private static final long DIAGNOSTIC_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_DIAGNOSTIC_KEYS = 32;

    private final CombatScopeService scopes;
    private final StasisPearlTracker pearls;
    private final WarzoneMessageService messages;
    private final Duration minimumAge;
    private final JavaPlugin plugin;
    private final StasisPearlMetadata metadata;
    private final StasisPearlLedger ledger;
    private final PearlEventDiagnostics diagnostics;
    private final TimeSource time;
    private final Map<String, Long> diagnosticRateLimit = new HashMap<>(); // NOPMD - Bukkit main-thread state

    public StasisPearlListener(CombatScopeService scopes, StasisPearlTracker pearls,
                               WarzoneMessageService messages, Duration minimumAge) {
        this(scopes, pearls, messages, minimumAge,
                JavaPlugin.getProvidingPlugin(StasisPearlListener.class), TimeSource.system());
    }

    StasisPearlListener(CombatScopeService scopes, StasisPearlTracker pearls,
                        WarzoneMessageService messages, Duration minimumAge,
                        JavaPlugin plugin, TimeSource time) {
        this.scopes = scopes;
        this.pearls = pearls;
        this.messages = messages;
        this.minimumAge = minimumAge;
        this.plugin = plugin;
        this.metadata = new StasisPearlMetadata();
        this.ledger = new StasisPearlLedger();
        this.diagnostics = PearlEventDiagnostics.forPlugin(plugin);
        this.time = time;
        StasisCommand.bind(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;
        UUID pearlId = pearl.getUniqueId();
        UUID ownerId = player.getUniqueId();
        if (event.isCancelled()) {
            pearls.removePearl(pearlId);
            diagnostics.record(ownerId, "launch-cancelled", () -> "pearl=" + pearlId);
            return;
        }
        long wall = time.wallMillis();
        long nanos = time.nanoTime();
        metadata.mark(pearl, ownerId, wall);
        ledger.record(player, pearlId, wall);
        pearls.launched(pearlId, ownerId, wall, nanos);
        diagnostics.record(ownerId, "launch", () -> "pearl=" + pearlId + " owner=" + ownerId
                + " wall=" + wall);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPearlAdded(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        UUID ownerId = pearl.getOwnerUniqueId();
        if (ownerId == null) return;
        Player owner = pearl.getServer().getPlayer(ownerId);
        if (owner == null) return;
        restoreExactMetadata(pearl, owner, time.wallMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        reconcileOwnedPearls(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        pearls.clearOwner(player.getUniqueId());
        ledger.clear(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        UUID entityOwner = pearl.getOwnerUniqueId();
        long wall = time.wallMillis();
        StasisPearlMetadata.ReadResult read = recoverMetadata(pearl, entityOwner, wall);
        if (!read.marked()) return;
        UUID ownerId = read.ownerId() != null ? read.ownerId() : entityOwner;
        if (ownerId == null) {
            report("missing-owner", "Marked Ender Pearl " + pearl.getUniqueId()
                    + " has no recoverable owner; no player teleport can be correlated.");
            return;
        }
        if (read.failClosed()) report(read.diagnostic(), "Marked Ender Pearl "
                + pearl.getUniqueId() + " has invalid metadata: " + read.diagnostic()
                + ". The affected owner's next matching pearl teleport will fail closed.");
        Location location = pearl.getLocation();
        StasisPearlTracker.Impact impact = pearls.landed(pearl.getUniqueId(),
                new StasisPearlTracker.LaunchMetadata(ownerId, read.launchedAtMillis(),
                        read.failClosed(), read.diagnostic()), minimumAge,
                pearl.getServer().getCurrentTick(), position(location), wall, time.nanoTime());
        Player onlineOwner = pearl.getServer().getPlayer(ownerId);
        if (onlineOwner != null) ledger.remove(onlineOwner, pearl.getUniqueId());
        diagnostics.record(ownerId, "impact", () -> "pearl=" + pearl.getUniqueId()
                + " tick=" + impact.serverTick() + " world=" + location.getWorld().getUID()
                + " pos=" + compact(location) + " ageMs=" + impact.elapsedMillis()
                + " source=" + impact.ageSource() + " enforce=" + impact.enforce());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        Location destination = event.getTo() != null && event.getTo().getWorld() != null
                ? event.getTo() : event.getFrom();
        boolean cancelledOnEntry = event.isCancelled();
        StasisPearlTracker.Correlation correlation = pearls.correlate(player.getUniqueId(),
                player.getServer().getCurrentTick(), position(destination), time.nanoTime());
        diagnostics.record(player.getUniqueId(), "teleport", () -> "cause=" + event.getCause()
                + " tick=" + player.getServer().getCurrentTick() + " to=" + compact(destination)
                + " cancelledOnEntry=" + cancelledOnEntry + " matched=" + correlation.matched()
                + " selected=" + correlation.selectedPearlId() + " candidates="
                + correlation.candidateCount() + " ambiguous=" + correlation.ambiguous()
                + " overflow=" + correlation.overflow() + " effectiveAged="
                + correlation.effectiveAged() + " destinationMatched="
                + correlation.destinationMatched());
        if (!correlation.matched() || cancelledOnEntry) return;
        if (!shouldBlock(player, correlation.effectiveAged())) return;
        event.setCancelled(true);
        messages.stasisBlocked(player);
        messages.send(player, "<red>Your stasis pearl was blocked. <gray>Use <white>/stasis<gray> for more info.");
        diagnostics.record(player.getUniqueId(), "teleport-final", () -> "selected="
                + correlation.selectedPearlId() + " maceGuardCancelled=true");
    }

    /** Repairs exact identities for Paper-associated pearls that survived/reloaded unchanged. */
    void reconcileOwnedPearls(Player player) {
        long wall = time.wallMillis();
        for (EnderPearl pearl : player.getEnderPearls())
            if (player.getUniqueId().equals(pearl.getOwnerUniqueId()))
                restoreExactMetadata(pearl, player, wall);
    }

    /**
     * Recovers a reconstructed pearl before impact authority is calculated. Exact identity wins.
     * If Paper replaced the entity UUID, an otherwise unmarked impact consumes the oldest durable
     * owner record so relogging cannot reset an old stasis chamber's age.
     */
    StasisPearlMetadata.ReadResult recoverMetadata(EnderPearl pearl, UUID ownerId, long wall) {
        StasisPearlMetadata.ReadResult read = metadata.read(pearl, ownerId, wall);
        if (read.marked() || ownerId == null) return read;
        Player owner = pearl.getServer().getPlayer(ownerId);
        if (owner == null) return read;
        reconcileOwnedPearls(owner);
        read = metadata.read(pearl, ownerId, wall);
        if (read.marked()) return read;
        Long launchedAt = ledger.rebindOldest(owner, pearl.getUniqueId());
        if (launchedAt == null) return read;
        metadata.mark(pearl, ownerId, launchedAt);
        diagnostics.record(ownerId, "relog-recovered", () -> "pearl=" + pearl.getUniqueId()
                + " launchedAt=" + launchedAt + " exact=false");
        return metadata.read(pearl, ownerId, wall);
    }

    private void restoreExactMetadata(EnderPearl pearl, Player owner, long wall) {
        UUID ownerId = owner.getUniqueId();
        StasisPearlMetadata.ReadResult read = metadata.read(pearl, ownerId, wall);
        if (read.marked()) {
            if (!read.failClosed() && ownerId.equals(read.ownerId()))
                ledger.recordObserved(owner, pearl.getUniqueId(), read.launchedAtMillis());
            return;
        }
        Long launchedAt = ledger.read(owner.getPersistentDataContainer()).get(pearl.getUniqueId());
        if (launchedAt == null) return;
        metadata.mark(pearl, ownerId, launchedAt);
        diagnostics.record(ownerId, "relog-recovered", () -> "pearl=" + pearl.getUniqueId()
                + " launchedAt=" + launchedAt + " exact=true");
    }

    private boolean shouldBlock(Player player, boolean aged) {
        boolean combatBound = scopes.combatBound(player);
        Optional<CombatScopeService.Latch> latch = scopes.latch(player.getUniqueId());
        return StasisPolicy.shouldBlock(aged, combatBound, false,
                player.hasPermission("warzonerotator.bypass"), latch.isPresent(),
                latch.map(CombatScopeService.Latch::stasisDenied).orElse(false));
    }

    private void report(String key, String message) {
        long now = time.wallMillis();
        Long previous = diagnosticRateLimit.put(key, now);
        if (previous == null || now - previous >= DIAGNOSTIC_INTERVAL_MILLIS)
            plugin.getLogger().warning(message);
        if (diagnosticRateLimit.size() > MAX_DIAGNOSTIC_KEYS)
            diagnosticRateLimit.entrySet().removeIf(entry -> now - entry.getValue()
                    >= DIAGNOSTIC_INTERVAL_MILLIS);
    }

    private StasisPearlTracker.Position position(Location location) {
        return new StasisPearlTracker.Position(location.getWorld().getUID(),
                location.getX(), location.getY(), location.getZ());
    }

    private String compact(Location location) {
        return location.getWorld().getName() + "@" + String.format(java.util.Locale.ROOT,
                "%.2f,%.2f,%.2f", location.getX(), location.getY(), location.getZ());
    }

    interface TimeSource {
        long wallMillis();
        long nanoTime();
        static TimeSource system() {
            return new TimeSource() {
                @Override public long wallMillis() { return System.currentTimeMillis(); }
                @Override public long nanoTime() { return System.nanoTime(); }
            };
        }
    }
}
