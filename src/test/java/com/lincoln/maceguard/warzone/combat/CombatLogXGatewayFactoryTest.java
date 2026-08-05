package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombatLogXGatewayFactoryTest {
    @Test void absentSoftDependencyReturnsDependencyNeutralGateway() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin("CombatLogX")).thenReturn(null);

        CombatLogXGateway gateway = CombatLogXGatewayFactory.discover(owner);

        assertFalse(gateway.available());
        assertTrue(gateway.unavailableReason().contains("not installed or enabled"));
    }
}
