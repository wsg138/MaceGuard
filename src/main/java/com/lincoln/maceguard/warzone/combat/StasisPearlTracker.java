package com.lincoln.maceguard.warzone.combat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded launch cache and ordered impact correlation for PDC-marked Ender Pearls. */
public final class StasisPearlTracker {
    private static final long IMPACT_TTL_NANOS = Duration.ofSeconds(5).toNanos();
    /** An unusual expiry must not acquire a second, tick-based fail-open horizon. */
    private static final long EXPIRED_IMPACT_MAX_TICK_LAG = Long.MAX_VALUE;
    private static final int MAX_TRACKED_PEARLS = 4_096;
    private static final int MAX_PEARLS_PER_PLAYER = 32;
    private static final int MAX_IMPACTS_PER_PLAYER = 8;
    private static final int MAX_OVERFLOW_SEGMENTS_PER_WORLD = 256;
    private static final double DIAGNOSTIC_DISTANCE_SQUARED = 9.0;
    private static final int MIN_AMBIGUOUS_CANDIDATES = 2;
    private static final int MIN_RETAINED_OVERFLOW_COUNT = 2;

    /** PDC remains authoritative if this bounded accelerator evicts. */
    private final Map<UUID, Launch> launches = new ConcurrentHashMap<>();
    /** Per-owner callback order is retained by each ArrayDeque, not by this owner lookup map. */
    private final Map<UUID, ArrayDeque<Impact>> impacts = new ConcurrentHashMap<>();
    /** Ordered compressed segments retain tick and enforcement identity independently. */
    private final Map<UUID, Map<UUID, ArrayDeque<OverflowSegment>>> overflow =
            new ConcurrentHashMap<>();
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
        OverflowCandidates overflowCandidates = overflowCandidates(ownerId, serverTick, destination);
        int candidateCount = saturatedAdd(exact.count(), overflowCandidates.count());
        boolean anyEnforce = exact.enforce() || overflowCandidates.enforce();
        OverflowSegment selectedOverflow = overflowCandidates.selected();
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
            if (!matches(impact.serverTick(), 1L,
                    impact.position().worldId(), serverTick, destination.worldId())) continue;
            count = saturatedAdd(count, 1);
            enforce |= impact.enforce();
            if (selected == null || impact.sequence() < selected.sequence()) selected = impact;
        }
        return new ExactCandidates(selected, count, enforce);
    }

    private OverflowCandidates overflowCandidates(UUID ownerId, long serverTick,
                                                   Position destination) {
        Map<UUID, ArrayDeque<OverflowSegment>> ownerOverflow = overflow.get(ownerId);
        if (ownerOverflow == null) return OverflowCandidates.empty();
        ArrayDeque<OverflowSegment> values = ownerOverflow.get(destination.worldId());
        if (values == null) return OverflowCandidates.empty();
        OverflowSegment selected = null;
        int count = 0;
        boolean enforce = false;
        for (OverflowSegment value : values) {
            if (!matches(value.serverTick(), value.maxTickLag(), value.worldId(), serverTick,
                    destination.worldId())) continue;
            count = saturatedAdd(count, value.count());
            enforce |= value.enforce();
            if (selected == null || value.firstSequence() < selected.firstSequence())
                selected = value;
        }
        return new OverflowCandidates(selected, count, enforce);
    }

    private boolean selectExact(Impact selectedImpact, OverflowSegment selectedOverflow) {
        return selectedImpact != null && (selectedOverflow == null
                || selectedImpact.sequence() <= selectedOverflow.firstSequence());
    }

    private Correlation consumeExact(UUID ownerId, ArrayDeque<Impact> ownerImpacts,
                                     Impact selectedImpact, Position destination,
                                     int candidateCount, boolean anyEnforce) {
        ownerImpacts.remove(selectedImpact);
        if (ownerImpacts.isEmpty()) impacts.remove(ownerId, ownerImpacts);
        boolean distanceMatched = selectedImpact.position().distanceSquared(destination)
                <= DIAGNOSTIC_DISTANCE_SQUARED;
        return new Correlation(Optional.of(selectedImpact), selectedImpact.pearlId(), anyEnforce,
                candidateCount >= MIN_AMBIGUOUS_CANDIDATES, candidateCount, false,
                distanceMatched);
    }

    private Correlation consumeOverflowCandidate(UUID ownerId, OverflowSegment selectedOverflow,
                                                  int candidateCount, boolean anyEnforce) {
        UUID syntheticId = selectedOverflow.representativePearlId();
        consumeOverflow(ownerId, selectedOverflow);
        return new Correlation(Optional.empty(), syntheticId, anyEnforce,
                candidateCount >= MIN_AMBIGUOUS_CANDIDATES, candidateCount, true, false);
    }

    private boolean matches(long impactTick, long maxLag, UUID impactWorld,
                            long teleportTick, UUID teleportWorld) {
        if (!impactWorld.equals(teleportWorld) || teleportTick < impactTick) return false;
        return teleportTick <= safeAdd(impactTick, maxLag);
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
        return new Age(elapsedMillis >= saturatedMillis(minimumAge), false,
                elapsedMillis, AgeSource.WALL_CLOCK);
    }

    private long saturatedMillis(Duration duration) {
        try { return Math.max(0L, duration.toMillis()); }
        catch (ArithmeticException overflowed) { return Long.MAX_VALUE; }
    }

    private long saturatedNanos(Duration duration) {
        try { return Math.max(0L, duration.toNanos()); }
        catch (ArithmeticException overflowed) { return Long.MAX_VALUE; }
    }

    public void removePearl(UUID pearlId) { launches.remove(pearlId); }

    public void clearOwner(UUID ownerId) {
        impacts.remove(ownerId);
        overflow.remove(ownerId);
        for (Map.Entry<UUID, Launch> entry : launches.entrySet()) {
            if (entry.getValue().ownerId().equals(ownerId))
                launches.remove(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Exact impacts use the short ordinary lifetime expected by the targeted Paper callback model.
     * Any unusual expiry becomes an ordered owner/world-scoped fail-closed segment instead of
     * disappearing. Segments merge only when tick, lag, and enforcement state are identical.
     */
    public void cleanup(long nowNanos) {
        for (Map.Entry<UUID, ArrayDeque<Impact>> entry : impacts.entrySet()) {
            ArrayDeque<Impact> ownerImpacts = entry.getValue();
            Iterator<Impact> values = ownerImpacts.iterator();
            while (values.hasNext()) {
                Impact impact = values.next();
                if (impact.expiresAtNanos() > nowNanos) continue;
                values.remove();
                addOverflow(entry.getKey(), impact, true, EXPIRED_IMPACT_MAX_TICK_LAG);
            }
            if (ownerImpacts.isEmpty()) impacts.remove(entry.getKey(), ownerImpacts);
        }
        for (Map.Entry<UUID, Map<UUID, ArrayDeque<OverflowSegment>>> ownerEntry
                : overflow.entrySet()) {
            Map<UUID, ArrayDeque<OverflowSegment>> byWorld = ownerEntry.getValue();
            for (Map.Entry<UUID, ArrayDeque<OverflowSegment>> worldEntry : byWorld.entrySet()) {
                worldEntry.getValue().removeIf(value -> value.count() <= 0);
                if (worldEntry.getValue().isEmpty())
                    byWorld.remove(worldEntry.getKey(), worldEntry.getValue());
            }
            if (byWorld.isEmpty()) overflow.remove(ownerEntry.getKey(), byWorld);
        }
    }

    public void clear() { launches.clear(); impacts.clear(); overflow.clear(); }
    public int trackedPearls() { return launches.size(); }
    public int pendingImpacts() {
        int count = impacts.values().stream().mapToInt(ArrayDeque::size).sum();
        for (Map<UUID, ArrayDeque<OverflowSegment>> byWorld : overflow.values()) {
            for (ArrayDeque<OverflowSegment> values : byWorld.values()) {
                for (OverflowSegment value : values)
                    count = saturatedAdd(count, value.count());
            }
        }
        return count;
    }

    private void addOverflow(UUID ownerId, Impact impact, boolean forceEnforce, long maxTickLag) {
        Map<UUID, ArrayDeque<OverflowSegment>> byWorld = overflow.computeIfAbsent(ownerId,
                ignored -> new ConcurrentHashMap<>());
        UUID worldId = impact.position().worldId();
        ArrayDeque<OverflowSegment> values = byWorld.computeIfAbsent(worldId,
                ignored -> new ArrayDeque<>());
        boolean enforce = forceEnforce || impact.enforce();
        OverflowSegment last = values.peekLast();
        if (last != null && last.canMerge(impact.serverTick(), maxTickLag, enforce)) {
            values.removeLast();
            values.addLast(last.increment());
            return;
        }
        if (values.size() >= MAX_OVERFLOW_SEGMENTS_PER_WORLD) {
            // More than 256 distinct pending ticks cannot occur inside the ordinary five-second
            // callback window. If lifecycle callbacks are missing for longer than that, retain a
            // bounded fail-closed recovery segment rather than expanding memory without limit.
            OverflowSegment oldest = values.removeFirst();
            long recoveryCount = (long) oldest.count() + 1L;
            values.addFirst(new OverflowSegment(oldest.representativePearlId(), worldId,
                    Math.min(oldest.serverTick(), impact.serverTick()), Long.MAX_VALUE,
                    recoveryCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) recoveryCount,
                    true, Math.min(oldest.firstSequence(), impact.sequence())));
            return;
        }
        values.addLast(new OverflowSegment(impact.pearlId(), worldId, impact.serverTick(),
                maxTickLag, 1, enforce, impact.sequence()));
    }

    private void consumeOverflow(UUID ownerId, OverflowSegment selected) {
        Map<UUID, ArrayDeque<OverflowSegment>> byWorld = overflow.get(ownerId);
        if (byWorld == null) return;
        ArrayDeque<OverflowSegment> values = byWorld.get(selected.worldId());
        if (values == null) return;
        consumeSelectedOverflow(byWorld, values, selected);
        ArrayDeque<OverflowSegment> remaining = byWorld.get(selected.worldId());
        if (remaining != null && remaining.isEmpty()) byWorld.remove(selected.worldId(), remaining);
        if (byWorld.isEmpty()) overflow.remove(ownerId, byWorld);
    }

    private void consumeSelectedOverflow(
            Map<UUID, ArrayDeque<OverflowSegment>> byWorld,
            ArrayDeque<OverflowSegment> values,
            OverflowSegment selected) {
        if (selected.count() < MIN_RETAINED_OVERFLOW_COUNT) {
            values.remove(selected);
            return;
        }
        ArrayDeque<OverflowSegment> replacement = new ArrayDeque<>();
        for (OverflowSegment value : values) {
            if (value == selected) replacement.addLast(selected.withCount(selected.count() - 1));
            else replacement.addLast(value);
        }
        byWorld.put(selected.worldId(), replacement);
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
    private record OverflowCandidates(OverflowSegment selected, int count, boolean enforce) {
        static OverflowCandidates empty() { return new OverflowCandidates(null, 0, false); }
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

    private record OverflowSegment(UUID representativePearlId, UUID worldId,
                                   long serverTick, long maxTickLag, int count,
                                   boolean enforce, long firstSequence) {
        boolean canMerge(long nextTick, long nextMaxTickLag, boolean nextEnforce) {
            return serverTick == nextTick && maxTickLag == nextMaxTickLag
                    && enforce == nextEnforce;
        }
        OverflowSegment increment() {
            return withCount(saturatedAdd(count, 1));
        }
        OverflowSegment withCount(int value) {
            return new OverflowSegment(representativePearlId, worldId, serverTick, maxTickLag,
                    value, enforce, firstSequence);
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
