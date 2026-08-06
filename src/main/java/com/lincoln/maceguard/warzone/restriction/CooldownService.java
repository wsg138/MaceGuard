package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Authoritative MaceGuard cooldown state. Bukkit item cooldowns are only a visual projection. */
public final class CooldownService {
    private final LongSupplier clock;
    private final Map<Key, ActiveCooldown> active = new HashMap<>();

    public CooldownService(LongSupplier clock) { this.clock = clock; }

    public void start(UUID playerId, RestrictionTarget target, Duration duration) {
        start(playerId, target, duration, null);
    }

    public void start(UUID playerId, RestrictionTarget target, Duration duration,
                      Material concreteMaterial) {
        long expiry = Math.addExact(clock.getAsLong(), duration.toMillis());
        Material visualMaterial = normalizeConcreteMaterial(target, concreteMaterial);
        active.put(new Key(playerId, target), new ActiveCooldown(expiry, visualMaterial));
    }

    public Duration remaining(UUID playerId, RestrictionTarget target) {
        Key key = new Key(playerId, target);
        ActiveCooldown cooldown = active.get(key);
        if (cooldown == null) return Duration.ZERO;
        long remaining = cooldown.expiresAtMillis() - clock.getAsLong();
        if (remaining <= 0) {
            active.remove(key);
            return Duration.ZERO;
        }
        return Duration.ofMillis(remaining);
    }

    public Map<RestrictionTarget, Duration> activeFor(UUID playerId) {
        Map<RestrictionTarget, Duration> result = new LinkedHashMap<>();
        for (Key key : Set.copyOf(active.keySet())) {
            if (!key.playerId().equals(playerId)) continue;
            Duration remaining = remaining(playerId, key.target());
            if (!remaining.isZero()) result.put(key.target(), remaining);
        }
        return Map.copyOf(result);
    }

    /** Concrete visual materials for active cooldowns. Effect-only targets are deliberately absent. */
    public Map<Material, Duration> activeVisualsFor(UUID playerId) {
        return activeVisualsFor(playerId, ignored -> true);
    }

    public Map<Material, Duration> activeVisualsFor(UUID playerId,
                                                     Predicate<RestrictionTarget> included) {
        Map<Material, Duration> result = new LinkedHashMap<>();
        for (Key key : Set.copyOf(active.keySet())) {
            if (!key.playerId().equals(playerId) || !included.test(key.target())) continue;
            Duration remaining = remaining(playerId, key.target());
            if (remaining.isZero()) continue;
            ActiveCooldown cooldown = active.get(key);
            if (cooldown == null || cooldown.visualMaterial() == null) continue;
            result.merge(cooldown.visualMaterial(), remaining,
                    (left, right) -> left.compareTo(right) >= 0 ? left : right);
        }
        return Map.copyOf(result);
    }

    public Material concreteMaterial(UUID playerId, RestrictionTarget target) {
        if (remaining(playerId, target).isZero()) return null;
        ActiveCooldown cooldown = active.get(new Key(playerId, target));
        return cooldown == null ? null : cooldown.visualMaterial();
    }

    public boolean active(UUID playerId, RestrictionTarget target) {
        return !remaining(playerId, target).isZero();
    }

    public int discardExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        Iterator<Map.Entry<Key, ActiveCooldown>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtMillis() > now) continue;
            iterator.remove();
            removed++;
        }
        return removed;
    }

    public Snapshot snapshot() {
        discardExpired();
        return new Snapshot(active.entrySet().stream()
                .map(entry -> new SnapshotEntry(entry.getKey().playerId(),
                        entry.getKey().target(), entry.getValue().expiresAtMillis(),
                        entry.getValue().visualMaterial()))
                .toList());
    }

    /** Restores only still-configured cooldown targets, clamping to any shorter new duration. */
    public void restore(Snapshot snapshot, Map<RestrictionTarget, Duration> allowedDurations) {
        active.clear();
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
        active.put(new Key(entry.playerId(), entry.target()), new ActiveCooldown(expiry,
                normalizeConcreteMaterial(entry.target(), entry.concreteMaterial())));
    }

    private Material normalizeConcreteMaterial(RestrictionTarget target, Material requested) {
        if (target == null) return null;
        if (target.kind() == RestrictionTarget.Kind.MATERIAL) return target.material();
        if (target == RestrictionTarget.SPEAR && RestrictionTarget.isSpear(requested)) return requested;
        return null;
    }

    public void clear() { active.clear(); }
    public void clearTargets(Set<RestrictionTarget> targets) {
        active.keySet().removeIf(key -> targets.contains(key.target()));
    }
    public int size() { discardExpired(); return active.size(); }

    public record Snapshot(List<SnapshotEntry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
        public static Snapshot empty() { return new Snapshot(List.of()); }
    }
    public record SnapshotEntry(UUID playerId, RestrictionTarget target, long expiresAtMillis,
                                Material concreteMaterial) {
        public SnapshotEntry(UUID playerId, RestrictionTarget target, long expiresAtMillis) {
            this(playerId, target, expiresAtMillis, null);
        }
    }
    private record Key(UUID playerId, RestrictionTarget target) { }
    private record ActiveCooldown(long expiresAtMillis, Material visualMaterial) { }
}
