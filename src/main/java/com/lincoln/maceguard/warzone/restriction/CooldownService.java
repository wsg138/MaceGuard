package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class CooldownService {
    private final LongSupplier clock;
    private final Map<Key, Long> expiresAt = new HashMap<>();

    public CooldownService(LongSupplier clock) { this.clock = clock; }

    public void start(UUID playerId, RestrictionTarget target, Duration duration) {
        long expiry = Math.addExact(clock.getAsLong(), duration.toMillis());
        expiresAt.put(new Key(playerId, target), expiry);
    }

    public Duration remaining(UUID playerId, RestrictionTarget target) {
        Key key = new Key(playerId, target);
        Long expiry = expiresAt.get(key);
        if (expiry == null) return Duration.ZERO;
        long remaining = expiry - clock.getAsLong();
        if (remaining <= 0) {
            expiresAt.remove(key);
            return Duration.ZERO;
        }
        return Duration.ofMillis(remaining);
    }

    public Map<RestrictionTarget, Duration> activeFor(UUID playerId) {
        Map<RestrictionTarget, Duration> result = new LinkedHashMap<>();
        for (Key key : Set.copyOf(expiresAt.keySet())) {
            if (!key.playerId().equals(playerId)) continue;
            Duration remaining = remaining(playerId, key.target());
            if (!remaining.isZero()) result.put(key.target(), remaining);
        }
        return Map.copyOf(result);
    }

    public boolean active(UUID playerId, RestrictionTarget target) {
        return !remaining(playerId, target).isZero();
    }

    public int discardExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        Iterator<Map.Entry<Key, Long>> iterator = expiresAt.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() > now) continue;
            iterator.remove();
            removed++;
        }
        return removed;
    }

    public Snapshot snapshot() {
        discardExpired();
        return new Snapshot(expiresAt.entrySet().stream()
                .map(entry -> new SnapshotEntry(entry.getKey().playerId(),
                        entry.getKey().target(), entry.getValue()))
                .toList());
    }

    /** Restores only still-configured cooldown targets, clamping to any shorter new duration. */
    public void restore(Snapshot snapshot, Map<RestrictionTarget, Duration> allowedDurations) {
        expiresAt.clear();
        long now = clock.getAsLong();
        for (SnapshotEntry entry : snapshot.entries()) {
            Duration maximum = allowedDurations.get(entry.target());
            if (maximum == null || maximum.isZero() || maximum.isNegative()) continue;
            long capped;
            try { capped = Math.addExact(now, maximum.toMillis()); }
            catch (ArithmeticException overflow) { capped = Long.MAX_VALUE; }
            long expiry = Math.min(entry.expiresAtMillis(), capped);
            if (expiry > now) restoreEntry(entry, expiry);
        }
    }

    private void restoreEntry(SnapshotEntry entry, long expiry) {
        expiresAt.put(new Key(entry.playerId(), entry.target()), expiry);
    }

    public void clear() { expiresAt.clear(); }
    public void clearTargets(Set<RestrictionTarget> targets) {
        expiresAt.keySet().removeIf(key -> targets.contains(key.target()));
    }
    public int size() { discardExpired(); return expiresAt.size(); }

    public record Snapshot(List<SnapshotEntry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
        public static Snapshot empty() { return new Snapshot(List.of()); }
    }
    public record SnapshotEntry(UUID playerId, RestrictionTarget target, long expiresAtMillis) { }
    private record Key(UUID playerId, RestrictionTarget target) { }
}
