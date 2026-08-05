package com.lincoln.maceguard.warzone.combat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
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
    private final Map<UUID, ArrayDeque<Impact>> impacts = new HashMap<>();

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
        ArrayDeque<Impact> ownerImpacts = impacts.computeIfAbsent(
                pearl.ownerId(), ignored -> new ArrayDeque<>());
        removeExpiredImpacts(ownerImpacts, nowNanos);
        if (ownerImpacts.size() >= MAX_IMPACTS_PER_PLAYER) ownerImpacts.removeFirst();
        ownerImpacts.addLast(new Impact(pearlId, ticksLived >= minimumAgeTicks, serverTick,
                position, nowNanos + IMPACT_TTL_NANOS));
        return true;
    }

    public Optional<Impact> correlate(UUID ownerId, long serverTick, Position destination,
                                      long nowNanos) {
        cleanup(nowNanos);
        ArrayDeque<Impact> ownerImpacts = impacts.get(ownerId);
        if (ownerImpacts == null) return Optional.empty();

        Impact selected = null;
        double selectedDistance = Double.MAX_VALUE;
        for (Impact impact : ownerImpacts) {
            long tickDifference = serverTick - impact.serverTick();
            if (tickDifference < 0 || tickDifference > 1) continue;
            double distance = impact.position().distanceSquared(destination);
            if (distance > MAX_DISTANCE_SQUARED || distance >= selectedDistance) continue;
            selected = impact;
            selectedDistance = distance;
        }
        if (selected == null) return Optional.empty();
        ownerImpacts.remove(selected);
        if (ownerImpacts.isEmpty()) impacts.remove(ownerId);
        return Optional.of(selected);
    }

    public void discardCorrelated(UUID ownerId, long serverTick, Position destination,
                                  long nowNanos) {
        correlate(ownerId, serverTick, destination, nowNanos);
    }

    public void removePearl(UUID pearlId) { pearls.remove(pearlId); }

    public void clearOwner(UUID ownerId) {
        impacts.remove(ownerId);
        pearls.entrySet().removeIf(entry -> entry.getValue().ownerId().equals(ownerId));
    }

    public void cleanup(long nowNanos) {
        pearls.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= nowNanos);
        Iterator<Map.Entry<UUID, ArrayDeque<Impact>>> iterator = impacts.entrySet().iterator();
        while (iterator.hasNext()) {
            ArrayDeque<Impact> ownerImpacts = iterator.next().getValue();
            removeExpiredImpacts(ownerImpacts, nowNanos);
            if (ownerImpacts.isEmpty()) iterator.remove();
        }
    }

    public void clear() { pearls.clear(); impacts.clear(); }
    public int trackedPearls() { return pearls.size(); }

    public int pendingImpacts() {
        int count = 0;
        for (ArrayDeque<Impact> ownerImpacts : impacts.values()) count += ownerImpacts.size();
        return count;
    }

    private void removeExpiredImpacts(ArrayDeque<Impact> ownerImpacts, long nowNanos) {
        ownerImpacts.removeIf(value -> value.expiresAtNanos() <= nowNanos);
    }

    private void evictOldestForOwner(UUID ownerId) {
        int owned = 0;
        UUID oldestId = null;
        long oldestLaunch = Long.MAX_VALUE;
        for (Map.Entry<UUID, Pearl> entry : pearls.entrySet()) {
            Pearl pearl = entry.getValue();
            if (!pearl.ownerId().equals(ownerId)) continue;
            owned++;
            if (pearl.launchedAtNanos() < oldestLaunch) {
                oldestLaunch = pearl.launchedAtNanos();
                oldestId = entry.getKey();
            }
        }
        if (owned >= MAX_PEARLS_PER_PLAYER && oldestId != null) pearls.remove(oldestId);
    }

    private void evictOldestGlobally() {
        if (pearls.size() < MAX_TRACKED_PEARLS) return;
        UUID oldestId = null;
        long oldestLaunch = Long.MAX_VALUE;
        for (Map.Entry<UUID, Pearl> entry : pearls.entrySet()) {
            long launchedAt = entry.getValue().launchedAtNanos();
            if (launchedAt < oldestLaunch) {
                oldestLaunch = launchedAt;
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) pearls.remove(oldestId);
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
