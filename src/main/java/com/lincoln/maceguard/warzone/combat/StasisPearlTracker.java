package com.lincoln.maceguard.warzone.combat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded launch cache and ordered impact correlation for PDC-marked Ender Pearls. */
public final class StasisPearlTracker {
    private static final long IMPACT_TTL_NANOS = Duration.ofSeconds(5).toNanos();
    /** An unusual expiry must not acquire a second, tick-based fail-open horizon. */
    private static final long EXPIRED_IMPACT_MAX_TICK_LAG = Long.MAX_VALUE;
    private static final int MAX_TRACKED_PEARLS = 4_096;
    private static final int MAX_PEARLS_PER_PLAYER = 32;
    private static final int MAX_IMPACTS_PER_PLAYER = 8;
    private static final double DIAGNOSTIC_DISTANCE_SQUARED = 9.0;
    private static final int MIN_AMBIGUOUS_CANDIDATES = 2;
    private static final int MIN_RETAINED_OVERFLOW_COUNT = 2;

    // Bukkit-primary-thread state. PDC remains authoritative if this bounded accelerator evicts.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Launch> launches = new HashMap<>();
    // Bukkit-primary-thread ordered queues preserve verified callback order.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, ArrayDeque<Impact>> impacts = new HashMap<>();
    /** One count-based overflow bucket per affected owner/world; memory does not grow per impact. */
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Map<UUID, Overflow>> overflow = new HashMap<>();
    private long nextSequence;

    public void launched(UUID pearlId, UUID ownerId, long launchEpochMillis, long launchNanos) {
        cleanup(launchNanos);
        evictOldestForOwner(ownerId);
        evictOldestGlobally();
        launches.put(pearlId, new Launch(ownerId, launchEpochMillis, launchNanos));
    }

