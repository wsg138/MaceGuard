package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class CombatIntegrationBarHandoffTest {
    @Test void committedRuntimeReconcilesBarAtTagAndUntag() {
        CombatScopeService scopes = mock(CombatScopeService.class);
        StasisPearlTracker pearls = mock(StasisPearlTracker.class);
        WarzoneCombatBar bar = mock(WarzoneCombatBar.class);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        CombatIntegrationListener lifecycle = new CombatIntegrationListener(scopes, pearls, bar);

        lifecycle.tagged(player, location);
        verify(bar, never()).reconcilePlayer(player);

        lifecycle.setBarActive(true);
        lifecycle.tagged(player, location);
        verify(bar).reconcilePlayer(player);
        lifecycle.untagged(player);
        verify(bar).hide(player);
    }
}
