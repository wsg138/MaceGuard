package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StasisPearlOverflowSegmentationTest {
    private static final Duration MINIMUM = Duration.ofSeconds(60);
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID FILLER_WORLD = UUID.randomUUID();
    private static final StasisPearlTracker.Position HERE =
            new StasisPearlTracker.Position(WORLD, 0, 64, 0);

    @Test void separatedNormalAndAgedOverflowTicksRemainIndependent() {
        StasisPearlTracker tracker = saturatedTracker();
        UUID owner = OWNER.get();
        UUID normal = land(tracker, owner, 100L, 69_900L, HERE, 20L);
        UUID aged = land(tracker, owner, 200L, 1_000L, HERE, 21L);

        StasisPearlTracker.Correlation first = tracker.correlate(owner, 100L, HERE, 30L);
        assertEquals(normal, first.selectedPearlId());
        assertFalse(first.effectiveAged());
        assertFalse(tracker.correlate(owner, 150L, HERE, 31L).matched());
        StasisPearlTracker.Correlation second = tracker.correlate(owner, 200L, HERE, 32L);
        assertEquals(aged, second.selectedPearlId());
        assertTrue(second.effectiveAged());
    }

    @Test void reverseAgeOrderingDoesNotContaminateLaterNormalImpact() {
        StasisPearlTracker tracker = saturatedTracker();
        UUID owner = OWNER.get();
        UUID aged = land(tracker, owner, 100L, 1_000L, HERE, 20L);
        UUID normal = land(tracker, owner, 200L, 69_900L, HERE, 21L);

        assertEquals(aged, tracker.correlate(owner, 100L, HERE, 30L).selectedPearlId());
        assertFalse(tracker.correlate(owner, 150L, HERE, 31L).matched());
        StasisPearlTracker.Correlation later = tracker.correlate(owner, 200L, HERE, 32L);
        assertEquals(normal, later.selectedPearlId());
        assertFalse(later.effectiveAged());
    }

    @Test void threeSeparatedTicksConsumeOnlyTheirOwnSegments() {
        StasisPearlTracker tracker = saturatedTracker();
        UUID owner = OWNER.get();
        UUID first = land(tracker, owner, 100L, 69_900L, HERE, 20L);
        UUID second = land(tracker, owner, 200L, 1_000L, HERE, 21L);
        UUID third = land(tracker, owner, 300L, 69_900L, HERE, 22L);

        assertEquals(first, tracker.correlate(owner, 100L, HERE, 30L).selectedPearlId());
        assertFalse(tracker.correlate(owner, 150L, HERE, 31L).matched());
        assertEquals(second, tracker.correlate(owner, 200L, HERE, 32L).selectedPearlId());
        assertFalse(tracker.correlate(owner, 250L, HERE, 33L).matched());
        StasisPearlTracker.Correlation last = tracker.correlate(owner, 300L, HERE, 34L);
        assertEquals(third, last.selectedPearlId());
        assertFalse(last.effectiveAged());
    }

    @Test void overflowSegmentsRemainWorldScoped() {
        StasisPearlTracker tracker = saturatedTracker();
        UUID owner = OWNER.get();
        StasisPearlTracker.Position other = new StasisPearlTracker.Position(
                UUID.randomUUID(), 0, 64, 0);
        UUID first = land(tracker, owner, 100L, 69_900L, HERE, 20L);
        UUID second = land(tracker, owner, 100L, 1_000L, other, 21L);

        assertEquals(first, tracker.correlate(owner, 100L, HERE, 30L).selectedPearlId());
        assertEquals(second, tracker.correlate(owner, 100L, other, 31L).selectedPearlId());
    }

    private static final ThreadLocal<UUID> OWNER = new ThreadLocal<>();

    private StasisPearlTracker saturatedTracker() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        OWNER.set(owner);
        StasisPearlTracker.Position filler = new StasisPearlTracker.Position(
                FILLER_WORLD, 0, 64, 0);
        for (int index = 0; index < 8; index++)
            land(tracker, owner, 10L, 69_900L, filler, index + 1L);
        return tracker;
    }

    private UUID land(StasisPearlTracker tracker, UUID owner, long tick, long launchMillis,
                      StasisPearlTracker.Position position, long nowNanos) {
        UUID pearl = UUID.randomUUID();
        tracker.landed(pearl, new StasisPearlTracker.LaunchMetadata(owner, launchMillis,
                        false, null), MINIMUM, tick, position, 70_000L, nowNanos);
        return pearl;
    }
}
