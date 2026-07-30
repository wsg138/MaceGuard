package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DenialThrottle {
    private final Map<Key, Long> lastSent = new HashMap<>();

    public boolean acquire(UUID playerId, RestrictionTarget target, long nowMillis, Duration cooldown) {
        Key key = new Key(playerId, target);
        Long previous = lastSent.get(key);
        if (previous != null && nowMillis - previous < cooldown.toMillis()) return false;
        lastSent.put(key, nowMillis);
        return true;
    }

    public void discardOlderThan(long thresholdMillis) {
        lastSent.values().removeIf(value -> value < thresholdMillis);
    }

    public void clear() { lastSent.clear(); }

    private record Key(UUID playerId, RestrictionTarget target) { }
}
