package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StasisPearlExtremeDelayTest {
    @Test void expiredImpactAuthoritySurvivesBeyondIntegerTickRange() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        StasisPearlTracker.Position impact =
                new StasisPearlTracker.Position(world, 1.0, 64.0, 1.0);
        tracker.landed(UUID.randomUUID(),
                new StasisPearlTracker.LaunchMetadata(owner, 1_000L, false, null),
                Duration.ofSeconds(60), 10L, impact, 70_000L, 1L);

        tracker.cleanup(Duration.ofSeconds(6).toNanos());
        StasisPearlTracker.Correlation recovered = tracker.correlate(
                owner, (long) Integer.MAX_VALUE + 10_000L, impact,
                Duration.ofDays(30).toNanos());

        assertTrue(recovered.matched());
        assertTrue(recovered.overflow());
        assertTrue(recovered.effectiveAged());
        assertEquals(0, tracker.pendingImpacts());
    }
}
