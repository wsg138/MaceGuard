package com.lincoln.maceguard.warzone.restriction;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class ProjectileLaunchTracker {
    // Listener access is confined to Paper's server thread; no concurrent
    // mutation exists to justify the overhead or semantics of a concurrent map.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Pending> pending = new HashMap<>();

    void record(UUID projectileId, UUID playerId,
                RestrictionDecision decision, long deadlineNanos) {
        pending.put(projectileId,
                new Pending(playerId, decision, deadlineNanos));
    }

    Optional<Completion> finalizeLaunch(UUID projectileId, boolean cancelled) {
        Pending accepted = pending.remove(projectileId);
        if (accepted == null || cancelled) return Optional.empty();
        return Optional.of(new Completion(accepted.playerId(), accepted.decision()));
    }

    void cleanup(long nowNanos) {
        pending.values().removeIf(value -> value.deadlineNanos() <= nowNanos);
    }

    void clear() {
        pending.clear();
    }

    int size() {
        return pending.size();
    }

    record Completion(UUID playerId, RestrictionDecision decision) { }
    private record Pending(UUID playerId, RestrictionDecision decision,
                           long deadlineNanos) { }
}
