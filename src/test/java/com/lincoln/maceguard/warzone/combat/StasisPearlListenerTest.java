package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static com.lincoln.maceguard.warzone.combat.PersistentDataTestSupport.container;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StasisPearlListenerTest {
    private JavaPlugin plugin;

    @AfterEach void clearDiagnostics() {
        if (plugin != null) PearlEventDiagnostics.forPlugin(plugin).clear();
    }

    @Test void alreadyCanceledTeleportConsumesExactlyOneImpactWithoutMaceGuardWarning() {
        Fixture fixture = fixture(true);
        fixture.listener.onPearlTeleport(fixture.event);

        verify(fixture.tracker, times(1)).correlate(
                fixture.playerId, 100L, fixture.destinationPosition, 5_000L);
        verify(fixture.messages, never()).stasisBlocked(any());
        verify(fixture.event, never()).setCancelled(true);
    }

    @Test void maceGuardBlockConsumesExactlyOneImpactAndWarnsOnce() {
        Fixture fixture = fixture(false);
        when(fixture.scopes.combatBound(fixture.player)).thenReturn(true);
        when(fixture.scopes.insideCombatZone(fixture.player)).thenReturn(true);
        when(fixture.scopes.stasisDeniedAtLocation(fixture.player)).thenReturn(true);
        when(fixture.scopes.latch(fixture.playerId))
                .thenReturn(Optional.of(new CombatScopeService.Latch(true)));
        when(fixture.player.hasPermission("warzonerotator.bypass")).thenReturn(false);

        fixture.listener.onPearlTeleport(fixture.event);

        verify(fixture.tracker, times(1)).correlate(
                fixture.playerId, 100L, fixture.destinationPosition, 5_000L);
        verify(fixture.event).setCancelled(true);
        verify(fixture.messages).stasisBlocked(fixture.player);
    }

    @Test void agedPearlDoesNotBlockAfterPlayerLeavesWarzone() {
        Fixture fixture = fixture(false);
        when(fixture.scopes.combatBound(fixture.player)).thenReturn(true);
        when(fixture.scopes.insideCombatZone(fixture.player)).thenReturn(false);
        when(fixture.scopes.latch(fixture.playerId))
                .thenReturn(Optional.of(new CombatScopeService.Latch(true)));

        fixture.listener.onPearlTeleport(fixture.event);

        verify(fixture.tracker, times(1)).correlate(
                fixture.playerId, 100L, fixture.destinationPosition, 5_000L);
        verify(fixture.event, never()).setCancelled(true);
        verify(fixture.messages, never()).stasisBlocked(any());
        verify(fixture.scopes, never()).stasisDeniedAtLocation(fixture.player);
    }

    @Test void currentAllowedStasisFlagDoesNotBlockOldDeniedLatch() {
        Fixture fixture = fixture(false);
        when(fixture.scopes.combatBound(fixture.player)).thenReturn(true);
        when(fixture.scopes.insideCombatZone(fixture.player)).thenReturn(true);
        when(fixture.scopes.stasisDeniedAtLocation(fixture.player)).thenReturn(false);
        when(fixture.scopes.latch(fixture.playerId))
                .thenReturn(Optional.of(new CombatScopeService.Latch(true)));

        fixture.listener.onPearlTeleport(fixture.event);

        verify(fixture.event, never()).setCancelled(true);
        verify(fixture.messages, never()).stasisBlocked(any());
    }

    @Test void deathClearsOwnerTrackerState() {
        Fixture fixture = fixture(false);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(fixture.player);

        fixture.listener.onPlayerDeath(death);

        verify(fixture.tracker).clearOwner(fixture.playerId);
    }

    @Test void unmatchedTeleportDoesNotWarnOrCancel() {
        Fixture fixture = fixture(false);
        when(fixture.tracker.correlate(any(), anyLong(), any(), anyLong()))
                .thenReturn(StasisPearlTracker.Correlation.none());

        fixture.listener.onPearlTeleport(fixture.event);

        verify(fixture.tracker, times(1)).correlate(
                fixture.playerId, 100L, fixture.destinationPosition, 5_000L);
        verify(fixture.messages, never()).stasisBlocked(any());
        verify(fixture.event, never()).setCancelled(true);
    }

    private Fixture fixture(boolean canceledOnEntry) {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        CombatScopeService scopes = mock(CombatScopeService.class);
        StasisPearlTracker tracker = mock(StasisPearlTracker.class);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        PersistentDataContainer playerData = container();
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        Location destination = new Location(world, 10.0, 64.0, 10.0);
        StasisPearlTracker.Position position = new StasisPearlTracker.Position(
                worldId, 10.0, 64.0, 10.0);
        StasisPearlTracker.Impact impact = new StasisPearlTracker.Impact(
                UUID.randomUUID(), playerId, true, false, 60_000L,
                StasisPearlTracker.AgeSource.MONOTONIC, 100L, position, 10_000L, 0L);
        StasisPearlTracker.Correlation correlation = new StasisPearlTracker.Correlation(
                Optional.of(impact), impact.pearlId(), true, false, 1, false, true);

        when(plugin.getName()).thenReturn("MaceGuard");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StasisPearlListenerTest"));
        when(server.getCurrentTick()).thenReturn(100);
        when(player.getServer()).thenReturn(server);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getPersistentDataContainer()).thenReturn(playerData);
        when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        when(event.getPlayer()).thenReturn(player);
        when(event.getTo()).thenReturn(destination);
        when(event.getFrom()).thenReturn(destination);
        when(event.isCancelled()).thenReturn(canceledOnEntry);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(tracker.correlate(playerId, 100L, position, 5_000L)).thenReturn(correlation);

        StasisPearlListener.TimeSource time = new StasisPearlListener.TimeSource() {
            @Override public long wallMillis() { return 1_000L; }
            @Override public long nanoTime() { return 5_000L; }
        };
        StasisPearlListener listener = new StasisPearlListener(
                scopes, tracker, messages, Duration.ofSeconds(60), plugin, time);
        return new Fixture(listener, event, player, playerId, tracker, scopes, messages, position, world);
    }

    private record Fixture(StasisPearlListener listener, PlayerTeleportEvent event,
                           Player player, UUID playerId, StasisPearlTracker tracker,
                           CombatScopeService scopes, WarzoneMessageService messages,
                           StasisPearlTracker.Position destinationPosition, World retainedWorld) { }
}
