package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RotationOverrideLifecycleTest {
    @TempDir Path directory;

    @Test void kitActivationPreservesConfiguredOrder() {
        RotationManager manager = manager(new MutableClock(instant("2026-08-10T12:00:00Z")), store("kit"), 1);
        manager.setKit("smp", OverrideDurationMode.UNTIL_CLEARED, true);
        assertEquals(SelectionSourceType.KIT, manager.activeSelection().sourceType());
        assertEquals("smp", manager.activeSelection().sourceId());
        assertEquals(List.of("cobwebs", "ender-pearl-cooldown-5", "wind-charge-cooldown-5"),
                manager.active().modifierIds());
    }

    @Test void oneHourOverrideExpiresToCurrentlyDueAutomaticSlot() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        RotationManager manager = manager(clock, store("one-hour"), 2);
        manager.setKit("mace", OverrideDurationMode.ONE_HOUR, true);
        assertTrue(manager.state().overrideActive());
        clock.advance(Duration.ofHours(1));
        manager.tick();
        assertFalse(manager.state().overrideActive());
        assertEquals(manager.automaticSelection().activeSet().modifierIds(), manager.active().modifierIds());
    }

    @Test void indefiniteOverrideSurvivesRestartWhileAutomaticAdvancesInBackground() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        WarzoneStateStore firstStore = store("indefinite");
        RotationManager first = manager(clock, firstStore, 3);
        first.setKit("mace", OverrideDurationMode.UNTIL_CLEARED, true);
        List<String> override = first.active().modifierIds();
        String automaticIdentity = first.state().automaticSlotIdentity();
        clock.advance(Duration.ofDays(22));

        RotationManager restored = manager(clock, store("indefinite"), 99);
        assertEquals(override, restored.active().modifierIds());
        assertTrue(restored.state().overrideActive());
        assertNotEquals(automaticIdentity, restored.state().automaticSlotIdentity());
        assertNotEquals(restored.active().modifierIds(), restored.automaticSelection().activeSet().modifierIds());
    }

    @Test void clearingOverrideAppliesBackgroundAutomaticSlot() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        RotationManager manager = manager(clock, store("clear"), 4);
        manager.setKit("mace", OverrideDurationMode.UNTIL_CLEARED, true);
        clock.advance(Duration.ofDays(8));
        manager.tick();
        assertTrue(manager.clearOverride(true));
        assertEquals(manager.automaticSelection().activeSet().modifierIds(), manager.active().modifierIds());
    }

    @Test void automaticBoundaryUnderOverrideDoesNotPublishGameplayTransition() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        AtomicInteger transitions = new AtomicInteger();
        RotationManager manager = manager(clock, store("suppressed"), 5, transitions);
        manager.setKit("mace", OverrideDurationMode.UNTIL_CLEARED, true);
        transitions.set(0);
        clock.advance(Duration.ofDays(8));
        manager.tick();
        assertEquals(0, transitions.get());
        assertTrue(manager.state().overrideActive());
    }

    @Test void randomAutomaticSlotSurvivesRestartWithoutReroll() {
        MutableClock clock = new MutableClock(instant("2026-08-17T12:00:00Z"));
        RotationManager first = manager(clock, store("random"), 6);
        assertEquals(SelectionSourceType.RANDOM, first.automaticSelection().sourceType());
        List<String> selected = first.automaticSelection().activeSet().modifierIds();
        RotationManager restored = manager(clock, store("random"), 999);
        assertEquals(selected, restored.automaticSelection().activeSet().modifierIds());
    }

    @Test void disabledScheduleRejectsUntilNextAndManualAdvance() {
        RotationManager manager = manager(new MutableClock(instant("2026-08-10T12:00:00Z")),
                store("disabled"), 7);
        manager.setScheduleEnabled(false);
        assertThrows(IllegalStateException.class,
                () -> manager.setKit("mace", OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE, true));
        assertThrows(IllegalStateException.class, () -> manager.advanceSchedule(true));
        assertFalse(manager.scheduleEnabled());
    }

    @Test void forgedMissingKitOverrideFailsSafelyOnRestart() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        WarzoneStateStore store = store("forged-kit");
        RotationManager manager = manager(clock, store, 8);
        RotationState forged = manager.state().withOverride(SelectionSourceType.KIT, "missing",
                List.of("cobwebs"), OverrideDurationMode.UNTIL_CLEARED,
                clock.millis(), 0, manager.state().selectionSequence() + 1);
        store.update(forged);
        RotationManager restored = manager(clock, store("forged-kit"), 9);
        assertFalse(restored.state().overrideActive());
        assertFalse(restored.activeSelection().manualOverride());
    }

    @Test void forgedKitMembersThatDoNotMatchDefinitionFailSafely() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        WarzoneStateStore store = store("forged-members");
        RotationManager manager = manager(clock, store, 10);
        RotationState forged = manager.state().withOverride(SelectionSourceType.KIT, "smp",
                List.of("cobwebs"), OverrideDurationMode.UNTIL_CLEARED,
                clock.millis(), 0, manager.state().selectionSequence() + 1);
        store.update(forged);
        RotationManager restored = manager(clock, store("forged-members"), 11);
        assertFalse(restored.state().overrideActive());
    }

    @Test void forgedOutOfRangeRandomOverrideFailsSafely() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        WarzoneStateStore store = store("forged-random");
        RotationManager manager = manager(clock, store, 12);
        RotationState forged = manager.state().withOverride(SelectionSourceType.RANDOM, null,
                List.of(), OverrideDurationMode.UNTIL_CLEARED,
                clock.millis(), 0, manager.state().selectionSequence() + 1);
        store.update(forged);
        RotationManager restored = manager(clock, store("forged-random"), 13);
        assertFalse(restored.state().overrideActive());
    }

    @Test void applyPreparedRejectsAutomaticOnlySourceTypes() {
        RotationManager manager = manager(new MutableClock(instant("2026-08-10T12:00:00Z")),
                store("prepared"), 14);
        assertThrows(IllegalArgumentException.class, () -> manager.applyPrepared(
                SelectionSourceType.SCHEDULED_MODIFIERS, null, List.of("cobwebs"),
                OverrideDurationMode.UNTIL_CLEARED, true));
        assertThrows(IllegalArgumentException.class, () -> manager.applyPrepared(
                SelectionSourceType.NONE, null, List.of(),
                OverrideDurationMode.UNTIL_CLEARED, true));
    }


    @Test void indefiniteOverrideCannotBeCorruptedByExtend() {
        RotationManager manager = manager(new MutableClock(instant("2026-08-10T12:00:00Z")),
                store("indefinite-extend"), 15);
        manager.setKit("mace", OverrideDurationMode.UNTIL_CLEARED, true);
        RotationState before = manager.state();
        assertThrows(IllegalStateException.class, () -> manager.extend(Duration.ofHours(1)));
        assertEquals(before, manager.state());
        assertEquals(OverrideDurationMode.UNTIL_CLEARED, manager.state().overrideDurationMode());
        assertEquals(0, manager.state().overrideExpiresAtMillis());
    }

    @Test void disabledScheduleCannotCreateSyntheticAutomaticExtension() {
        RotationManager manager = manager(new MutableClock(instant("2026-08-10T12:00:00Z")),
                store("disabled-extend"), 16);
        manager.setScheduleEnabled(false);
        RotationState before = manager.state();
        assertThrows(IllegalStateException.class, () -> manager.extend(Duration.ofHours(1)));
        assertEquals(before, manager.state());
    }

    @Test void modifyingKitSelectionDetachesIntoCustomOverrideWithoutMutatingKit() {
        RotationManager manager = manager(new MutableClock(instant("2026-08-10T12:00:00Z")),
                store("detach"), 17);
        manager.setKit("mace", OverrideDurationMode.UNTIL_CLEARED, true);
        List<String> originalKit = manager.controlConfig().kits().get("mace").modifierIds();
        manager.addModifier("cobwebs", OverrideDurationMode.UNTIL_CLEARED, true);

        assertEquals(SelectionSourceType.CUSTOM_OVERRIDE, manager.activeSelection().sourceType());
        assertNull(manager.activeSelection().sourceId());
        assertTrue(manager.active().modifierIds().contains("cobwebs"));
        assertEquals(originalKit, manager.controlConfig().kits().get("mace").modifierIds());
    }

    @Test void manualScheduleAdvancePhaseSurvivesRestart() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        RotationManager first = manager(clock, store("phase"), 18);
        int before = first.currentSlot().cycleIndex();
        first.advanceSchedule(false);
        int advanced = first.currentSlot().cycleIndex();
        assertNotEquals(before, advanced);

        RotationManager restored = manager(clock, store("phase"), 19);
        assertEquals(advanced, restored.currentSlot().cycleIndex());
        assertEquals(first.state().cyclePhaseOffset(), restored.state().cyclePhaseOffset());
    }

    @Test void untilNextOverrideKeepsOriginallyConfirmedBoundaryAcrossRestart() {
        MutableClock clock = new MutableClock(instant("2026-08-10T12:00:00Z"));
        RotationManager first = manager(clock, store("frozen-boundary"), 20);
        first.setKit("mace", OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE, true);
        long confirmed = first.state().overrideExpiresAtMillis();
        clock.advance(Duration.ofHours(2));

        RotationManager restored = manager(clock, store("frozen-boundary"), 21);
        assertEquals(confirmed, restored.state().overrideExpiresAtMillis());
        assertEquals(OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE,
                restored.state().overrideDurationMode());
    }

    private RotationManager manager(MutableClock clock, WarzoneStateStore store, long seed) {
        return manager(clock, store, seed, new AtomicInteger());
    }

    private RotationManager manager(MutableClock clock, WarzoneStateStore store, long seed,
                                    AtomicInteger transitions) {
        return new RotationManager(control(), store, clock, new java.util.Random(seed),
                (previous, current, announce) -> transitions.incrementAndGet(),
                (active, remaining) -> { });
    }

    private WarzoneControlConfig control() {
        var result = new WarzoneControlConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
        assertTrue(result.valid(), result.errors().toString());
        return result.value();
    }

    private WarzoneStateStore store(String name) {
        return new WarzoneStateStore(directory.resolve(name + ".yml"),
                Logger.getLogger("test"), Runnable::run);
    }

    private long instant(String value) { return Instant.parse(value).toEpochMilli(); }

    private static final class MutableClock extends Clock {
        private long millis;
        private MutableClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
        void advance(Duration duration) { millis += duration.toMillis(); }
    }
}
