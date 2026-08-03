package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectileLaunchTrackerTest {
    private static final RestrictionTarget PEARL =
            RestrictionTarget.parse("ENDER_PEARL").orElseThrow();

    @Test void successfulFinalLaunchCompletesExactlyOnce() {
        ProjectileLaunchTracker tracker = new ProjectileLaunchTracker();
        UUID projectile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        RestrictionDecision decision = cooldownDecision();
        tracker.record(projectile, player, decision, 5_000L);

        var completion = tracker.finalizeLaunch(projectile, false);
        assertTrue(completion.isPresent());
        assertEquals(player, completion.orElseThrow().playerId());
        assertSame(decision, completion.orElseThrow().decision());
        assertTrue(tracker.finalizeLaunch(projectile, false).isEmpty(),
                "A duplicate ProjectileLaunchEvent must not start a second cooldown.");
    }

    @Test void cancelledFinalLaunchConsumesPendingStateWithoutCompleting() {
        ProjectileLaunchTracker tracker = new ProjectileLaunchTracker();
        UUID projectile = UUID.randomUUID();
        tracker.record(projectile, UUID.randomUUID(), cooldownDecision(), 5_000L);

        assertTrue(tracker.finalizeLaunch(projectile, true).isEmpty());
        assertEquals(0, tracker.size());
        assertTrue(tracker.finalizeLaunch(projectile, false).isEmpty());
    }

    @Test void failedSpawnPathExpiresWithoutStartingCooldown() {
        ProjectileLaunchTracker tracker = new ProjectileLaunchTracker();
        UUID projectile = UUID.randomUUID();
        tracker.record(projectile, UUID.randomUUID(), cooldownDecision(), 100L);

        tracker.cleanup(100L);
        assertEquals(0, tracker.size());
        assertTrue(tracker.finalizeLaunch(projectile, false).isEmpty());
    }

    @Test void reloadClearsEveryPendingLaunch() {
        ProjectileLaunchTracker tracker = new ProjectileLaunchTracker();
        tracker.record(UUID.randomUUID(), UUID.randomUUID(), cooldownDecision(), 5_000L);
        tracker.record(UUID.randomUUID(), UUID.randomUUID(), cooldownDecision(), 5_000L);

        tracker.clear();
        assertEquals(0, tracker.size());
    }

    private RestrictionDecision cooldownDecision() {
        WarzoneConfig.Restriction restriction = new WarzoneConfig.Restriction(
                PEARL, RestrictionMode.COOLDOWN, Duration.ofSeconds(5));
        return new RestrictionDecision(RestrictionDecision.Result.COOLDOWN_READY,
                PEARL, restriction, Duration.ZERO);
    }
}
