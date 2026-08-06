package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StasisPearlTrackerTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final StasisPearlTracker.Position HERE =
            new StasisPearlTracker.Position(WORLD, 10.0, 64.0, 10.0);
    private static final Duration MINIMUM = Duration.ofSeconds(60);

    @Test void elapsedThresholdUsesMonotonicTimeAtAndAroundBoundary() {
        UUID owner = UUID.randomUUID();
        assertFalse(impact(owner, 59_999, 59_999_000_000L).aged());
        assertTrue(impact(owner, 60_000, 60_000_000_000L).aged());
        assertTrue(impact(owner, 60_001, 60_001_000_000L).aged());
    }

    @Test void cacheMissFallsBackToPersistedWallClock() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID pearl = UUID.randomUUID();
        StasisPearlTracker.Impact impact = tracker.landed(pearl,
                metadata(owner, 1_000L), MINIMUM, 50L, HERE,
                61_000L, 5L);
        assertTrue(impact.aged());
        assertEquals(StasisPearlTracker.AgeSource.WALL_CLOCK, impact.ageSource());
    }

    @Test void entityTicksCannotOverrideElapsedTime() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID pearl = UUID.randomUUID();
        tracker.launched(pearl, owner, 1_000L, 1_000_000_000L);
        StasisPearlTracker.Impact impact = tracker.landed(pearl, metadata(owner, 1_000L),
                MINIMUM, 1L, HERE, 61_000L, 61_000_000_000L);
        assertTrue(impact.aged(), "No entity tick count participates in the decision.");
    }

    @Test void thirtyThirdPearlDoesNotMakeFirstPearlUnrestricted() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID first = null;
        for (int index = 0; index < 33; index++) {
            UUID pearl = UUID.randomUUID();
            if (index == 0) first = pearl;
            tracker.launched(pearl, owner, 1_000L + index, 1_000L + index);
        }
        assertEquals(32, tracker.trackedPearls());
        StasisPearlTracker.Impact recovered = tracker.landed(first, metadata(owner, 1_000L),
                MINIMUM, 100L, HERE, 70_000L, 80_000L);
        assertTrue(recovered.aged());
        assertEquals(StasisPearlTracker.AgeSource.WALL_CLOCK, recovered.ageSource());
    }

    @Test void fourThousandNinetySeventhPearlDoesNotCreateGlobalFailOpen() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID firstOwner = UUID.randomUUID();
        UUID firstPearl = UUID.randomUUID();
        tracker.launched(firstPearl, firstOwner, 1_000L, 1L);
        for (int index = 1; index < 4_097; index++)
            tracker.launched(UUID.randomUUID(), UUID.randomUUID(), 1_000L + index, index + 1L);
        assertEquals(4_096, tracker.trackedPearls());
        assertTrue(tracker.landed(firstPearl, metadata(firstOwner, 1_000L), MINIMUM,
                100L, HERE, 70_000L, 10_000L).aged());
    }

    @Test void nineImpactsRetainFailClosedOverflowAndConsumeOneEach() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 9; index++) {
            UUID pearl = UUID.randomUUID();
            tracker.landed(pearl, metadata(owner, 1_000L), MINIMUM,
                    10L, HERE, 70_000L, index + 1L);
        }
        assertEquals(9, tracker.pendingImpacts());
        for (int index = 0; index < 9; index++) {
            StasisPearlTracker.Correlation result = tracker.correlate(owner, 10L, HERE, 20L + index);
            assertTrue(result.matched());
            assertTrue(result.effectiveAged());
            assertEquals(8 - index, tracker.pendingImpacts());
        }
    }

    @Test void oneTeleportConsumesAtMostOneOfTwoPendingImpacts() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        tracker.landed(first, metadata(owner, 1_000L), MINIMUM, 10L, HERE, 70_000L, 1L);
        tracker.landed(second, metadata(owner, 1_000L), MINIMUM, 10L, HERE, 70_000L, 2L);

        assertEquals(first, tracker.correlate(owner, 10L, HERE, 3L).selectedPearlId());
        assertEquals(1, tracker.pendingImpacts());
        assertEquals(second, tracker.correlate(owner, 10L, HERE, 4L).selectedPearlId());
        assertEquals(0, tracker.pendingImpacts());
    }

    @Test void sameTickNormalAndAgedPearlsFailClosedOnlyBecauseImpactSetIsAmbiguous() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        UUID aged = UUID.randomUUID();
        tracker.landed(normal, metadata(owner, 69_500L), MINIMUM, 10L, HERE, 70_000L, 1L);
        tracker.landed(aged, metadata(owner, 1_000L), MINIMUM, 10L, HERE, 70_000L, 2L);

        StasisPearlTracker.Correlation first = tracker.correlate(owner, 10L, HERE, 3L);
        assertEquals(normal, first.selectedPearlId());
        assertTrue(first.ambiguous());
        assertTrue(first.effectiveAged());
        StasisPearlTracker.Correlation second = tracker.correlate(owner, 10L, HERE, 4L);
        assertEquals(aged, second.selectedPearlId());
        assertTrue(second.effectiveAged());
    }

    @Test void twoNormalSameTickPearlsRemainNormalAndOrdered() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        tracker.landed(first, metadata(owner, 69_900L), MINIMUM, 10L, HERE, 70_000L, 1L);
        tracker.landed(second, metadata(owner, 69_900L), MINIMUM, 10L, HERE, 70_000L, 2L);
        assertFalse(tracker.correlate(owner, 10L, HERE, 3L).effectiveAged());
        assertFalse(tracker.correlate(owner, 10L, HERE, 4L).effectiveAged());
    }

    @Test void modifiedDestinationDoesNotBreakOrderedCorrelation() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID pearl = UUID.randomUUID();
        tracker.landed(pearl, metadata(owner, 1_000L), MINIMUM, 10L, HERE, 70_000L, 1L);
        StasisPearlTracker.Position modified = new StasisPearlTracker.Position(WORLD, 500, 90, -500);
        StasisPearlTracker.Correlation result = tracker.correlate(owner, 10L, modified, 2L);
        assertTrue(result.matched());
        assertFalse(result.destinationMatched());
        assertTrue(result.effectiveAged());
    }

    @Test void crossWorldCorrelationRemainsImpossible() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        tracker.landed(UUID.randomUUID(), metadata(owner, 1_000L), MINIMUM,
                10L, HERE, 70_000L, 1L);
        assertFalse(tracker.correlate(owner, 10L,
                new StasisPearlTracker.Position(UUID.randomUUID(), 10, 64, 10), 2L).matched());
        assertEquals(1, tracker.pendingImpacts());
    }

    @Test void unrelatedOldSuspendedPearlDoesNotBlockNewNormalImpact() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        tracker.launched(UUID.randomUUID(), owner, 1_000L, 1L);
        tracker.landed(UUID.randomUUID(), metadata(owner, 69_900L), MINIMUM,
                10L, HERE, 70_000L, 2L);
        assertFalse(tracker.correlate(owner, 10L, HERE, 3L).effectiveAged());
    }

    @Test void invalidMarkedMetadataFailsClosedForAffectedOwner() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        StasisPearlTracker.LaunchMetadata malformed = new StasisPearlTracker.LaunchMetadata(
                owner, 0L, true, "malformed timestamp");
        assertTrue(tracker.landed(UUID.randomUUID(), malformed, MINIMUM,
                10L, HERE, 1L, 1L).enforce());
        assertTrue(tracker.correlate(owner, 10L, HERE, 2L).effectiveAged());
    }

    @Test void expiredAgedImpactCreatesOwnerScopedRecoveryInsteadOfFailOpen() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        tracker.landed(UUID.randomUUID(), metadata(owner, 1_000L), MINIMUM,
                10L, HERE, 70_000L, 1L);
        tracker.cleanup(Duration.ofSeconds(6).toNanos());
        StasisPearlTracker.Correlation recovered = tracker.correlate(owner, 50L, HERE,
                Duration.ofSeconds(6).toNanos() + 1);
        assertTrue(recovered.matched());
        assertTrue(recovered.overflow());
        assertTrue(recovered.effectiveAged());
    }

    @Test void overflowAuthoritySurvivesRepeatedCleanupUntilOneEventConsumesIt() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        tracker.landed(UUID.randomUUID(), metadata(owner, 1_000L), MINIMUM,
                10L, HERE, 70_000L, 1L);

        tracker.cleanup(Duration.ofSeconds(6).toNanos());
        tracker.cleanup(Duration.ofDays(30).toNanos());

        StasisPearlTracker.Correlation recovered = tracker.correlate(owner, 5_000L, HERE,
                Duration.ofDays(30).toNanos() + 1L);
        assertTrue(recovered.matched());
        assertTrue(recovered.overflow());
        assertTrue(recovered.effectiveAged());
        assertEquals(0, tracker.pendingImpacts());
    }

    @Test void unexpectedlyExpiredNormalImpactAlsoUsesFailClosedRecovery() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        tracker.landed(UUID.randomUUID(), metadata(owner, 69_900L), MINIMUM,
                10L, HERE, 70_000L, 1L);
        tracker.cleanup(Duration.ofSeconds(6).toNanos());
        StasisPearlTracker.Correlation recovered = tracker.correlate(owner, 50L, HERE,
                Duration.ofSeconds(6).toNanos() + 1);
        assertTrue(recovered.matched());
        assertTrue(recovered.overflow());
        assertTrue(recovered.effectiveAged(),
                "An unusually delayed marked teleport cannot turn queue expiry into a bypass.");
    }

    @Test void overflowRemainsWorldScopedAcrossManyImpactWorlds() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 8; index++)
            tracker.landed(UUID.randomUUID(), metadata(owner, 1_000L), MINIMUM,
                    10L, HERE, 70_000L, index + 1L);
        StasisPearlTracker.Position overflowWorld = new StasisPearlTracker.Position(
                UUID.randomUUID(), 0, 64, 0);
        tracker.landed(UUID.randomUUID(), metadata(owner, 1_000L), MINIMUM,
                10L, overflowWorld, 70_000L, 20L);
        assertFalse(tracker.correlate(owner, 10L,
                new StasisPearlTracker.Position(UUID.randomUUID(), 0, 64, 0), 21L).matched());
        assertTrue(tracker.correlate(owner, 10L, overflowWorld, 22L).matched());
    }

    @Test void wallClockMovingBackwardFailsClosedAfterCacheLoss() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        StasisPearlTracker.Impact impact = tracker.landed(UUID.randomUUID(),
                metadata(owner, 10_000L), MINIMUM, 10L, HERE, 9_000L, 1L);
        assertTrue(impact.enforce());
        assertTrue(impact.failClosed());
        assertEquals(StasisPearlTracker.AgeSource.INVALID, impact.ageSource());
    }

    private StasisPearlTracker.Impact impact(UUID owner, long elapsedMillis, long elapsedNanos) {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID pearl = UUID.randomUUID();
        tracker.launched(pearl, owner, 1_000L, 1_000_000_000L);
        return tracker.landed(pearl, metadata(owner, 1_000L), MINIMUM, 1L, HERE,
                1_000L + elapsedMillis, 1_000_000_000L + elapsedNanos);
    }

    private StasisPearlTracker.LaunchMetadata metadata(UUID owner, long launchMillis) {
        return new StasisPearlTracker.LaunchMetadata(owner, launchMillis, false, null);
    }
}
