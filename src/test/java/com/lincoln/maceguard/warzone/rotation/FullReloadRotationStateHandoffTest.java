package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullReloadRotationStateHandoffTest {
    @TempDir Path directory;

    @Test
    void pendingLiveOverrideSeedsCandidateExactlyAndFailedCandidateCannotPersist() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneId.of("UTC"));
        QueueExecutor writer = new QueueExecutor();
        Path file = directory.resolve("warzone-state.yml");
        WarzoneStateStore liveStore = new WarzoneStateStore(file, logger(), writer);
        RotationManager live = manager(clock, liveStore, 1L);
        writer.runAll(); // establish the older automatic state on disk

        live.advanceSchedule(false);
        live.setKit("mace", OverrideDurationMode.ONE_HOUR, false);
        RotationState expected = live.state();
        assertTrue(writer.hasPending(), "manual override should still be queued for persistence");

        WarzoneStateStore staged = WarzoneStateStore.staged(expected, logger());
        RotationManager candidate = manager(clock, staged, 99L);
        assertTransferredFields(expected, candidate.state());
        assertEquals(expected, candidate.state());

        candidate.clearOverride(false); // simulate a candidate that later fails validation/startup
        assertEquals(expected, live.state());
        assertEquals(expected, liveStore.snapshot().orElseThrow());

        writer.runAll();
        RotationState persisted = new WarzoneStateStore(file, logger(), Runnable::run)
                .load().orElseThrow();
        assertEquals(expected, persisted);
    }

    @Test
    void successfulCandidatePromotesToSharedStoreAndFutureChangesPersist() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneId.of("UTC"));
        QueueExecutor writer = new QueueExecutor();
        Path file = directory.resolve("promoted-warzone-state.yml");
        WarzoneStateStore liveStore = new WarzoneStateStore(file, logger(), writer);
        RotationManager live = manager(clock, liveStore, 2L);
        writer.runAll();
        live.advanceSchedule(false);
        live.setKit("mace", OverrideDurationMode.ONE_HOUR, false);
        RotationState expected = live.state();

        RotationManager candidate = manager(clock,
                WarzoneStateStore.staged(expected, logger()), 777L);
        candidate.adoptStateStore(liveStore);
        assertSame(liveStore, candidate.store());
        assertEquals(candidate.state(), liveStore.snapshot().orElseThrow());
        writer.runAll();
        assertEquals(candidate.state(), new WarzoneStateStore(file, logger(), Runnable::run)
                .load().orElseThrow());

        candidate.clearOverride(false);
        assertFalse(candidate.state().overrideActive());
        writer.runAll();
        RotationState persisted = new WarzoneStateStore(file, logger(), Runnable::run)
                .load().orElseThrow();
        assertFalse(persisted.overrideActive());
        assertEquals(candidate.state(), persisted);
    }

    private void assertTransferredFields(RotationState expected, RotationState actual) {
        assertEquals(expected.overrideSourceType(), actual.overrideSourceType());
        assertEquals(expected.overrideSourceId(), actual.overrideSourceId());
        assertEquals(expected.overrideModifierIds(), actual.overrideModifierIds());
        assertEquals(expected.overrideDurationMode(), actual.overrideDurationMode());
        assertEquals(expected.overrideExpiresAtMillis(), actual.overrideExpiresAtMillis());
        assertEquals(expected.cyclePhaseOffset(), actual.cyclePhaseOffset());
        assertEquals(expected.selectionSequence(), actual.selectionSequence());
    }

    private RotationManager manager(Clock clock, WarzoneStateStore store, long seed) {
        return new RotationManager(control(), store, clock, new java.util.Random(seed),
                (previous, current, announce) -> { }, (active, remaining) -> { });
    }

    private WarzoneControlConfig control() {
        var result = new WarzoneControlConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
        assertTrue(result.valid(), result.errors().toString());
        return result.value();
    }

    private Logger logger() {
        return Logger.getLogger("FullReloadRotationStateHandoffTest");
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queued.add(command); }
        boolean hasPending() { return !queued.isEmpty(); }
        void runAll() {
            while (!queued.isEmpty()) queued.remove().run();
        }
    }
}
