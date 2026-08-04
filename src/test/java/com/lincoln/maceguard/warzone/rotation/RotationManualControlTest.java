package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

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
        assertNotEquals(before, manager.active().modifierIds());
    }

    @Test void forceAlsoPreservesTheExistingWeeklyAnchor() {
        RotationManager manager = manager(29L);
        long boundary = manager.state().weeklyBoundaryMillis();
        long transition = manager.state().transitionAtMillis();

        manager.force();
        assertEquals(boundary, manager.state().weeklyBoundaryMillis());
        assertEquals(transition, manager.state().transitionAtMillis());
    }

    @Test void forceWithOnlyOneCombinationHasNoTransitionSideEffects() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T09:00:00Z"),
                ZoneId.of("UTC"));
        WarzoneConfig base = ModifierSelectorTest.config(1, 1);
        WarzoneConfig single = new WarzoneConfig(base.version(), base.enabled(),
                base.region(), base.schedule(), base.selection(), Map.of(), base.warningTimes(),
                base.messages(), base.cobwebs(), base.targetPolicies(),
                Map.of("cobwebs", base.modifiers().get("cobwebs")), Map.of());
        WarzoneStateStore store = new WarzoneStateStore(
                directory.resolve("single-state.yml"), Logger.getLogger("test"), Runnable::run);
        AtomicInteger transitions = new AtomicInteger();
        RotationManager manager = new RotationManager(single, store, clock,
                new java.util.Random(1L),
                (previous, current, announce) -> transitions.incrementAndGet(),
                (active, remaining) -> { });
        RotationState before = manager.state();

        assertFalse(manager.force());
        assertEquals(before, manager.state());
        assertEquals(0, transitions.get());
    }

    private RotationManager manager(long seed) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T09:00:00Z"),
                ZoneId.of("UTC"));
        WarzoneStateStore store = new WarzoneStateStore(
                directory.resolve("state-" + seed + ".yml"),
                Logger.getLogger("test"), Runnable::run);
        return new RotationManager(ModifierSelectorTest.config(1, 3), store, clock,
                new java.util.Random(seed), (previous, current, announce) -> { },
                (active, remaining) -> { });
    }
}
