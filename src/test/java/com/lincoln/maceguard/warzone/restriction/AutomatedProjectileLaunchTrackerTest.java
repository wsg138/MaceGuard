package com.lincoln.maceguard.warzone.restriction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatedProjectileLaunchTrackerTest {
    @Test void vanillaDispenserLaunchMatchesWithoutAssignedShooter() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        long attempt = tracker.record(world, 10, 64, 10, 100,
                vec(1, 0, 0), 2_000L);

        var match = tracker.match(world, 100,
                vec(11.2, 64.5, 10.5), vec(1, 0, 0), 1_000L);

        assertTrue(match.isPresent());
        assertEquals(attempt, match.orElseThrow().attemptId());
    }

    @Test void finalDispenseVelocityIsUsedForCorrelation() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        tracker.record(world, 0, 64, 0, 20,
                vec(0, 0, 1), 2_000L);

        assertTrue(tracker.match(world, 20,
                vec(0.5, 64.5, 1.2), vec(0, 0, 1), 1_000L).isPresent());
    }

    @Test void cancelledDispenseAttemptLeavesNoPendingState() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        long attempt = tracker.record(world, 0, 64, 0, 30,
                vec(1, 0, 0), 2_000L);

        assertTrue(tracker.cancel(attempt));
        assertEquals(0, tracker.size());
        assertTrue(tracker.match(world, 30,
                vec(1.2, 64.5, 0.5), vec(1, 0, 0), 1_000L).isEmpty());
    }

    @Test void nearbyDispensersInTheSameTickMatchDeterministically() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        long first = tracker.record(world, 0, 64, 0, 40,
                vec(1, 0, 0), 2_000L);
        long second = tracker.record(world, 0, 64, 1, 40,
                vec(1, 0, 0), 2_000L);

        assertEquals(second, tracker.match(world, 40,
                vec(1.2, 64.5, 1.5), vec(1, 0, 0), 1_000L)
                .orElseThrow().attemptId());
        assertEquals(first, tracker.match(world, 40,
                vec(1.2, 64.5, 0.5), vec(1, 0, 0), 1_000L)
                .orElseThrow().attemptId());
    }

    @Test void unrelatedProjectileDoesNotConsumeNearbyPendingAttempt() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        tracker.record(world, 0, 64, 0, 50,
                vec(1, 0, 0), 2_000L);

        assertTrue(tracker.match(world, 50,
                vec(20, 64, 20), vec(1, 0, 0), 1_000L).isEmpty());
        assertEquals(1, tracker.size());
    }

    @Test void pendingAttemptExpiresWhenNoProjectileAppears() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        tracker.record(world, 0, 64, 0, 60,
                vec(1, 0, 0), 1_100L);

        tracker.cleanup(60, 1_100L);
        assertEquals(0, tracker.size());

        tracker.record(world, 0, 64, 0, 60,
                vec(1, 0, 0), 10_000L);
        tracker.cleanup(62, 2_000L);
        assertEquals(0, tracker.size());
    }

    @Test void reloadShutdownAndModifierTransitionClearPendingState() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        tracker.record(UUID.randomUUID(), 0, 64, 0, 70,
                vec(1, 0, 0), 2_000L);

        tracker.clear();
        assertEquals(0, tracker.size());
    }

    @Test void pendingAttemptIsConsumedExactlyOnce() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        tracker.record(world, 0, 64, 0, 80,
                vec(1, 0, 0), 2_000L);

        assertTrue(tracker.match(world, 80,
                vec(1.2, 64.5, 0.5), vec(1, 0, 0), 1_000L).isPresent());
        assertTrue(tracker.match(world, 80,
                vec(1.2, 64.5, 0.5), vec(1, 0, 0), 1_000L).isEmpty());
    }

    @Test void assignedBlockSourceConsumesMatchingPendingAttempt() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        long attempt = tracker.record(world, 4, 65, 7, 90,
                vec(0, 1, 0), 2_000L);

        assertEquals(attempt, tracker.consumeExactSource(
                world, 4, 65, 7, 90, 1_000L).orElseThrow().attemptId());
        assertTrue(tracker.consumeExactSource(
                world, 4, 65, 7, 90, 1_000L).isEmpty());
    }

    @Test void mismatchedDirectionDoesNotMatchAnotherLaunch() {
        AutomatedProjectileLaunchTracker tracker = new AutomatedProjectileLaunchTracker();
        UUID world = UUID.randomUUID();
        tracker.record(world, 0, 64, 0, 100,
                vec(1, 0, 0), 2_000L);

        assertTrue(tracker.match(world, 100,
                vec(-0.2, 64.5, 0.5), vec(-1, 0, 0), 1_000L).isEmpty());
        assertEquals(1, tracker.size());
    }

    private AutomatedProjectileLaunchTracker.Vec3 vec(double x, double y, double z) {
        return new AutomatedProjectileLaunchTracker.Vec3(x, y, z);
    }
}
