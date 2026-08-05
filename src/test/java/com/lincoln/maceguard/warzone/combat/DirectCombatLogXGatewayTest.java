package com.lincoln.maceguard.warzone.combat;

import com.github.sirblobman.combatlogx.api.ICombatLogX;
import com.github.sirblobman.combatlogx.api.manager.ICombatManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class DirectCombatLogXGatewayTest {
    @Test void delegatesOnlyToPublicCombatManagerTypes() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Plugin candidate = mock(Plugin.class, withSettings().extraInterfaces(ICombatLogX.class));
        ICombatLogX api = (ICombatLogX) candidate;
        ICombatManager manager = mock(ICombatManager.class);
        Player player = mock(Player.class);
        when(api.getCombatManager()).thenReturn(manager);
        when(manager.isInCombat(player)).thenReturn(true);
        when(manager.canBypass(player)).thenReturn(false);
        when(manager.getMaxTimerSeconds(player)).thenReturn(30);
        when(manager.getTagInformation(player)).thenReturn(null);

        DirectCombatLogXGateway gateway = DirectCombatLogXGateway.connect(owner, candidate);

        assertTrue(gateway.available());
        assertTrue(gateway.inCombat(player));
        assertFalse(gateway.bypass(player));
        assertEquals(30, gateway.maximumSeconds(player));
        assertEquals(Duration.ZERO, gateway.remaining(player));
    }
    @Test void rejectsMissingCombatManager() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Plugin candidate = mock(Plugin.class, withSettings().extraInterfaces(ICombatLogX.class));
        ICombatLogX api = (ICombatLogX) candidate;
        when(api.getCombatManager()).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> DirectCombatLogXGateway.connect(owner, candidate));
    }

}
