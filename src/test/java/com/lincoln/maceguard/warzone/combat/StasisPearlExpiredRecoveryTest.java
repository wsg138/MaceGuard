package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StasisPearlExpiredRecoveryTest {
    @Test
    void expiredMarkedImpactCannotFailOpenAfterLargeTickDelay() {
        StasisPearlTracker tracker = new StasisPearlTracker();
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        StasisPearlTracker.Position impact =
                new StasisPearlTracker.Position(world, 10.0, 64.0, 10.0);

        tracker.landed(UUID.randomUUID(),
                new StasisPearlTracker.LaunchMetadata(owner, 1_000L, false, null),
                Duration.ofSeconds(60), 10L, impact, 70_000L, 1L);

        long cleanupTime = Duration.ofSeconds(6).toNanos();
        tracker.cleanup(cleanupTime);
        StasisPearlTracker.Correlation recovered = tracker.correlate(
                owner, 5_000L, impact, cleanupTime + 1L);

        assertTrue(recovered.matched());
        assertTrue(recovered.overflow());
        assertTrue(recovered.effectiveAged(),
                "A large tick delay must not turn unusual queue expiry into a bypass.");
    }
}
