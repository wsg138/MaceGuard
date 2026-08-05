package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StasisPearlTrackerTest {
    private static final StasisPearlTracker.Position HERE =
            new StasisPearlTracker.Position(10.0, 64.0, 10.0);

    @Test void exactThresholdIsAged() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID pearl = UUID.randomUUID();
        tracker.launched(pearl, owner, 1L);
        assertTrue(tracker.landed(pearl, 1_200, 1_200, 50L, HERE, 2L));
        assertTrue(tracker.correlate(owner, 50L, HERE, 3L).orElseThrow().aged());
    }

    @Test void normalAndAgedPearlsDoNotUseBroadOwnerAgeState() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID oldPearl = UUID.randomUUID();
        UUID normalPearl = UUID.randomUUID();
        tracker.launched(oldPearl, owner, 1L);
        tracker.launched(normalPearl, owner, 1L);
        tracker.landed(normalPearl, 20, 1_200, 10L, HERE, 2L);
        assertFalse(tracker.correlate(owner, 10L, HERE, 3L).orElseThrow().aged());
        assertEquals(1, tracker.trackedPearls());
    }

    @Test void simultaneousOwnersNeverCrossCorrelate() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstPearl = UUID.randomUUID();
        UUID secondPearl = UUID.randomUUID();
        tracker.launched(firstPearl, first, 1L);
        tracker.launched(secondPearl, second, 1L);
        tracker.landed(firstPearl, 1_300, 1_200, 30L, HERE, 2L);
        tracker.landed(secondPearl, 30, 1_200, 30L, HERE, 2L);
        assertTrue(tracker.correlate(first, 30L, HERE, 3L).orElseThrow().aged());
        assertFalse(tracker.correlate(second, 30L, HERE, 3L).orElseThrow().aged());
    }

    @Test void canceledOrRemovedPearlCanBeDiscardedWithoutMarkerLeak() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID pearl = UUID.randomUUID();
        tracker.launched(pearl, owner, 1L);
        tracker.removePearl(pearl);
        assertFalse(tracker.landed(pearl, 2_000, 1_200, 1L, HERE, 2L));
        assertEquals(0, tracker.pendingImpacts());
    }

    @Test void launchTrackingIsBoundedPerOwner() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID oldest = null;
        for (int index = 0; index < 40; index++) {
            UUID pearl = UUID.randomUUID();
            if (index == 0) oldest = pearl;
            tracker.launched(pearl, owner, index + 1L);
        }
        assertEquals(32, tracker.trackedPearls());
        assertFalse(tracker.landed(oldest, 2_000, 1_200, 1L, HERE, 50L));
    }

    @Test void cleanupAndOwnerClearBoundMemory() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID pearl = UUID.randomUUID();
        tracker.launched(pearl, owner, 1L);
        tracker.landed(pearl, 1_300, 1_200, 1L, HERE, 2L);
        tracker.cleanup(600_000_000L);
        assertEquals(0, tracker.pendingImpacts());
        tracker.clearOwner(owner);
        assertEquals(0, tracker.trackedPearls());
    }
}
