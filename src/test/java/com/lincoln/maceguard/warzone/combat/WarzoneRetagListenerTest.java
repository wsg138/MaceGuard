package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Server;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
    private boolean insideWarzone;
    private boolean windChargeEnabled = true;

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
        listener = new WarzoneRetagListener(plugin, scopes,
                player -> insideWarzone, player -> windChargeEnabled);
    }

    private EntityDamageByEntityEvent hit() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(2D);
        return event;
    }

    @Test void acceptedLungeInsideRefreshesOnlyWarzoneCombat() {
        listener.onAcceptedLunge(attacker, true);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, null, false);
        pending.clear();
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        listener.onAcceptedLunge(attacker, true);
        assertTrue(pending.isEmpty());
    }

    @Test void acceptedLungeOutsideRefreshesOrdinaryCombatWithoutCreatingWarzoneTag() {
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        when(scopes.combatBound(attacker)).thenReturn(true);
        listener.onAcceptedLunge(attacker, false);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, null, false);
        verify(scopes, never()).warzoneTagged(attacker);
    }

    @Test void outsideLungeDoesNotStartCombatOrRefreshAnExpiredTag() {
        listener.onAcceptedLunge(attacker, false);
        assertTrue(pending.isEmpty());
        when(scopes.combatBound(attacker)).thenReturn(true);
        listener.onAcceptedLunge(attacker, false);
        when(scopes.combatBound(attacker)).thenReturn(false);
        pending.forEach(Runnable::run);
        verify(combat, never()).retag(any(), any(), anyBoolean());
    }

    @Test void acceptedLungeIsFencedAfterRuntimeClose() {
        listener.onAcceptedLunge(attacker, true);
        listener.close();
        pending.forEach(Runnable::run);
        verify(combat, never()).retag(any(), any(), anyBoolean());
    }

    @Test void outsideLungeRespectsBypassDisconnectAndAlreadyRefreshedTimer() {
        when(scopes.combatBound(attacker)).thenReturn(true);
        when(attacker.hasPermission("warzonerotator.bypass")).thenReturn(true);
        listener.onAcceptedLunge(attacker, false);
        assertTrue(pending.isEmpty());
        when(attacker.hasPermission("warzonerotator.bypass")).thenReturn(false);
        listener.onAcceptedLunge(attacker, false);
        when(attacker.isOnline()).thenReturn(false);
        pending.forEach(Runnable::run);
        pending.clear();
        when(attacker.isOnline()).thenReturn(true);
        when(combat.remaining(attacker)).thenReturn(Duration.ofSeconds(30));
        listener.onAcceptedLunge(attacker, false);
        pending.forEach(Runnable::run);
        verify(combat, never()).retag(any(), any(), anyBoolean());
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
        listener.onProjectileLaunch(event);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, null, false);
        pending.clear();
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        listener.onProjectileLaunch(event);
        assertTrue(pending.isEmpty());
    }

    @Test void outsideWindChargeLaunchRefreshesOrdinaryCombat() {
        when(scopes.combatBound(attacker)).thenReturn(true);
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        ProjectileLaunchEvent event = mock(ProjectileLaunchEvent.class);
        var charge = mock(org.bukkit.entity.Projectile.class);
        when(charge.getType()).thenReturn(EntityType.WIND_CHARGE);
        when(charge.getShooter()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(charge);
        listener.onProjectileLaunch(event);
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, null, false);
    }

    private ProjectileLaunchEvent windCharge() {
        var event = mock(ProjectileLaunchEvent.class);
        var charge = mock(Projectile.class);
        when(charge.getType()).thenReturn(EntityType.WIND_CHARGE);
        when(charge.getShooter()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(charge);
        return event;
    }

    @Test void enabledWindChargeInsideRefreshesOnlyExistingWarzoneCombat() {
        insideWarzone = true;
        listener.onProjectileLaunch(windCharge());
        pending.forEach(Runnable::run);
        verify(combat).retag(attacker, null, false);
        pending.clear();
        when(scopes.warzoneTagged(attacker)).thenReturn(false);
        when(scopes.combatBound(attacker)).thenReturn(true);
        listener.onProjectileLaunch(windCharge());
        assertTrue(pending.isEmpty());
    }

    @Test void disabledWindChargeInsideDoesNotRetagEvenIfOtherPluginLaunchesIt() {
        insideWarzone = true;
        windChargeEnabled = false;
        listener.onProjectileLaunch(windCharge());
        assertTrue(pending.isEmpty());
        verifyNoInteractions(combat);
    }

    @Test void cancelledWindChargeAndNonPlayerSourcesDoNotRetag() {
        when(scopes.combatBound(attacker)).thenReturn(true);
        var cancelled = windCharge();
        when(cancelled.isCancelled()).thenReturn(true);
        listener.onProjectileLaunch(cancelled);
        var automated = windCharge();
        when(automated.getEntity().getShooter()).thenReturn(
                mock(org.bukkit.projectiles.BlockProjectileSource.class));
        listener.onProjectileLaunch(automated);
        var other = windCharge();
        when(other.getEntity().getType()).thenReturn(EntityType.SNOWBALL);
        listener.onProjectileLaunch(other);
        assertTrue(pending.isEmpty());
    }

    @Test void outsideWindChargeDoesNotStartCombatAndIsFencedOnClose() {
        listener.onProjectileLaunch(windCharge());
        assertTrue(pending.isEmpty());
        when(scopes.combatBound(attacker)).thenReturn(true);
        listener.onProjectileLaunch(windCharge());
        listener.close();
        pending.forEach(Runnable::run);
        verify(combat, never()).retag(any(), any(), anyBoolean());
    }
}
