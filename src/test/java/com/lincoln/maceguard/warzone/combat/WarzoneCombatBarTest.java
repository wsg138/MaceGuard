package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarzoneCombatBarTest {
    @Test void defaultBarUsesRedFillOrangeTextAndCombatLogXTimeThenHides() {
        CombatLogXGateway combat = mock(CombatLogXGateway.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        Player player = mock(Player.class);
        Location warzone = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(combat.available()).thenReturn(true);
        when(combat.inCombat(player)).thenReturn(true);
        when(combat.remaining(player)).thenReturn(Duration.ofSeconds(15));
        when(combat.maximumSeconds(player)).thenReturn(30);
        when(combat.suppressBossBar(player)).thenReturn(true);
        when(worldGuard.warzoneCombatZoneAllowed(warzone, player)).thenReturn(true);

        CombatScopeService scopes = new CombatScopeService(combat, worldGuard);
        assertTrue(scopes.acquireIfEligible(player, warzone));
        WarzoneCombatBar display = new WarzoneCombatBar(scopes, WarzoneConfig.WarzoneTag.defaults());
        display.reconcile(List.of(player));

        ArgumentCaptor<BossBar> captured = ArgumentCaptor.forClass(BossBar.class);
        verify(player).showBossBar(captured.capture());
        InOrder handoff = inOrder(combat, player);
        handoff.verify(combat).suppressBossBar(player);
        handoff.verify(player).showBossBar(any(BossBar.class));
        BossBar bar = captured.getValue();
        assertEquals(BossBar.Color.RED, bar.color());
        assertEquals(0.5f, bar.progress(), 0.001f);
        assertEquals(TextColor.fromHexString("#EE4B00"), bar.name().color());

        PlayerCommandPreprocessEvent toggle = mock(PlayerCommandPreprocessEvent.class);
        when(toggle.getPlayer()).thenReturn(player);
        when(toggle.getMessage()).thenReturn("/clx toggle bossbar");
        display.onCombatLogXBossBarToggle(toggle);
        verify(toggle).setCancelled(true);

        when(combat.inCombat(player)).thenReturn(false);
        display.reconcile(List.of(player));
        verify(player).hideBossBar(bar);
        handoff.verify(player).hideBossBar(bar);
        handoff.verify(combat).restoreBossBar(player);
    }

    @Test void failedCombatLogXSuppressionNeverShowsWarzoneBar() {
        CombatLogXGateway combat = mock(CombatLogXGateway.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        Player player = mock(Player.class);
        Location warzone = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(combat.available()).thenReturn(true);
        when(combat.inCombat(player)).thenReturn(true);
        when(combat.remaining(player)).thenReturn(Duration.ofSeconds(15));
        when(worldGuard.warzoneCombatZoneAllowed(warzone, player)).thenReturn(true);
        CombatScopeService scopes = new CombatScopeService(combat, worldGuard);
        assertTrue(scopes.acquireIfEligible(player, warzone));

        new WarzoneCombatBar(scopes, WarzoneConfig.WarzoneTag.defaults())
                .reconcile(List.of(player));

        verify(combat).suppressBossBar(player);
        verify(player, never()).showBossBar(any(BossBar.class));
    }
}
