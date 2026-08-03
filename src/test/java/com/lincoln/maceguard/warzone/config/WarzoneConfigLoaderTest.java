package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneConfigLoaderTest {
    @TempDir Path directory;

    @Test void bundledDefaultConfigurationIsValid() {
        var result = new WarzoneConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(4, result.value().version());
        assertEquals(Set.of("spawn", "market"),
                Set.copyOf(result.value().region().excludedRegionIds()));
    }

    @Test void parsesWeeklyScheduleAndMaceCooldownModifier() {
        var result = new WarzoneConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
        assertTrue(result.valid(), result.errors().toString());
        assertEquals("America/Indiana/Indianapolis",
                result.value().schedule().timezone().getId());
        var restriction = result.value().modifiers().get("mace-cooldown")
                .restrictions().get(RestrictionTarget.parse("MACE").orElseThrow());
        assertEquals(RestrictionMode.COOLDOWN, restriction.mode());
        assertEquals(10, restriction.cooldown().getSeconds());
    }

    @Test void rejectsSequentialSchemaInsteadOfSilentlyReinterpretingIt() throws IOException {
        Path file = directory.resolve("old.yml");
        Files.writeString(file, """
                config-version: 3
                enabled: true
                region: {world: world, id: warzone}
                rotation: {warning-times: [10m]}
                messages: {}
                cobwebs: {}
                restriction-targets: {}
                rotations: {}
                """);
        var result = new WarzoneConfigLoader().load(file);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("config-version must be 4")));
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("rotations is not supported")));
    }

    @Test void rejectsUnknownExclusionOrModifierConflictReference() throws IOException {
        String text = Files.readString(
                Path.of("src", "main", "resources", "warzone.yml"))
                .replace("    - mace-cooldown\n", "    - missing-modifier\n");
        Path file = directory.resolve("invalid.yml");
        Files.writeString(file, text);
        var result = new WarzoneConfigLoader().load(file);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("unknown modifier 'missing-modifier'")));
    }

    @Test void strictLoaderRejectsDuplicateKeys() throws IOException {
        String text = Files.readString(
                Path.of("src", "main", "resources", "warzone.yml"))
                .replace("    maximum: 3", "    maximum: 3\n    maximum: 2");
        Path file = directory.resolve("duplicate.yml");
        Files.writeString(file, text);
        var result = new WarzoneConfigLoader().load(file);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.toLowerCase().contains("duplicate")));
    }
}
