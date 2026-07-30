package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
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

    public void clear() { expiresAt.clear(); }

    public void clearTargets(Set<RestrictionTarget> targets) {
        expiresAt.keySet().removeIf(key -> targets.contains(key.target()));
    }

    public int size() {
        discardExpired();
        return expiresAt.size();
    }

    private record Key(UUID playerId, RestrictionTarget target) { }
}
