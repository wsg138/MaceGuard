package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneConfigLoaderTest {
    @TempDir Path directory;

    @Test void parsesDisabledRestriction() throws IOException {
        var result = load(config("MACE", "can-disable: true\n    can-cooldown: false",
                "mode: DISABLED"));
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(RestrictionMode.DISABLED, result.value().rotations().getFirst().restrictions()
                .get(RestrictionTarget.parse("MACE").orElseThrow()).mode());
    }

    @Test void bundledDefaultConfigurationIsValid() {
        var result = new WarzoneConfigLoader().load(Path.of("src", "main", "resources", "warzone.yml"));
        assertTrue(result.valid(), result.errors().toString());
    }

    @Test void parsesCooldownRestriction() throws IOException {
        var result = load(config("ENDER_PEARL",
                "can-disable: true\n    can-cooldown: true\n    maximum-cooldown: 60s",
                "mode: COOLDOWN\n        cooldown: 15s"));
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(15, result.value().rotations().getFirst().restrictions().values()
                .iterator().next().cooldown().getSeconds());
    }

    @Test void parsesGenericMaterialTargetByExactName() throws IOException {
        var result = load(config("DIAMOND_SWORD", "can-disable: true\n    can-cooldown: false",
                "mode: DISABLED"));
        assertTrue(result.valid(), result.errors().toString());
        assertTrue(result.value().rotations().getFirst().restrictions().keySet().iterator().next()
                .matches(Material.DIAMOND_SWORD));
    }

    @Test void spearTargetExpandsAsAMaterialGroup() {
        RestrictionTarget spear = RestrictionTarget.parse("SPEAR").orElseThrow();
        java.util.Arrays.stream(Material.values()).filter(material -> material.name().endsWith("_SPEAR"))
                .forEach(material -> assertTrue(spear.matches(material)));
        assertFalse(spear.matches(Material.TRIDENT));
    }

    @Test void parsesSpearLungeAsEffectOnlyTarget() throws IOException {
        var result = load(config("SPEAR_LUNGE",
                "can-disable: true\n    can-cooldown: true\n    maximum-cooldown: 60s",
                "mode: COOLDOWN\n        cooldown: 10s"));
        assertTrue(result.valid(), result.errors().toString());
        assertTrue(result.value().rotations().getFirst().restrictions().keySet().iterator().next().effectOnly());
    }

    @Test void rejectsCooldownWhenGlobalPolicyDisallowsIt() throws IOException {
        var result = load(config("ENDER_PEARL", "can-disable: true\n    can-cooldown: false",
                "mode: COOLDOWN\n        cooldown: 15s"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("can-cooldown is false")));
    }

    @Test void rejectsDisabledWhenGlobalPolicyDisallowsIt() throws IOException {
        var result = load(config("MACE",
                "can-disable: false\n    can-cooldown: true\n    maximum-cooldown: 30s",
                "mode: DISABLED"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("can-disable is false")));
    }

    @Test void rejectsCooldownAboveGlobalMaximum() throws IOException {
        var result = load(config("WIND_CHARGE",
                "can-disable: true\n    can-cooldown: true\n    maximum-cooldown: 30s",
                "mode: COOLDOWN\n        cooldown: 31s"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("exceeds")));
    }

    @Test void legacyDisabledItemsMigrateToDisabledMode() throws IOException {
        Path legacy = directory.resolve("legacy.yml");
        Files.writeString(legacy, """
                config-version: 2
                region: { world: world, id: warzone }
                rotation: { warning-times: [10s] }
                messages: { blocked-message-cooldown: 2s, warning-audience: global, transition-audience: global }
                cobwebs: { clear-after: 60s, clear-on-meta-change: true, clear-on-disable: true }
                rotations:
                  alpha:
                    display-name: Alpha
                    description: Alpha rotation
                    duration: 1h
                    cobwebs-allowed: true
                    disabled-items: [MACE, SPEAR]
                    start-message: Started
                """);
        var converted = new LegacyWarzoneConverter().convert(legacy);
        assertTrue(converted.valid(), converted.errors().toString());
        Path migrated = directory.resolve("warzone.yml");
        Files.writeString(migrated, converted.value());
        var loaded = new WarzoneConfigLoader().load(migrated);
        assertTrue(loaded.valid(), loaded.errors().toString());
        assertEquals(2, loaded.value().rotations().getFirst().restrictions().size());
        loaded.value().rotations().getFirst().restrictions().values()
                .forEach(restriction -> assertEquals(RestrictionMode.DISABLED, restriction.mode()));
    }

    @Test void rejectsDuplicateYamlKeys() throws IOException {
        String duplicate = config("MACE", "can-disable: true\n    can-cooldown: false",
                "mode: DISABLED").replace("duration: 1h", "duration: 1h\n    duration: 2h");
        var result = load(duplicate);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.toLowerCase().contains("duplicate")));
    }

    private ValidationResult<WarzoneConfig> load(String text) throws IOException {
        Path file = directory.resolve("config-" + System.nanoTime() + ".yml");
        Files.writeString(file, text);
        return new WarzoneConfigLoader().load(file);
    }

    private String config(String target, String policy, String restriction) {
        return """
                config-version: 3
                enabled: true
                region:
                  world: world
                  id: warzone
                rotation:
                  warning-times: [10s]
                messages:
                  blocked-message-cooldown: 2s
                  warning-audience: global
                  transition-audience: global
                cobwebs:
                  clear-after: 60s
                  clear-on-meta-change: true
                  clear-on-disable: true
                restriction-targets:
                  %s:
                    %s
                rotations:
                  alpha:
                    display-name: Alpha
                    description: Alpha rotation
                    duration: 1h
                    cobwebs-allowed: true
                    restrictions:
                      %s:
                        %s
                    start-message: Started
                """.formatted(target, policy, target, restriction);
    }
}
