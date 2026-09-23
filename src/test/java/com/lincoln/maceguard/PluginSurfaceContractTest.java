package com.lincoln.maceguard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSurfaceContractTest {
    private static final Set<String> EXPECTED_COMMANDS = Set.of(
            "maceguard",
            "maceguardpearltrace",
            "warzone",
            "stasis"
    );

    private static final Set<String> DEFAULT_TRUE = Set.of(
            "warzonerotator.command.info",
            "warzonerotator.command.modifiers",
            "warzonerotator.command.kits",
            "warzonerotator.command.next",
            "warzonerotator.command.schedule",
            "warzonerotator.command.menu",
            "warzonerotator.command.items"
    );

    private static final Set<String> DEFAULT_FALSE = Set.of(
            "maceguard.block-policy.bypass",
            "maceguard.temporary-cobweb.bypass",
            "warzonerotator.bypass"
    );

    private static final Set<String> DEFAULT_OP = Set.of(
            "maceguard.admin",
            "maceguard.reload",
            "maceguard.reset",
            "warzonerotator.admin",
            "warzonerotator.manage.modifier",
            "warzonerotator.manage.kit",
            "warzonerotator.manage.random",
            "warzonerotator.manage.override",
            "warzonerotator.manage.schedule",
            "warzonerotator.manage.custom-combinations",
            "warzonerotator.command.skip",
            "warzonerotator.command.force",
            "warzonerotator.command.set",
            "warzonerotator.command.extend",
            "warzonerotator.command.reload",
            "warzonerotator.command.validate",
            "warzonerotator.command.debug"
    );

    @Test
    void descriptorKeepsTheReviewedPluginIdentityAndDependencies() {
        YamlConfiguration plugin = descriptor();

        assertEquals("com.lincoln.maceguard.MaceGuardPlugin", plugin.getString("main"));
        assertEquals("1.21", plugin.getString("api-version"));
        assertEquals(Set.of("WorldGuard"), Set.copyOf(plugin.getStringList("depend")));
        assertEquals(Set.of("PlaceholderAPI", "CombatLogX"), Set.copyOf(plugin.getStringList("softdepend")));
    }

    @Test
    void commandSurfaceAndOuterPermissionBoundariesStayExplicit() {
        ConfigurationSection commands = descriptor().getConfigurationSection("commands");
        assertNotNull(commands);
        assertEquals(EXPECTED_COMMANDS, commands.getKeys(false));

        assertEquals("warzonerotator.command.debug", commands.getString("maceguardpearltrace.permission"));
        assertEquals(Set.of("warzonerotator", "wzr"), Set.copyOf(commands.getStringList("warzone.aliases")));

        assertEquals(null, commands.get("maceguard.permission"));
        assertEquals(null, commands.get("warzone.permission"));
        assertEquals(null, commands.get("stasis.permission"));
    }

    @Test
    void permissionDefaultsMatchTheReviewedAuthoritySurface() {
        ConfigurationSection permissions = descriptor().getConfigurationSection("permissions");
        assertNotNull(permissions);

        Set<String> expected = new HashSet<>();
        expected.addAll(DEFAULT_TRUE);
        expected.addAll(DEFAULT_FALSE);
        expected.addAll(DEFAULT_OP);
        assertEquals(expected, permissions.getKeys(false));

        DEFAULT_TRUE.forEach(permission ->
                assertEquals(Boolean.TRUE, permissions.get(permission + ".default"), permission));
        DEFAULT_FALSE.forEach(permission ->
                assertEquals(Boolean.FALSE, permissions.get(permission + ".default"), permission));
        DEFAULT_OP.forEach(permission ->
                assertEquals("op", permissions.get(permission + ".default"), permission));
    }

    @Test
    void everyPermissionChildReferencesADeclaredPermissionAndGrantsItExplicitly() {
        ConfigurationSection permissions = descriptor().getConfigurationSection("permissions");
        assertNotNull(permissions);

        for (String permission : permissions.getKeys(false)) {
            ConfigurationSection children = permissions.getConfigurationSection(permission + ".children");
            if (children == null) {
                continue;
            }
            for (String child : children.getKeys(false)) {
                assertTrue(permissions.contains(child), permission + " references undeclared child " + child);
                assertEquals(Boolean.TRUE, children.get(child), permission + " must grant child " + child);
            }
        }
    }

    private static YamlConfiguration descriptor() {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
    }
}
