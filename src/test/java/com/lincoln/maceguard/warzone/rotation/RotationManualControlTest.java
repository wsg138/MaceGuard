package com.lincoln.maceguard.warzone.rotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationManualControlTest {
    @TempDir Path directory;

    @Test void skipSelectsAnotherValidSetWithoutMovingCalendarBoundary() {
        RotationManager manager = manager(11L);
        long boundary = manager.state().weeklyBoundaryMillis();
        long transition = manager.state().transitionAtMillis();
        var before = manager.active().modifierIds();

        assertTrue(manager.skip());
        assertEquals(boundary, manager.state().weeklyBoundaryMillis());
        assertEquals(transition, manager.state().transitionAtMillis());
        org.junit.jupiter.api.Assertions.assertNotEquals(before, manager.active().modifierIds());
    }

    @Test void forceAlsoPreservesTheExistingWeeklyAnchor() {
        RotationManager manager = manager(29L);
        long boundary = manager.state().weeklyBoundaryMillis();
        long transition = manager.state().transitionAtMillis();

        manager.force();
        assertEquals(boundary, manager.state().weeklyBoundaryMillis());
        assertEquals(transition, manager.state().transitionAtMillis());
    }

    private RotationManager manager(long seed) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T09:00:00Z"), ZoneId.of("UTC"));
        WarzoneStateStore store = new WarzoneStateStore(directory.resolve("state-" + seed + ".yml"),
                Logger.getLogger("test"), Runnable::run);
        return new RotationManager(ModifierSelectorTest.config(1, 3), store, clock,
                new java.util.Random(seed), (previous, current, announce) -> { },
                (active, remaining) -> { });
    }
}
