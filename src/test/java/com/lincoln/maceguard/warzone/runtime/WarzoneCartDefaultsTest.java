package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.config.WarzoneControlConfigLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarzoneCartDefaultsTest {
    private static final String CART_MODIFIER_PATH = "modifiers.carts";

    @TempDir Path directory;

    @Test
    void currentSchemaGetsOnlyMissingCartSectionWithoutChangingExistingValues() throws IOException {
        YamlConfiguration defaults = bundledDefaults();
        YamlConfiguration existing = bundledDefaults();
        existing.set(CART_MODIFIER_PATH, null);
        existing.set("modifiers.cobwebs.weight", 37);
        existing.set("rotation.schedule.time", "06:25");
        existing.set("kits.smp.display-name", "Custom SMP Kit");

        assertTrue(WarzoneMigrationService.mergeMissingPath(defaults, existing, CART_MODIFIER_PATH));

        assertEquals(37, existing.getInt("modifiers.cobwebs.weight"));
        assertEquals("06:25", existing.getString("rotation.schedule.time"));
        assertEquals("Custom SMP Kit", existing.getString("kits.smp.display-name"));
        assertTrue(existing.getBoolean("modifiers.carts.enabled"));
        assertEquals(8, existing.getInt("modifiers.carts.weight"));
        assertEquals(List.of("CARTS"), existing.getStringList("modifiers.carts.effects"));
        assertNotNull(existing.getConfigurationSection("modifiers.carts.restrictions"));

        Path file = directory.resolve("warzone.yml");
        WarzoneMigrationService.saveValidatedAtomically(existing, file);
        assertTrue(new WarzoneControlConfigLoader().load(file).valid());
        assertFalse(WarzoneMigrationService.mergeMissingPath(defaults, existing, CART_MODIFIER_PATH));
    }

    @Test
    void partialCartSectionKeepsOperatorOverridesAndFillsOnlyMissingFields() {
        YamlConfiguration defaults = bundledDefaults();
        YamlConfiguration existing = bundledDefaults();
        existing.set("modifiers.carts.weight", 91);
        existing.set("modifiers.carts.description", "My custom cart description");
        existing.set("modifiers.carts.effects", null);
        existing.set("modifiers.carts.restrictions", null);

        assertTrue(WarzoneMigrationService.mergeMissingPath(defaults, existing, CART_MODIFIER_PATH));

        assertEquals(91, existing.getInt("modifiers.carts.weight"));
        assertEquals("My custom cart description", existing.getString("modifiers.carts.description"));
        assertEquals(List.of("CARTS"), existing.getStringList("modifiers.carts.effects"));
        assertNotNull(existing.getConfigurationSection("modifiers.carts.restrictions"));
    }

    private YamlConfiguration bundledDefaults() {
        try (InputStream stream = WarzoneCartDefaultsTest.class.getResourceAsStream("/warzone.yml")) {
            assertNotNull(stream, "Bundled warzone.yml must be available to tests.");
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
