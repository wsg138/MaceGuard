package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Compatibility gate for Minecraft/Paper 1.21.11, which has no dedicated Lunge event.
 *
 * <p>The gate is armed only by a real, attackable entity hit with a Lunge-enchanted spear.
 * It then accepts one immediate forward velocity delta. It does not arm from animation or
 * generic interaction events, and it compares the complete three-dimensional look vector so
 * legitimate upward/downward Lunges are not discarded.</p>
 */
public final class LungeVelocityGate {
    private static final double MIN_DELTA = 0.20D;
    private static final double MAX_DELTA = 4.50D;
    private static final double MIN_ALIGNMENT = 0.82D;

    private final LongSupplier nanoClock;
    private final long windowNanos;
    private final Map<UUID, Attempt> attempts = new HashMap<>();

    public LungeVelocityGate(LongSupplier nanoClock, Duration window) {
        this.nanoClock = nanoClock;
        this.windowNanos = window.toNanos();
    }

    public void record(UUID playerId, String materialName, Vec3 lookDirection, Vec3 initialVelocity,
                       boolean actorInside, boolean targetInside, RestrictionDecision itemDecision) {
        Vec3 normalized = lookDirection.normalized();
        if (normalized.lengthSquared() == 0) return;
        attempts.put(playerId, new Attempt(Math.addExact(nanoClock.getAsLong(), windowNanos),
                materialName, normalized, initialVelocity, actorInside, targetInside, itemDecision));
    }

    public Optional<Attempt> consumeIfLunge(UUID playerId, Vec3 resultingVelocity) {
        Attempt attempt = attempts.get(playerId);
        if (attempt == null) return Optional.empty();
        if (nanoClock.getAsLong() > attempt.deadlineNanos()) {
            attempts.remove(playerId);
            return Optional.empty();
        }

        Vec3 delta = resultingVelocity.subtract(attempt.initialVelocity());
        double length = Math.sqrt(delta.lengthSquared());
        if (length < MIN_DELTA || length > MAX_DELTA) return Optional.empty();
        double forward = delta.dot(attempt.lookDirection());
        double alignment = forward / length;
        if (forward < MIN_DELTA || alignment < MIN_ALIGNMENT) return Optional.empty();

        attempts.remove(playerId);
        return Optional.of(attempt);
    }

    public void remove(UUID playerId) { attempts.remove(playerId); }
    public void clear() { attempts.clear(); }
    public int size() { cleanup(); return attempts.size(); }

    public int cleanup() {
        long now = nanoClock.getAsLong();
        int removed = 0;
        Iterator<Attempt> iterator = attempts.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().deadlineNanos() >= now) continue;
            iterator.remove();
            removed++;
        }
        return removed;
    }

    public record Attempt(long deadlineNanos, String materialName, Vec3 lookDirection, Vec3 initialVelocity,
                          boolean actorInside, boolean targetInside, RestrictionDecision itemDecision) { }

    public record Vec3(double x, double y, double z) {
        public Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        public double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        public double lengthSquared() { return dot(this); }
        public Vec3 normalized() {
            double length = Math.sqrt(lengthSquared());
            return length == 0 ? this : new Vec3(x / length, y / length, z / length);
        }
    }
}