    public Impact landed(UUID pearlId, LaunchMetadata metadata, Duration minimumAge,
                         long serverTick, Position position, long nowMillis, long nowNanos) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(minimumAge, "minimumAge");
        Objects.requireNonNull(position, "position");
        cleanup(nowNanos);
        Launch cached = launches.remove(pearlId);
        Age age = age(cached, metadata, minimumAge, nowMillis, nowNanos);
        Impact impact = new Impact(pearlId, metadata.ownerId(), age.aged(), age.failClosed(),
                age.elapsedMillis(), age.source(), serverTick, position,
                safeAdd(nowNanos, IMPACT_TTL_NANOS), nextSequence++);
        ArrayDeque<Impact> ownerImpacts = impacts.computeIfAbsent(
                metadata.ownerId(), ignored -> new ArrayDeque<>());
        if (ownerImpacts.size() >= MAX_IMPACTS_PER_PLAYER) {
            addOverflow(metadata.ownerId(), impact, false, 1L);
        } else ownerImpacts.addLast(impact);
        return impact;
    }

    /**
     * Correlates in per-owner callback order. Destination distance is diagnostic only because
     * another plugin may mutate the teleport destination before this handler runs.
     */
    public Correlation correlate(UUID ownerId, long serverTick, Position destination,
                                 long nowNanos) {
        cleanup(nowNanos);
        ArrayDeque<Impact> ownerImpacts = impacts.get(ownerId);
        ExactCandidates exact = exactCandidates(ownerImpacts, serverTick, destination);
        Overflow selectedOverflow = matchingOverflow(ownerId, serverTick, destination);
        int candidateCount = exact.count();
        boolean anyEnforce = exact.enforce();
        if (selectedOverflow != null) {
            candidateCount = saturatedAdd(candidateCount, selectedOverflow.count());
            anyEnforce |= selectedOverflow.enforce();
        }
        if (exact.selected() == null && selectedOverflow == null) return Correlation.none();
        if (selectExact(exact.selected(), selectedOverflow))
            return consumeExact(ownerId, ownerImpacts, exact.selected(), destination,
                    candidateCount, anyEnforce);
        return consumeOverflowCandidate(ownerId, selectedOverflow, candidateCount, anyEnforce);
    }

    private ExactCandidates exactCandidates(ArrayDeque<Impact> ownerImpacts, long serverTick,
                                             Position destination) {
        if (ownerImpacts == null) return ExactCandidates.empty();
        int count = 0;
        boolean enforce = false;
        Impact selected = null;
        for (Impact impact : ownerImpacts) {
            if (!matches(impact.serverTick(), impact.serverTick(), 1L,
                    impact.position().worldId(), serverTick, destination.worldId())) continue;
            count = saturatedAdd(count, 1);
            enforce |= impact.enforce();
            if (selected == null || impact.sequence() < selected.sequence()) selected = impact;
        }
        return new ExactCandidates(selected, count, enforce);
    }

    private Overflow matchingOverflow(UUID ownerId, long serverTick, Position destination) {
        Map<UUID, Overflow> ownerOverflow = overflow.get(ownerId);
        if (ownerOverflow == null) return null;
        Overflow value = ownerOverflow.get(destination.worldId());
        if (value == null) return null;
        return matches(value.firstServerTick(), value.lastServerTick(), value.maxTickLag(),
                value.worldId(), serverTick, destination.worldId()) ? value : null;
    }

    private boolean selectExact(Impact selectedImpact, Overflow selectedOverflow) {
        return selectedImpact != null && (selectedOverflow == null
                || selectedImpact.sequence() <= selectedOverflow.firstSequence());
    }

    private Correlation consumeExact(UUID ownerId, ArrayDeque<Impact> ownerImpacts,
                                     Impact selectedImpact, Position destination,
                                     int candidateCount, boolean anyEnforce) {
        ownerImpacts.remove(selectedImpact);
        if (ownerImpacts.isEmpty()) impacts.remove(ownerId);
        boolean distanceMatched = selectedImpact.position().distanceSquared(destination)
                <= DIAGNOSTIC_DISTANCE_SQUARED;
        return new Correlation(Optional.of(selectedImpact), selectedImpact.pearlId(), anyEnforce,
                candidateCount >= MIN_AMBIGUOUS_CANDIDATES, candidateCount, false,
                distanceMatched);
    }

    private Correlation consumeOverflowCandidate(UUID ownerId, Overflow selectedOverflow,
                                                  int candidateCount, boolean anyEnforce) {
        UUID syntheticId = selectedOverflow.representativePearlId();
        consumeOverflow(ownerId, selectedOverflow);
        return new Correlation(Optional.empty(), syntheticId, anyEnforce,
                candidateCount >= MIN_AMBIGUOUS_CANDIDATES, candidateCount, true, false);
    }

    private boolean matches(long firstImpactTick, long lastImpactTick, long maxLag, UUID impactWorld,
                            long teleportTick, UUID teleportWorld) {
        if (!impactWorld.equals(teleportWorld)) return false;
        return teleportTick >= firstImpactTick && teleportTick <= safeAdd(lastImpactTick, maxLag);
    }

    private Age age(Launch cached, LaunchMetadata metadata, Duration minimumAge,
                    long nowMillis, long nowNanos) {
        if (metadata.failClosed()) return new Age(true, true, Long.MAX_VALUE, AgeSource.INVALID);
        long thresholdNanos = saturatedNanos(minimumAge);
        if (cached != null && cached.ownerId().equals(metadata.ownerId())
                && cached.launchEpochMillis() == metadata.launchEpochMillis()
                && nowNanos >= cached.launchNanos()) {
            long elapsedNanos = nowNanos - cached.launchNanos();
            return new Age(elapsedNanos >= thresholdNanos, false,
                    Duration.ofNanos(elapsedNanos).toMillis(), AgeSource.MONOTONIC);
        }
        if (nowMillis < metadata.launchEpochMillis())
            return new Age(true, true, Long.MAX_VALUE, AgeSource.INVALID);
        long elapsedMillis = nowMillis - metadata.launchEpochMillis();
        return new Age(elapsedMillis >= Math.max(0L, minimumAge.toMillis()), false,
                elapsedMillis, AgeSource.WALL_CLOCK);
    }

    private long saturatedNanos(Duration duration) {
        try { return Math.max(0L, duration.toNanos()); }
        catch (ArithmeticException overflowed) { return Long.MAX_VALUE; }
    }

    public void removePearl(UUID pearlId) { launches.remove(pearlId); }

    public void clearOwner(UUID ownerId) {
        impacts.remove(ownerId);
        overflow.remove(ownerId);
        launches.entrySet().removeIf(entry -> entry.getValue().ownerId().equals(ownerId));
    }

    /**
     * Exact impacts use the short ordinary lifetime expected by the targeted Paper callback model.
     * Any unusual expiry becomes owner/world-scoped fail-closed overflow instead of disappearing.
     * Overflow is count-bounded and survives time cleanup until one event consumes each record or
     * lifecycle cleanup clears the affected owner; neither time nor tick lag creates a bypass.
     */
    public void cleanup(long nowNanos) {
        Iterator<Map.Entry<UUID, ArrayDeque<Impact>>> impactIterator = impacts.entrySet().iterator();
        while (impactIterator.hasNext()) {
            Map.Entry<UUID, ArrayDeque<Impact>> entry = impactIterator.next();
            ArrayDeque<Impact> ownerImpacts = entry.getValue();
            Iterator<Impact> values = ownerImpacts.iterator();
            while (values.hasNext()) {
                Impact impact = values.next();
                if (impact.expiresAtNanos() > nowNanos) continue;
                values.remove();
                addOverflow(entry.getKey(), impact, true, EXPIRED_IMPACT_MAX_TICK_LAG);
            }
            if (ownerImpacts.isEmpty()) impactIterator.remove();
        }
        Iterator<Map.Entry<UUID, Map<UUID, Overflow>>> overflowIterator =
                overflow.entrySet().iterator();
        while (overflowIterator.hasNext()) {
            Map<UUID, Overflow> values = overflowIterator.next().getValue();
            values.entrySet().removeIf(entry -> entry.getValue().count() <= 0);
            if (values.isEmpty()) overflowIterator.remove();
        }
    }

    public void clear() { launches.clear(); impacts.clear(); overflow.clear(); }
    public int trackedPearls() { return launches.size(); }
    public int pendingImpacts() {
        int count = impacts.values().stream().mapToInt(ArrayDeque::size).sum();
        for (Map<UUID, Overflow> byWorld : overflow.values()) {
            for (Overflow value : byWorld.values()) count = saturatedAdd(count, value.count());
        }
        return count;
    }

    private void addOverflow(UUID ownerId, Impact impact, boolean forceEnforce, long maxTickLag) {
        Map<UUID, Overflow> byWorld = overflow.computeIfAbsent(ownerId,
                ignored -> new LinkedHashMap<>());
        UUID worldId = impact.position().worldId();
        Overflow existing = byWorld.get(worldId);
        boolean enforce = forceEnforce || impact.enforce();
        if (existing == null) {
            byWorld.put(worldId, new Overflow(impact.pearlId(), worldId, impact.serverTick(),
                    impact.serverTick(), maxTickLag, 1, enforce, impact.sequence()));
            return;
        }
        byWorld.put(worldId, existing.merge(impact, enforce, maxTickLag));
    }

    private void consumeOverflow(UUID ownerId, Overflow selected) {
        Map<UUID, Overflow> values = overflow.get(ownerId);
        if (values == null) return;
        if (selected.count() >= MIN_RETAINED_OVERFLOW_COUNT)
            values.put(selected.worldId(), selected.withCount(selected.count() - 1));
        else values.remove(selected.worldId());
        if (values.isEmpty()) overflow.remove(ownerId);
    }

    private void evictOldestForOwner(UUID ownerId) {
        int owned = 0;
        UUID oldestId = null;
        long oldestLaunch = Long.MAX_VALUE;
        for (Map.Entry<UUID, Launch> entry : launches.entrySet()) {
            Launch launch = entry.getValue();
            if (!launch.ownerId().equals(ownerId)) continue;
            owned++;
            if (launch.launchNanos() < oldestLaunch) {
                oldestLaunch = launch.launchNanos();
                oldestId = entry.getKey();
            }
        }
        if (owned >= MAX_PEARLS_PER_PLAYER && oldestId != null) launches.remove(oldestId);
    }

    private void evictOldestGlobally() {
        if (launches.size() < MAX_TRACKED_PEARLS) return;
        UUID oldestId = null;
        long oldestLaunch = Long.MAX_VALUE;
        for (Map.Entry<UUID, Launch> entry : launches.entrySet()) {
            if (entry.getValue().launchNanos() < oldestLaunch) {
                oldestLaunch = entry.getValue().launchNanos();
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) launches.remove(oldestId);
    }

    private static int saturatedAdd(int left, int right) {
        long value = (long) left + right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long safeAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record ExactCandidates(Impact selected, int count, boolean enforce) {
        static ExactCandidates empty() { return new ExactCandidates(null, 0, false); }
    }
    private record Launch(UUID ownerId, long launchEpochMillis, long launchNanos) { }
    private record Age(boolean aged, boolean failClosed, long elapsedMillis, AgeSource source) { }
    public enum AgeSource { MONOTONIC, WALL_CLOCK, INVALID }

    public record LaunchMetadata(UUID ownerId, long launchEpochMillis,
                                 boolean failClosed, String diagnostic) {
        public LaunchMetadata { Objects.requireNonNull(ownerId, "ownerId"); }
    }

    public record Impact(UUID pearlId, UUID ownerId, boolean aged, boolean failClosed,
                         long elapsedMillis, AgeSource ageSource, long serverTick,
                         Position position, long expiresAtNanos, long sequence) {
        public boolean enforce() { return aged || failClosed; }
    }

    public record Correlation(Optional<Impact> impact, UUID selectedPearlId,
                              boolean effectiveAged, boolean ambiguous, int candidateCount,
                              boolean overflow, boolean destinationMatched) {
        static Correlation none() {
            return new Correlation(Optional.empty(), null, false, false, 0, false, false);
        }
        public boolean matched() { return selectedPearlId != null; }
    }

    private record Overflow(UUID representativePearlId, UUID worldId,
                            long firstServerTick, long lastServerTick, long maxTickLag,
                            int count, boolean enforce, long firstSequence) {
        Overflow merge(Impact impact, boolean nextEnforce, long nextMaxTickLag) {
            int mergedCount = saturatedAdd(count, 1);
            boolean older = impact.sequence() < firstSequence;
            return new Overflow(older ? impact.pearlId() : representativePearlId, worldId,
                    Math.min(firstServerTick, impact.serverTick()),
                    Math.max(lastServerTick, impact.serverTick()),
                    Math.max(maxTickLag, nextMaxTickLag), mergedCount,
                    enforce || nextEnforce, Math.min(firstSequence, impact.sequence()));
        }
        Overflow withCount(int value) {
            return new Overflow(representativePearlId, worldId, firstServerTick, lastServerTick,
                    maxTickLag, value, enforce, firstSequence);
        }
    }

    public record Position(UUID worldId, double x, double y, double z) {
        public Position { Objects.requireNonNull(worldId, "worldId"); }
        public double distanceSquared(Position other) {
            if (!worldId.equals(other.worldId)) return Double.POSITIVE_INFINITY;
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
