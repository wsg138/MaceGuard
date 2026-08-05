package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CombatScopeServiceTest {
    private CombatLogXGateway combat;
    private WorldGuardQueryService worldGuard;
    private CombatScopeService scopes;
    private Player player;
    private Location inside;
    private Location outside;
    private UUID playerId;

    @BeforeEach void setUp() {
        combat = mock(CombatLogXGateway.class);
        worldGuard = mock(WorldGuardQueryService.class);
        scopes = new CombatScopeService(combat, worldGuard);
        player = mock(Player.class);
        inside = mock(Location.class);
        outside = mock(Location.class);
        playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(inside);
        when(combat.available()).thenReturn(true);
        when(combat.inCombat(player)).thenReturn(true);
        when(combat.bypass(player)).thenReturn(false);
        when(worldGuard.warzoneCombatZoneAllowed(inside, player)).thenReturn(true);
        when(worldGuard.warzoneCombatZoneAllowed(outside, player)).thenReturn(false);
    }

    @Test void taggedInsideAcquiresLatchAndCapturesStasisDeny() {
        when(worldGuard.warzoneStasisDenied(inside, player)).thenReturn(true);
        assertTrue(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.latch(playerId).orElseThrow().stasisDenied());
    }

    @Test void taggedOutsideDoesNotAcquireLatch() {
        assertFalse(scopes.acquireIfEligible(player, outside));
        assertTrue(scopes.latch(playerId).isEmpty());
    }

    @Test void alreadyTaggedPlayerAcquiresWhenEntering() {
        assertFalse(scopes.acquireIfEligible(player, outside));
        assertTrue(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.carryoverEligible(player));
    }

    @Test void playersLatchIndependently() {
        Player second = mock(Player.class);
        UUID secondId = UUID.randomUUID();
        when(second.getUniqueId()).thenReturn(secondId);
        when(combat.inCombat(second)).thenReturn(true);
        when(combat.bypass(second)).thenReturn(false);
        when(worldGuard.warzoneCombatZoneAllowed(inside, second)).thenReturn(true);
        assertTrue(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.acquireIfEligible(second, inside));
        assertEquals(2, scopes.size());
    }

    @Test void leavingRegionPreservesLatchUntilCombatEnds() {
        assertTrue(scopes.acquireIfEligible(player, inside));
        assertFalse(scopes.acquireIfEligible(player, outside));
        assertTrue(scopes.latch(playerId).isPresent());
        when(combat.inCombat(player)).thenReturn(false);
        assertFalse(scopes.combatBound(player));
        assertTrue(scopes.latch(playerId).isEmpty());
    }

    @Test void laterAllowedRegionCannotEraseCapturedStasisDeny() {
        when(worldGuard.warzoneStasisDenied(inside, player)).thenReturn(true, false);
        assertTrue(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.latch(playerId).orElseThrow().stasisDenied());
    }

    @Test void combatBypassPreventsAndClearsLatch() {
        assertTrue(scopes.acquireIfEligible(player, inside));
        when(combat.bypass(player)).thenReturn(true);
        assertFalse(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.latch(playerId).isEmpty());
    }

    @Test void unavailableCombatIntegrationCannotAcquire() {
        when(combat.available()).thenReturn(false);
        assertFalse(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.latch(playerId).isEmpty());
    }

    @Test void worldGuardQueryFailureDoesNotBroadenRestriction() {
        when(worldGuard.warzoneCombatZoneAllowed(inside, player))
                .thenThrow(new IllegalStateException("unavailable"));
        assertFalse(scopes.acquireIfEligible(player, inside));
        assertTrue(scopes.latch(playerId).isEmpty());
    }

    @Test void clearModelsDeathLogoutUntagAndReloadCleanup() {
        assertTrue(scopes.acquireIfEligible(player, inside));
        scopes.clear(playerId);
        assertTrue(scopes.latch(playerId).isEmpty());
        assertTrue(scopes.acquireIfEligible(player, inside));
        scopes.clear();
        assertEquals(0, scopes.size());
    }
}
