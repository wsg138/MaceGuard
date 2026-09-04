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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        long wall = time.wallMillis();
        StasisPearlMetadata.ReadResult read = metadata.read(pearl, ownerId, wall);
        if (read.marked()) {
            if (!read.failClosed() && ownerId.equals(read.ownerId()))
                ledger.record(owner, pearl.getUniqueId(), read.launchedAtMillis());
            return;
        }
        Long launchedAt = ledger.read(owner.getPersistentDataContainer()).get(pearl.getUniqueId());
        if (launchedAt == null) return;
        metadata.mark(pearl, ownerId, launchedAt);
        diagnostics.record(ownerId, "entity-recovered", () -> "pearl=" + pearl.getUniqueId()
                + " launchedAt=" + launchedAt);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        reconcileOwnedPearls(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        ledger.clear(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        UUID entityOwner = pearl.getOwnerUniqueId();
        long wall = time.wallMillis();
        StasisPearlMetadata.ReadResult read = metadata.read(pearl, entityOwner, wall);
        if (!read.marked() && entityOwner != null) {
            Player owner = pearl.getServer().getPlayer(entityOwner);
            if (owner != null) {
                reconcileOwnedPearls(owner);
                read = metadata.read(pearl, entityOwner, wall);
            }
        }
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
        diagnostics.record(player.getUniqueId(), "teleport-final", () -> "selected="
                + correlation.selectedPearlId() + " maceGuardCancelled=true");
    }

    /** Repairs pearl metadata after Paper reconstructs a player's associated pearls on reconnect. */
    void reconcileOwnedPearls(Player player) {
        UUID ownerId = player.getUniqueId();
        long wall = time.wallMillis();
        Map<UUID, Long> persisted = ledger.read(player.getPersistentDataContainer());
        Map<UUID, Long> updated = new LinkedHashMap<>(persisted);
        Set<UUID> matchedPersisted = new HashSet<>();
        List<EnderPearl> unresolved = new ArrayList<>();

        for (EnderPearl pearl : player.getEnderPearls()) {
            if (!ownerId.equals(pearl.getOwnerUniqueId())) continue;
            StasisPearlMetadata.ReadResult read = metadata.read(pearl, ownerId, wall);
            if (read.marked()) {
                if (!read.failClosed() && ownerId.equals(read.ownerId())) {
                    updated.put(pearl.getUniqueId(), read.launchedAtMillis());
                    if (persisted.containsKey(pearl.getUniqueId()))
                        matchedPersisted.add(pearl.getUniqueId());
                }
                continue;
            }
            Long exactLaunch = persisted.get(pearl.getUniqueId());
            if (exactLaunch != null) {
                metadata.mark(pearl, ownerId, exactLaunch);
                matchedPersisted.add(pearl.getUniqueId());
                diagnostics.record(ownerId, "relog-recovered", () -> "pearl="
                        + pearl.getUniqueId() + " launchedAt=" + exactLaunch + " exact=true");
            } else unresolved.add(pearl);
        }

        List<Map.Entry<UUID, Long>> unmatched = persisted.entrySet().stream()
                .filter(entry -> !matchedPersisted.contains(entry.getKey()))
                .sorted(Comparator.<Map.Entry<UUID, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().toString()))
                .toList();
        if (!unresolved.isEmpty() && unresolved.size() == unmatched.size()) {
            unresolved.sort(Comparator.comparing(pearl -> pearl.getUniqueId().toString()));
            for (int index = 0; index < unresolved.size(); index++) {
                EnderPearl pearl = unresolved.get(index);
                Map.Entry<UUID, Long> previous = unmatched.get(index);
                metadata.mark(pearl, ownerId, previous.getValue());
                updated.remove(previous.getKey());
                updated.put(pearl.getUniqueId(), previous.getValue());
                diagnostics.record(ownerId, "relog-recovered", () -> "pearl="
                        + pearl.getUniqueId() + " launchedAt=" + previous.getValue()
                        + " exact=false");
            }
        }
        ledger.write(player, updated);
    }

    private boolean shouldBlock(Player player, boolean aged) {
        boolean combatBound = scopes.combatBound(player);
        if (!combatBound || !scopes.insideCombatZone(player)
                || !scopes.stasisDeniedAtLocation(player)) return false;
        Optional<CombatScopeService.Latch> latch = scopes.latch(player.getUniqueId());
        return StasisPolicy.shouldBlock(aged, true, false,
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
