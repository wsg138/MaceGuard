package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class CombatPositionListenerTest {
    @Test void deniesWalkingAndPearlEntryWhenScopeReportsBlockedRegion() {
        CombatScopeService scopes = mock(CombatScopeService.class);
        CombatPositionListener listener = new CombatPositionListener(scopes,
                mock(CombatIntegrationListener.class));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        when(scopes.blockedRegionOnEntry(player, from, to)).thenReturn("spawn");
        PlayerMoveEvent move = spy(new PlayerMoveEvent(player, from, to));
        listener.onBlockedRegionMove(move);
        verify(move).setCancelled(true);
        PlayerTeleportEvent teleport = spy(new PlayerTeleportEvent(player, from, to,
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        listener.onBlockedRegionTeleport(teleport);
        verify(teleport).setCancelled(true);
    }

    @Test void permitsMovementWithinRegionAndDoesNotDoubleHandleTeleportAsMove() {
        CombatScopeService scopes = mock(CombatScopeService.class);
        CombatPositionListener listener = new CombatPositionListener(scopes,
                mock(CombatIntegrationListener.class));
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 1, 64, 0);
        PlayerMoveEvent move = spy(new PlayerMoveEvent(player, from, to));
        listener.onBlockedRegionMove(move);
        verify(move, never()).setCancelled(true);
        clearInvocations(scopes);
        listener.onBlockedRegionMove(new PlayerTeleportEvent(player, from, to));
        verifyNoInteractions(scopes);
    }
}
