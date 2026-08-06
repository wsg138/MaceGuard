package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class ProjectileLaunchTracker {
    // Paper invokes these listener paths on the primary server thread. Keeping
    // a plain map preserves the exact-once remove semantics without implying
    // unsupported cross-thread access.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Pending> pending = new HashMap<>();

    void record(UUID projectileId, UUID playerId,
                RestrictionDecision decision, long deadlineNanos) {
        record(projectileId, playerId, decision, null, deadlineNanos);
    }

    void record(UUID projectileId, UUID playerId, RestrictionDecision decision,
                Material concreteMaterial, long deadlineNanos) {
        pending.put(projectileId,
                new Pending(playerId, decision, concreteMaterial, deadlineNanos));
    }

    Optional<Completion> finalizeLaunch(UUID projectileId, boolean cancelled) {
        Pending accepted = pending.remove(projectileId);
        if (accepted == null || cancelled) return Optional.empty();
        return Optional.of(new Completion(accepted.playerId(), accepted.decision(),
                accepted.concreteMaterial()));
    }

    void cleanup(long nowNanos) {
        pending.values().removeIf(value -> value.deadlineNanos() <= nowNanos);
    }

    void clear() { pending.clear(); }
    int size() { return pending.size(); }

    record Completion(UUID playerId, RestrictionDecision decision, Material concreteMaterial) {
        Completion(UUID playerId, RestrictionDecision decision) {
            this(playerId, decision, null);
        }
    }
    private record Pending(UUID playerId, RestrictionDecision decision,
                           Material concreteMaterial, long deadlineNanos) { }
}
