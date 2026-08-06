package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounds repeated denial chat without affecting authoritative cooldown state. */
public final class DenialThrottle {
    private static final long ZERO = 0L;

    private final Map<Key, Long> lastSent = new HashMap<>();

    public boolean acquire(UUID playerId, RestrictionTarget target,
                           long nowMillis, Duration cooldown) {
        return acquire(playerId, target.id(), nowMillis, cooldown);
    }

    public boolean acquire(UUID playerId, String targetKey,
                           long nowMillis, Duration cooldown) {
        Key key = new Key(playerId, targetKey);
        Long previous = lastSent.get(key);
        long elapsed = previous == null ? Long.MAX_VALUE : elapsed(nowMillis, previous);
        if (elapsed >= ZERO && elapsed < durationMillis(cooldown)) return false;
        lastSent.put(key, nowMillis);
        return true;
    }

    public void discardOutsideWindow(long nowMillis, Duration retention) {
        long oldest = safeSubtract(nowMillis, durationMillis(retention));
        lastSent.values().removeIf(value -> value > nowMillis || value < oldest);
    }

    public void clear() { lastSent.clear(); }

    private static long durationMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return ZERO;
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long elapsed(long nowMillis, long previousMillis) {
        try {
            return Math.subtractExact(nowMillis, previousMillis);
        } catch (ArithmeticException overflow) {
            return nowMillis >= previousMillis ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long safeSubtract(long value, long amount) {
        try {
            return Math.subtractExact(value, amount);
        } catch (ArithmeticException overflow) {
            return Long.MIN_VALUE;
        }
    }

    private record Key(UUID playerId, String targetKey) { }
}
