package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Correlates a Lunge-enchanted spear swing with a short, forward velocity change.
 * Ordinary knockback points away from the swing direction and unrelated velocity
 * outside this bounded window is ignored.
 */
public final class LungeAttemptTracker {
    private static final double MIN_FORWARD_DELTA = 0.25D;
    private static final double MIN_ALIGNMENT = 0.72D;
    private static final double MAX_HORIZONTAL_DELTA = 3.5D;
    private static final double MAX_VERTICAL_DELTA = 0.20D;

    private final LongSupplier nanoClock;
    private final long windowNanos;
    private final Map<UUID, Attempt> attempts = new HashMap<>();

    public LungeAttemptTracker(LongSupplier nanoClock, Duration window) {
        this.nanoClock = nanoClock;
        this.windowNanos = window.toNanos();
    }

    public void record(UUID playerId, Vec3 lookDirection, Vec3 initialVelocity, boolean targetInside) {
        Vec3 normalized = new Vec3(lookDirection.x(), 0, lookDirection.z()).normalized();
        if (normalized.lengthSquared() == 0) return;
        attempts.put(playerId, new Attempt(Math.addExact(nanoClock.getAsLong(), windowNanos),
                normalized, initialVelocity, targetInside));
    }

    public void markTargetInside(UUID playerId) {
        Attempt attempt = attempts.get(playerId);
        if (attempt != null) attempts.put(playerId, new Attempt(attempt.deadlineNanos(),
                attempt.lookDirection(), attempt.initialVelocity(), true));
    }

    public Optional<Attempt> consumeIfLunge(UUID playerId, Vec3 resultingVelocity) {
        Attempt attempt = attempts.get(playerId);
        if (attempt == null) return Optional.empty();
        if (nanoClock.getAsLong() > attempt.deadlineNanos()) {
            attempts.remove(playerId);
            return Optional.empty();
        }
        Vec3 delta = resultingVelocity.subtract(attempt.initialVelocity());
        if (Math.abs(delta.y()) > MAX_VERTICAL_DELTA) return Optional.empty();
        Vec3 horizontal = new Vec3(delta.x(), 0, delta.z());
        double length = Math.sqrt(horizontal.lengthSquared());
        if (length < MIN_FORWARD_DELTA) return Optional.empty();
        if (length > MAX_HORIZONTAL_DELTA) return Optional.empty();
        double alignment = horizontal.dot(attempt.lookDirection()) / length;
        if (alignment < MIN_ALIGNMENT || horizontal.dot(attempt.lookDirection()) < MIN_FORWARD_DELTA)
            return Optional.empty();
        attempts.remove(playerId);
        return Optional.of(attempt);
    }

    public void clear() { attempts.clear(); }
    public void remove(UUID playerId) { attempts.remove(playerId); }
    public int size() { return attempts.size(); }

    public record Attempt(long deadlineNanos, Vec3 lookDirection, Vec3 initialVelocity, boolean targetInside) { }

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
