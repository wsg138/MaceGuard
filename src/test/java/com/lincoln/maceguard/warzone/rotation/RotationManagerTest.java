package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RotationManagerTest {
    @TempDir Path directory;

    @Test void freshStateRecordsActualActivationAndCurrentWeekBoundary() {
        Instant now = Instant.parse("2026-08-03T09:00:00Z");
        MutableClock clock = new MutableClock(now.toEpochMilli());
        RotationManager manager = manager(store(), clock, 1L);

        assertEquals(now.toEpochMilli(), manager.state().activatedAtMillis());
        assertEquals(Instant.parse("2026-08-02T08:00:00Z").toEpochMilli(),
                manager.state().weeklyBoundaryMillis());
        assertEquals(Instant.parse("2026-08-09T08:00:00Z").toEpochMilli(),
                manager.state().transitionAtMillis());
    }

    @Test void restartDuringWeekPreservesSelectionAndTransition() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T09:00:00Z").toEpochMilli());
        WarzoneStateStore store = store();
        RotationManager first = manager(store, clock, 2L);
        var state = first.state();

        RotationManager restored = manager(new WarzoneStateStore(
                directory.resolve("state.yml"), Logger.getLogger("test"), Runnable::run),
                clock, 99L);
        assertEquals(state.activeModifierIds(), restored.state().activeModifierIds());
        assertEquals(state.transitionAtMillis(), restored.state().transitionAtMillis());
        assertFalse(restored.advancedDuringRestore());
    }

    @Test void offlineTransitionRecoversToCurrentWeekWithoutRepeatedReroll() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T09:00:00Z").toEpochMilli());
        manager(store(), clock, 1L);
        clock.advance(Duration.ofDays(9));
        RotationManager restored = manager(new WarzoneStateStore(
                directory.resolve("state.yml"), Logger.getLogger("test"), Runnable::run),
                clock, 3L);
        assertTrue(restored.advancedDuringRestore());
        assertEquals(clock.millis(), restored.state().activatedAtMillis());
        assertEquals(Instant.parse("2026-08-09T08:00:00Z").toEpochMilli(),
                restored.state().weeklyBoundaryMillis());
        assertTrue(restored.state().transitionAtMillis() > clock.millis());

        RotationManager secondRestart = manager(new WarzoneStateStore(
                directory.resolve("state.yml"), Logger.getLogger("test"), Runnable::run),
                clock, 7L);
        assertEquals(restored.state().activeModifierIds(),
                secondRestart.state().activeModifierIds());
        assertEquals(restored.state().transitionAtMillis(),
                secondRestart.state().transitionAtMillis());
    }

    @Test void manualForceSetAndExtendPreserveCalendarBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T09:00:00Z").toEpochMilli());
        RotationManager manager = manager(store(), clock, 1L);
        long boundary = manager.state().weeklyBoundaryMillis();
        long transition = manager.state().transitionAtMillis();

        manager.force();
        assertEquals(boundary, manager.state().weeklyBoundaryMillis());
        assertEquals(transition, manager.state().transitionAtMillis());

        assertTrue(manager.set(List.of("cobwebs", "no-lunge"), true));
        assertEquals(boundary, manager.state().weeklyBoundaryMillis());

        assertTrue(manager.extend(Duration.ofHours(2)));
        assertEquals(boundary, manager.state().weeklyBoundaryMillis());
        assertEquals(transition + Duration.ofHours(2).toMillis(),
                manager.state().transitionAtMillis());
    }

    @Test void invalidConflictingManualSetDoesNotCorruptState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T09:00:00Z").toEpochMilli());
        RotationManager manager = manager(store(), clock, 1L);
        RotationState before = manager.state();
        assertFalse(manager.set(List.of("mace-disabled", "mace-cooldown"), true));
        assertEquals(before, manager.state());
    }

    private RotationManager manager(WarzoneStateStore store, Clock clock, long seed) {
        WarzoneConfig config = ModifierSelectorTest.config(1, 3);
        return new RotationManager(config, store, clock, new java.util.Random(seed),
                (previous, current, announce) -> { }, (active, remaining) -> { });
    }

    private WarzoneStateStore store() {
        return new WarzoneStateStore(directory.resolve("state.yml"),
                Logger.getLogger("test"), Runnable::run);
    }

    private static final class MutableClock extends Clock {
        private long millis;
        private MutableClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        void advance(Duration duration) { millis += duration.toMillis(); }
        @Override public long millis() { return millis; }
    }
}
