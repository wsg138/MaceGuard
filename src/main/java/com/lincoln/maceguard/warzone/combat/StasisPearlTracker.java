package com.lincoln.maceguard.warzone.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per-pearl launch and short-lived impact correlation state. */
public final class StasisPearlTracker {
    private static final long IMPACT_TTL_NANOS = 500_000_000L;
    private static final long MAX_PROJECTILE_TTL_NANOS = 86_400_000_000_000L;
    private static final int MAX_TRACKED_PEARLS = 4_096;
    private static final int MAX_PEARLS_PER_PLAYER = 32;
    private static final int MAX_IMPACTS_PER_PLAYER = 8;
    private static final double MAX_DISTANCE_SQUARED = 9.0;

    private final Map<UUID, Pearl> pearls = new HashMap<>();
    private final Map<UUID, List<Impact>> impacts = new HashMap<>();

    public void launched(UUID pearlId, UUID ownerId, long nowNanos) {
        cleanup(nowNanos);
        evictOldestForOwner(ownerId);
        evictOldestGlobally();
        pearls.put(pearlId, new Pearl(ownerId, nowNanos, nowNanos + MAX_PROJECTILE_TTL_NANOS));
    }

    public boolean landed(UUID pearlId, int ticksLived, int minimumAgeTicks, long serverTick,
                          Position position, long nowNanos) {
        Pearl pearl = pearls.remove(pearlId);
        if (pearl == null) return false;
        List<Impact> ownerImpacts = impacts.computeIfAbsent(pearl.ownerId(), ignored -> new ArrayList<>());
        ownerImpacts.removeIf(value -> value.expiresAtNanos() <= nowNanos);
        if (ownerImpacts.size() >= MAX_IMPACTS_PER_PLAYER) ownerImpacts.removeFirst();
        ownerImpacts.add(new Impact(pearlId, ticksLived >= minimumAgeTicks, serverTick,
                position, nowNanos + IMPACT_TTL_NANOS));
        return true;
    }

    public Optional<Impact> correlate(UUID ownerId, long serverTick, Position destination,
                                      long nowNanos) {
        cleanup(nowNanos);
        List<Impact> ownerImpacts = impacts.get(ownerId);
        if (ownerImpacts == null) return Optional.empty();
        Optional<Impact> selected = ownerImpacts.stream()
                .filter(value -> value.serverTick() <= serverTick
                        && serverTick - value.serverTick() <= 1)
                .filter(value -> value.position().distanceSquared(destination) <= MAX_DISTANCE_SQUARED)
                .min(Comparator.comparingDouble(value -> value.position().distanceSquared(destination)));
        selected.ifPresent(ownerImpacts::remove);
        if (ownerImpacts.isEmpty()) impacts.remove(ownerId);
        return selected;
    }

    public void removePearl(UUID pearlId) { pearls.remove(pearlId); }

    public void clearOwner(UUID ownerId) {
        impacts.remove(ownerId);
        pearls.entrySet().removeIf(entry -> entry.getValue().ownerId().equals(ownerId));
    }

    public void cleanup(long nowNanos) {
        pearls.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= nowNanos);
        impacts.values().forEach(values -> values.removeIf(value -> value.expiresAtNanos() <= nowNanos));
        impacts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void clear() { pearls.clear(); impacts.clear(); }
    public int trackedPearls() { return pearls.size(); }
    public int pendingImpacts() { return impacts.values().stream().mapToInt(List::size).sum(); }

    private void evictOldestForOwner(UUID ownerId) {
        long owned = pearls.values().stream().filter(pearl -> pearl.ownerId().equals(ownerId)).count();
        if (owned < MAX_PEARLS_PER_PLAYER) return;
        pearls.entrySet().stream()
                .filter(entry -> entry.getValue().ownerId().equals(ownerId))
                .min(Map.Entry.comparingByValue(Comparator.comparingLong(Pearl::launchedAtNanos)))
                .map(Map.Entry::getKey).ifPresent(pearls::remove);
    }

    private void evictOldestGlobally() {
        if (pearls.size() < MAX_TRACKED_PEARLS) return;
        pearls.entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.comparingLong(Pearl::launchedAtNanos)))
                .map(Map.Entry::getKey).ifPresent(pearls::remove);
    }

    private record Pearl(UUID ownerId, long launchedAtNanos, long expiresAtNanos) { }

    public record Impact(UUID pearlId, boolean aged, long serverTick,
                         Position position, long expiresAtNanos) { }

    public record Position(double x, double y, double z) {
        public double distanceSquared(Position other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
