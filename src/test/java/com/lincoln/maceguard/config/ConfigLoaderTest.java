package com.lincoln.maceguard.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path directory;

    @Test void bundledConfigurationIsValidAndContainsRequiredProfiles() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src", "main", "resources", "config.yml").toFile());
        MaceGuardConfig config = new ConfigLoader().load(yaml);
        assertTrue(config.validSchema(), config.errors().toString());
        assertEquals(8, ConfigLoader.VERSION);
        assertTrue(config.blockPolicies().containsKey("cobweb-box"));
        assertEquals(ResetProfile.Mode.FULL_SNAPSHOT,
                config.resetProfiles().get("war-pit").mode());
        assertEquals(ResetProfile.Mode.FILTERED_SNAPSHOT,
                config.resetProfiles().get("warzone-environment").mode());
    }

    @Test void invalidPolicyMaterialProducesPathSpecificError() throws Exception {
        String text = Files.readString(
                Path.of("src", "main", "resources", "config.yml"))
                .replace("        - ICE", "        - NOT_A_REAL_BLOCK");
        Path file = directory.resolve("config.yml");
        Files.writeString(file, text);
        MaceGuardConfig config = new ConfigLoader().load(
                YamlConfiguration.loadConfiguration(file.toFile()));
        assertFalse(config.validSchema());
        assertTrue(config.errors().stream().anyMatch(value ->
                value.contains("block-policies.cobweb-box.place.materials")));
    }

    @Test void namespacedAndAliasMaterialNamesAreRejectedStrictly() throws Exception {
        String text = Files.readString(
                Path.of("src", "main", "resources", "config.yml"))
                .replace("        - ICE", "        - minecraft:ice");
        Path file = directory.resolve("config-namespaced.yml");
        Files.writeString(file, text);
        MaceGuardConfig config = new ConfigLoader().load(
                YamlConfiguration.loadConfiguration(file.toFile()));
        assertFalse(config.validSchema());
        assertTrue(config.errors().stream().anyMatch(value ->
                value.contains("block-policies.cobweb-box.place.materials")
                        && value.contains("invalid material")));
    }

    @Test void obsoleteSparseProfileFailsClosed() throws Exception {
        String text = Files.readString(
                Path.of("src", "main", "resources", "config.yml"))
                .replace("mode: FILTERED_SNAPSHOT", "mode: SPARSE_ORIGINALS");
        Path file = directory.resolve("config-sparse.yml");
        Files.writeString(file, text);
        MaceGuardConfig config = new ConfigLoader().load(
                YamlConfiguration.loadConfiguration(file.toFile()));
        assertFalse(config.validSchema());
        assertTrue(config.errors().stream().anyMatch(value ->
                value.contains("SPARSE_ORIGINALS is obsolete")));
    }
}
