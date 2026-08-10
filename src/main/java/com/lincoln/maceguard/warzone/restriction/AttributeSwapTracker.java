package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks the restricted weapon a player just swapped away from and binds that weapon to the
 * immediately following melee attack. This closes the vanilla/Paper attribute-swap window where
 * combat attributes (notably mace damage or spear reach) can outlive the visible main-hand item.
 */
final class AttributeSwapTracker {
    private final LongSupplier nanoTime;
    private final long windowNanos;
    private final Map<UUID, RecentWeapon> recentWeapons = new HashMap<>();
    private final Map<UUID, AttackAttempt> attacks = new HashMap<>();

    AttributeSwapTracker(LongSupplier nanoTime, Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Attribute-swap window must be positive.");
        }
        this.nanoTime = nanoTime;
        this.windowNanos = window.toNanos();
    }

    void recordTransition(UUID playerId, Material previous, Material current) {
        if (!tracked(previous) || previous == current) return;
        recentWeapons.put(playerId, new RecentWeapon(previous, deadline()));
    }

    Optional<Material> recent(UUID playerId) {
        RecentWeapon recent = recentWeapons.get(playerId);
        if (recent == null) return Optional.empty();
        if (expired(recent.expiresAtNanos())) {
            recentWeapons.remove(playerId, recent);
            return Optional.empty();
        }
        return Optional.of(recent.material());
    }

    void recordAttack(UUID playerId, UUID targetId, Material material,
                      RestrictionDecision itemDecision,
                      RestrictionDecision spearDamageDecision) {
        attacks.put(playerId, new AttackAttempt(targetId, material, itemDecision,
                spearDamageDecision, deadline()));
    }

    Optional<AttackAttempt> findAttack(UUID playerId, UUID targetId) {
        AttackAttempt attempt = attacks.get(playerId);
        if (attempt == null) return Optional.empty();
        if (expired(attempt.expiresAtNanos())) {
            attacks.remove(playerId, attempt);
            return Optional.empty();
        }
        return attempt.targetId().equals(targetId) ? Optional.of(attempt) : Optional.empty();
    }

    Optional<AttackAttempt> consumeAttack(UUID playerId, UUID targetId) {
        Optional<AttackAttempt> attempt = findAttack(playerId, targetId);
        attempt.ifPresent(value -> attacks.remove(playerId, value));
        return attempt;
    }

    void clearPlayer(UUID playerId) {
        recentWeapons.remove(playerId);
        attacks.remove(playerId);
    }

    void cleanup() {
        long now = nanoTime.getAsLong();
        recentWeapons.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() < now);
        attacks.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() < now);
    }

    void clear() {
        recentWeapons.clear();
        attacks.clear();
    }

    private long deadline() {
        long now = nanoTime.getAsLong();
        try {
            return Math.addExact(now, windowNanos);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private boolean expired(long expiresAtNanos) {
        return expiresAtNanos < nanoTime.getAsLong();
    }

    static boolean tracked(Material material) {
        return material == Material.MACE || RestrictionTarget.isSpear(material);
    }

    record AttackAttempt(UUID targetId, Material material,
                         RestrictionDecision itemDecision,
                         RestrictionDecision spearDamageDecision,
                         long expiresAtNanos) { }

    private record RecentWeapon(Material material, long expiresAtNanos) { }
}
