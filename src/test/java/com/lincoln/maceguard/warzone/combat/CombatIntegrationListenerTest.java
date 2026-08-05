package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CombatIntegrationListenerTest {
    @Test void forwardsCapturedTagLocationRatherThanReadingLaterPlayerPosition() {
        CombatScopeService scopes = mock(CombatScopeService.class);
        StasisPearlTracker pearls = mock(StasisPearlTracker.class);
        CombatIntegrationListener listener = new CombatIntegrationListener(scopes, pearls);
        Player player = mock(Player.class);
        Location tagLocation = mock(Location.class);

        listener.tagged(player, tagLocation);

        verify(scopes).acquireIfEligible(player, tagLocation);
    }

    @Test void untagClearsBothLatchAndOwnedPearls() {
        CombatScopeService scopes = mock(CombatScopeService.class);
        StasisPearlTracker pearls = mock(StasisPearlTracker.class);
        CombatIntegrationListener listener = new CombatIntegrationListener(scopes, pearls);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        listener.untagged(player);

        verify(scopes).clear(playerId);
        verify(pearls).clearOwner(playerId);
    }
}
