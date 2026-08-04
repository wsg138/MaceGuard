package com.lincoln.maceguard.warzone.rotation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneStateStoreVersionedTest {
    @TempDir Path directory;

    @Test void versionedStateRoundTripsWithoutReorderingModifiers() {
        Path file = directory.resolve("state.yml");
        RotationState expected = state().withOverride(SelectionSourceType.CUSTOM_OVERRIDE, null,
                List.of("cobwebs", "no-lunge"), OverrideDurationMode.UNTIL_CLEARED,
                1_500, 0, 8);
        store(file).update(expected);

        RotationState actual = store(file).load().orElseThrow();
        assertEquals(expected, actual);
        assertEquals(List.of("cobwebs", "no-lunge"), actual.overrideModifierIds());
    }

    @Test void untilClearedOverrideRejectsUnexpectedExpiration() throws Exception {
        Path file = write(state().withOverride(SelectionSourceType.CUSTOM_OVERRIDE, null,
                List.of(), OverrideDurationMode.UNTIL_CLEARED, 1_500, 2_500, 8));
        assertTrue(store(file).load().isEmpty());
        assertTrue(hasInvalidBackup());
    }

    @Test void orphanedOverrideFieldsAreRejected() throws Exception {
        Path file = write(state());
        YamlConfiguration yaml = load(file);
        yaml.set("override.duration-mode", "UNTIL_CLEARED");
        yaml.set("override.activated-at", 1_500L);
        yaml.save(file.toFile());

        assertTrue(store(file).load().isEmpty());
        assertTrue(hasInvalidBackup());
    }

    @Test void automaticRandomSourceCannotHaveEmptySelection() throws Exception {
        Path file = write(state());
        YamlConfiguration yaml = load(file);
        yaml.set("automatic.modifiers", List.of());
        yaml.save(file.toFile());

        assertTrue(store(file).load().isEmpty());
    }

    @Test void nonPositiveWarningMarkersAreRejected() throws Exception {
        Path file = write(state());
        YamlConfiguration yaml = load(file);
        yaml.set("emitted-warnings", List.of(60L, 0L));
        yaml.save(file.toFile());

        assertTrue(store(file).load().isEmpty());
    }


    @Test void malformedScheduleOverrideTypeIsRejectedInsteadOfCoerced() throws Exception {
        Path file = write(state());
        YamlConfiguration yaml = load(file);
        yaml.set("schedule.enabled-override", "false");
        yaml.save(file.toFile());

        assertTrue(store(file).load().isEmpty());
        assertTrue(hasInvalidBackup());
    }

    @Test void malformedPhaseOffsetTypeIsRejectedInsteadOfResetToZero() throws Exception {
        Path file = write(state());
        YamlConfiguration yaml = load(file);
        yaml.set("schedule.phase-offset", "1");
        yaml.save(file.toFile());

        assertTrue(store(file).load().isEmpty());
    }

    @Test void malformedModifierCollectionIsRejectedEvenForNoneSource() throws Exception {
        RotationState none = new RotationState(RotationState.VERSION, "1000:0:0", 0, 0, 0,
                null, 1_000, 2_000, 1_000, SelectionSourceType.NONE, null,
                List.of(), null, null, List.of(), null, 0, 0, Set.of(), 7);
        Path file = write(none);
        YamlConfiguration yaml = load(file);
        yaml.set("automatic.modifiers", "not-a-list");
        yaml.save(file.toFile());

        assertTrue(store(file).load().isEmpty());
    }

    @Test void schemaFiveStatePreservesSelectionOrderAndTiming() throws Exception {
        Path file = directory.resolve("legacy.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("selection.active-modifiers", List.of("wind-charge-cooldown-5", "cobwebs"));
        yaml.set("selection.activated-at", 1_100L);
        yaml.set("selection.weekly-boundary", 1_000L);
        yaml.set("selection.transition-at", 2_000L);
        yaml.set("selection.emitted-warnings", List.of(60L));
        yaml.set("selection.sequence", 9L);
        yaml.save(file.toFile());

        RotationState loaded = store(file).load().orElseThrow();
        assertEquals(List.of("wind-charge-cooldown-5", "cobwebs"),
                loaded.automaticModifierIds());
        assertEquals(1_100L, loaded.automaticActivatedAtMillis());
        assertEquals(1_000L, loaded.automaticSlotStartMillis());
        assertEquals(2_000L, loaded.automaticSlotEndMillis());
        assertEquals(9L, loaded.selectionSequence());
    }

    private RotationState state() {
        return new RotationState(RotationState.VERSION, "1000:0:0", 0, 0, 0,
                null, 1_000, 2_000, 1_000, SelectionSourceType.RANDOM, null,
                List.of("cobwebs"), null, null, List.of(), null, 0, 0,
                Set.of(60L), 7);
    }

    private Path write(RotationState state) {
        Path file = directory.resolve("state.yml");
        store(file).update(state);
        return file;
    }

    private WarzoneStateStore store(Path file) {
        return new WarzoneStateStore(file, Logger.getLogger("state-test"), Runnable::run);
    }

    private YamlConfiguration load(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private boolean hasInvalidBackup() throws Exception {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().contains(".invalid-"));
        }
    }
}
