package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Correlates a launched spear item with later projectile damage without NMS. */
public final class SpearProjectileTracker {
    private final Map<UUID, Attempt> attempts = new HashMap<>();

    public void record(UUID projectileId, UUID playerId, Material material,
                       boolean sourceInside, boolean bypass, long expiresAtNanos) {
        if (!RestrictionTarget.isSpear(material)) return;
        attempts.put(projectileId, new Attempt(playerId, material, sourceInside, bypass, expiresAtNanos));
    }

    public Optional<Attempt> find(UUID projectileId, long nowNanos) {
        Attempt attempt = attempts.get(projectileId);
        if (attempt == null) return Optional.empty();
        if (attempt.expiresAtNanos() <= nowNanos) {
            attempts.remove(projectileId);
            return Optional.empty();
        }
        return Optional.of(attempt);
    }

    public Optional<Attempt> remove(UUID projectileId, long nowNanos) {
        Attempt attempt = attempts.remove(projectileId);
        return attempt == null || attempt.expiresAtNanos() <= nowNanos
                ? Optional.empty() : Optional.of(attempt);
    }

    public int cleanup(long nowNanos) {
        int before = attempts.size();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= nowNanos);
        return before - attempts.size();
    }

    public void clear() { attempts.clear(); }
    public int size() { return attempts.size(); }

    public record Attempt(UUID playerId, Material material,
                          boolean sourceInside, boolean bypass, long expiresAtNanos) { }
}
