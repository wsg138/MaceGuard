package com.lincoln.maceguard.warzone.combat;

import com.github.sirblobman.combatlogx.api.ICombatLogX;
import com.github.sirblobman.combatlogx.api.event.PlayerTagEvent;
import com.github.sirblobman.combatlogx.api.event.PlayerUntagEvent;
import com.github.sirblobman.combatlogx.api.manager.ICombatManager;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class DirectCombatLogXGatewayTest {
    @Test void delegatesOnlyToPublicCombatManagerTypes() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Plugin candidate = combatLogXPlugin();
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
        Plugin candidate = combatLogXPlugin();
        when(((ICombatLogX) candidate).getCombatManager()).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> DirectCombatLogXGateway.connect(owner, candidate));
    }

    @Test void defersTagCallbackWithCapturedEventLocation() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getScheduler()).thenReturn(scheduler);

        Plugin candidate = combatLogXPlugin();
        when(((ICombatLogX) candidate).getCombatManager()).thenReturn(mock(ICombatManager.class));
        DirectCombatLogXGateway gateway = DirectCombatLogXGateway.connect(owner, candidate);
        CombatLogXGateway.Lifecycle lifecycle = mock(CombatLogXGateway.Lifecycle.class);
        gateway.register(lifecycle);

        Player player = mock(Player.class);
        Location source = mock(Location.class);
        Location captured = mock(Location.class);
        PlayerTagEvent event = mock(PlayerTagEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(player.getLocation()).thenReturn(source);
        when(source.clone()).thenReturn(captured);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        when(scheduler.runTask(eq(owner), any(Runnable.class))).thenAnswer(invocation -> {
            deferred.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        gateway.onTag(event);
        deferred.get().run();

        verify(lifecycle).tagged(player, captured);
    }

    @Test void forwardsPostRemovalUntagImmediately() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        Plugin candidate = combatLogXPlugin();
        when(((ICombatLogX) candidate).getCombatManager()).thenReturn(mock(ICombatManager.class));
        DirectCombatLogXGateway gateway = DirectCombatLogXGateway.connect(owner, candidate);
        CombatLogXGateway.Lifecycle lifecycle = mock(CombatLogXGateway.Lifecycle.class);
        gateway.register(lifecycle);
        Player player = mock(Player.class);
        PlayerUntagEvent event = mock(PlayerUntagEvent.class);
        when(event.getPlayer()).thenReturn(player);

        gateway.onUntag(event);

        verify(lifecycle).untagged(player);
    }

    private Plugin combatLogXPlugin() {
        return mock(Plugin.class, withSettings().extraInterfaces(ICombatLogX.class));
    }
}
