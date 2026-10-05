package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Server;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WarzoneRetagListenerTest {
    private final CombatScopeService scopes = mock(CombatScopeService.class);
    private final CombatLogXGateway combat = mock(CombatLogXGateway.class);
    private final Player attacker = mock(Player.class);
    private final Player victim = mock(Player.class);
    private final List<Runnable> pending = new ArrayList<>();
    private WarzoneRetagListener listener;

    @BeforeEach void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            pending.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scopes.combat()).thenReturn(combat);
        for (Player player : List.of(attacker, victim)) {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(scopes.warzoneTagged(player)).thenReturn(true);
            when(combat.maximumSeconds(player)).thenReturn(30);
            when(combat.remaining(player)).thenReturn(Duration.ofSeconds(10));
        }
        listener = new WarzoneRetagListener(plugin, scopes);
    }

    private EntityDamageByEntityEvent hit() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(2D);
        return event;
    }

    @Test void pvpRefreshesBothExistingTagsAfterOtherListeners() {
        listener.onPvpDamage(hit());
        verifyNoInteractions(combat);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, victim, true);
        verify(combat).retag(victim, attacker, false);
    }

    @Test void projectileUsesItsPlayerShooter() {
        EntityDamageByEntityEvent event = hit();
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(attacker);
        when(event.getDamager()).thenReturn(arrow);
        listener.onPvpDamage(event);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, victim, true);
    }

    @Test void ordinaryCombatIsNotPromotedToWarzoneCombat() {
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        when(scopes.warzoneTagged(victim)).thenReturn(false);
        listener.onPvpDamage(hit());
        assertTrue(pending.isEmpty());
        verifyNoInteractions(combat);
    }

    @Test void selfDamageAndZeroDamageDoNotRefresh() {
        EntityDamageByEntityEvent event = hit();
        when(event.getDamager()).thenReturn(victim);
        listener.onPvpDamage(event);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(0D);
        listener.onPvpDamage(event);
        assertTrue(pending.isEmpty());
    }

    @Test void disconnectExpiredTagAndAlreadyRefreshedTimerAreFenced() {
        listener.onPvpDamage(hit());
        when(attacker.isOnline()).thenReturn(false);
        when(scopes.warzoneTagged(victim)).thenReturn(false);
        pending.forEach(Runnable::run);
        verifyNoInteractions(combat);
        pending.clear();
        when(attacker.isOnline()).thenReturn(true);
        when(scopes.warzoneTagged(victim)).thenReturn(true);
        when(combat.remaining(attacker)).thenReturn(Duration.ofSeconds(30));
        when(combat.remaining(victim)).thenReturn(Duration.ofSeconds(30));
        listener.onPvpDamage(hit());
        pending.forEach(Runnable::run);
        verify(combat, never()).retag(any(), any(), anyBoolean());
    }

    @Test void pearlThrowRefreshesOnlyExistingWarzoneTag() {
        ProjectileLaunchEvent event = mock(ProjectileLaunchEvent.class);
        EnderPearl pearl = mock(EnderPearl.class);
        when(event.getEntity()).thenReturn(pearl);
        when(pearl.getShooter()).thenReturn(attacker);
        listener.onPearlThrow(event);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, null, false);
        pending.clear();
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        listener.onPearlThrow(event);
        assertTrue(pending.isEmpty());
    }
}
