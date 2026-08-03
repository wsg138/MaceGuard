package com.lincoln.maceguard.warzone.restriction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Correlates the short synchronous gap between a dispenser's final
 * {@code BlockDispenseEvent} state and the Wind Charge's
 * {@code ProjectileLaunchEvent}. Vanilla Paper assigns the block projectile
 * source only after the launch event has already fired, so correlation cannot
 * depend on {@code Projectile#getShooter()} being populated.
 */
final class AutomatedProjectileLaunchTracker {
    private static final double MAX_SOURCE_DISTANCE_SQUARED = 4.0;
    private static final double MIN_DIRECTION_DOT = 0.25;
    private static final double EPSILON = 1.0E-9;

    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private long nextSequence;

    long record(UUID worldId, int blockX, int blockY, int blockZ,
                long serverTick, Vec3 finalDispenseVelocity,
                long deadlineNanos) {
        return record(worldId, blockX, blockY, blockZ, serverTick, false,
                finalDispenseVelocity, deadlineNanos);
    }

    long record(UUID worldId, int blockX, int blockY, int blockZ,
                long serverTick, boolean sourceInside,
                Vec3 finalDispenseVelocity, long deadlineNanos) {
        long id = ++nextSequence;
        pending.put(id, new Pending(id, worldId, blockX, blockY, blockZ,
                serverTick, sourceInside, normalize(finalDispenseVelocity),
                deadlineNanos));
        return id;
    }

    Optional<Match> match(UUID worldId, long serverTick, Vec3 launchLocation,
                          Vec3 projectileVelocity, long nowNanos) {
        cleanup(serverTick, nowNanos);
        Vec3 normalizedProjectileVelocity = normalize(projectileVelocity);
        Pending best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (Pending candidate : pending.values()) {
            if (!candidate.worldId().equals(worldId)) continue;
            long tickDelta = serverTick - candidate.serverTick();
            if (tickDelta < 0 || tickDelta > 1) continue;

            Vec3 displacement = launchLocation.subtract(candidate.sourceCenter());
            double sourceDistanceSquared = displacement.lengthSquared();
            if (sourceDistanceSquared > MAX_SOURCE_DISTANCE_SQUARED) continue;
            if (!directionCompatible(candidate.direction(),
                    normalizedProjectileVelocity)) continue;

            // Paper fixes the spawn position from the dispenser facing before
            // BlockDispenseEvent, but uses the event's final velocity for the
            // projectile. Score by source proximity and verify direction only
            // against the actual projectile velocity.
            double score = sourceDistanceSquared;
            if (best == null || score < bestScore - EPSILON
                    || (Math.abs(score - bestScore) <= EPSILON
                    && candidate.id() < best.id())) {
                best = candidate;
                bestScore = score;
            }
        }

        if (best == null) return Optional.empty();
        pending.remove(best.id());
        return Optional.of(best.match());
    }

    Optional<Match> consumeExactSource(UUID worldId, int blockX, int blockY,
                                       int blockZ, long serverTick,
                                       long nowNanos) {
        cleanup(serverTick, nowNanos);
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending candidate = iterator.next();
            long tickDelta = serverTick - candidate.serverTick();
            if (candidate.worldId().equals(worldId)
                    && candidate.blockX() == blockX
                    && candidate.blockY() == blockY
                    && candidate.blockZ() == blockZ
                    && tickDelta >= 0 && tickDelta <= 1) {
                iterator.remove();
                return Optional.of(candidate.match());
            }
        }
        return Optional.empty();
    }

    boolean cancel(long attemptId) {
        return pending.remove(attemptId) != null;
    }

    void cleanup(long serverTick, long nowNanos) {
        pending.values().removeIf(value -> serverTick - value.serverTick() > 1
                || nowNanos >= value.deadlineNanos());
    }

    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }

    private boolean directionCompatible(Vec3 expectedDirection,
                                        Vec3 projectileDirection) {
        if (expectedDirection.lengthSquared() <= EPSILON
                || projectileDirection.lengthSquared() <= EPSILON) return true;
        return expectedDirection.dot(projectileDirection) >= MIN_DIRECTION_DOT;
    }

    private Vec3 normalize(Vec3 value) {
        double lengthSquared = value.lengthSquared();
        if (lengthSquared <= EPSILON) return Vec3.ZERO;
        return value.multiply(1.0 / Math.sqrt(lengthSquared));
    }

    record Match(long attemptId, UUID worldId, int blockX, int blockY,
                 int blockZ, long serverTick, boolean sourceInside) { }

    record Vec3(double x, double y, double z) {
        static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

        Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        Vec3 multiply(double scalar) {
            return new Vec3(x * scalar, y * scalar, z * scalar);
        }

        double dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        double lengthSquared() {
            return dot(this);
        }
    }

    private record Pending(long id, UUID worldId, int blockX, int blockY,
                           int blockZ, long serverTick, boolean sourceInside,
                           Vec3 direction, long deadlineNanos) {
        Vec3 sourceCenter() {
            return new Vec3(blockX + 0.5, blockY + 0.5, blockZ + 0.5);
        }

        Match match() {
            return new Match(id, worldId, blockX, blockY, blockZ, serverTick,
                    sourceInside);
        }
    }
}
