package com.lincoln.maceguard.warzone.restriction;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Keeps only the short-lived direct-target context that EntityLungeEvent does not expose.
 * It does not infer Lunges from movement or velocity.
 */
public final class LungeTargetTracker {
    private final LongSupplier nanoClock;
    private final long windowNanos;
    private final Map<UUID, Context> contexts = new HashMap<>();

    public LungeTargetTracker(LongSupplier nanoClock, Duration window) {
        this.nanoClock = nanoClock;
        this.windowNanos = window.toNanos();
    }

    public void record(UUID playerId, boolean targetInside) {
        contexts.put(playerId, new Context(Math.addExact(nanoClock.getAsLong(), windowNanos), targetInside));
    }

    public boolean targetInside(UUID playerId) {
        Context context = contexts.get(playerId);
        if (context == null) return false;
        if (nanoClock.getAsLong() > context.deadlineNanos()) {
            contexts.remove(playerId);
            return false;
        }
        return context.targetInside();
    }

    public void remove(UUID playerId) { contexts.remove(playerId); }
    public void clear() { contexts.clear(); }
    public int size() { cleanup(); return contexts.size(); }

    public int cleanup() {
        long now = nanoClock.getAsLong();
        int removed = 0;
        Iterator<Context> iterator = contexts.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().deadlineNanos() >= now) continue;
            iterator.remove();
            removed++;
        }
        return removed;
    }

    private record Context(long deadlineNanos, boolean targetInside) { }
}
