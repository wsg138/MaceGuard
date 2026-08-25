package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class DirectCombatLogXGatewayTest {
    @Test void delegatesThroughRuntimeValidatedPublicMethods() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Plugin candidate = combatLogXPlugin();
        FakeCombatLogXApi api = (FakeCombatLogXApi) candidate;
        FakeCombatManager manager = mock(FakeCombatManager.class);
        FakeTagInformation information = mock(FakeTagInformation.class);
        Player player = mock(Player.class);
        when(api.getCombatManager()).thenReturn(manager);
        when(manager.isInCombat(player)).thenReturn(true);
        when(manager.canBypass(player)).thenReturn(false);
        when(manager.getMaxTimerSeconds(player)).thenReturn(30);
        when(manager.getTagInformation(player)).thenReturn(information);
        when(information.getMillisLeftCombined()).thenReturn(1_250L);

        DirectCombatLogXGateway gateway = connect(owner, candidate);

        assertTrue(gateway.available());
        assertTrue(gateway.inCombat(player));
        assertFalse(gateway.bypass(player));
        assertEquals(30, gateway.maximumSeconds(player));
        assertEquals(Duration.ofMillis(1_250), gateway.remaining(player));
    }

    @Test void rejectsMissingCombatManager() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Plugin candidate = combatLogXPlugin();
        when(((FakeCombatLogXApi) candidate).getCombatManager()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> connect(owner, candidate));
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
        when(((FakeCombatLogXApi) candidate).getCombatManager()).thenReturn(mock(FakeCombatManager.class));
        DirectCombatLogXGateway gateway = connect(owner, candidate);
        CombatLogXGateway.Lifecycle lifecycle = mock(CombatLogXGateway.Lifecycle.class);
        gateway.register(lifecycle);

        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location source = mock(Location.class);
        Location captured = mock(Location.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(player.getLocation()).thenReturn(source);
        when(source.clone()).thenReturn(captured);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        when(scheduler.runTask(eq(owner), any(Runnable.class))).thenAnswer(invocation -> {
            deferred.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        gateway.handleTag(new FakeTagEvent(player));
        deferred.get().run();

        verify(lifecycle).tagged(player, captured);
    }

    @Test void deferredTagCannotCrossAReconnectSession() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getScheduler()).thenReturn(scheduler);

        Plugin candidate = combatLogXPlugin();
        when(((FakeCombatLogXApi) candidate).getCombatManager()).thenReturn(mock(FakeCombatManager.class));
        DirectCombatLogXGateway gateway = connect(owner, candidate);
        CombatLogXGateway.Lifecycle lifecycle = mock(CombatLogXGateway.Lifecycle.class);
        gateway.register(lifecycle);

        Player original = mock(Player.class);
        Player replacement = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location source = mock(Location.class);
        Location captured = mock(Location.class);
        when(original.getUniqueId()).thenReturn(playerId);
        when(original.isOnline()).thenReturn(true);
        when(server.getPlayer(playerId)).thenReturn(replacement);
        when(original.getLocation()).thenReturn(source);
        when(source.clone()).thenReturn(captured);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        when(scheduler.runTask(eq(owner), any(Runnable.class))).thenAnswer(invocation -> {
            deferred.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        gateway.handleTag(new FakeTagEvent(original));
        deferred.get().run();

        verifyNoInteractions(lifecycle);
    }

    @Test void closeReleasesCombatManagerAndFencesDeferredTag() throws Exception {
        JavaPlugin owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getScheduler()).thenReturn(scheduler);

        Plugin candidate = combatLogXPlugin();
        when(((FakeCombatLogXApi) candidate).getCombatManager()).thenReturn(mock(FakeCombatManager.class));
        DirectCombatLogXGateway gateway = connect(owner, candidate);
        CombatLogXGateway.Lifecycle lifecycle = mock(CombatLogXGateway.Lifecycle.class);
        gateway.register(lifecycle);

        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location source = mock(Location.class);
        Location captured = mock(Location.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(source);
        when(source.clone()).thenReturn(captured);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        when(scheduler.runTask(eq(owner), any(Runnable.class))).thenAnswer(invocation -> {
            deferred.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });

        gateway.handleTag(new FakeTagEvent(player));
        gateway.close();
        assertFalse(gateway.available());
        assertThrows(IllegalStateException.class, () -> gateway.inCombat(player));
        deferred.get().run();

        verifyNoInteractions(lifecycle);
    }

    @Test void forwardsPostRemovalUntagImmediately() {
        JavaPlugin owner = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        Plugin candidate = combatLogXPlugin();
        when(((FakeCombatLogXApi) candidate).getCombatManager()).thenReturn(mock(FakeCombatManager.class));
        DirectCombatLogXGateway gateway = connect(owner, candidate);
        CombatLogXGateway.Lifecycle lifecycle = mock(CombatLogXGateway.Lifecycle.class);
        gateway.register(lifecycle);
        Player player = mock(Player.class);

        gateway.handleUntag(new FakeUntagEvent(player));

        verify(lifecycle).untagged(player);
    }

    private DirectCombatLogXGateway connect(JavaPlugin owner, Plugin candidate) {
        return DirectCombatLogXGateway.connect(owner, candidate,
                FakeTagEvent.class.getName(), FakeReTagEvent.class.getName(), FakeUntagEvent.class.getName());
    }

    private Plugin combatLogXPlugin() {
        return mock(Plugin.class, withSettings().extraInterfaces(FakeCombatLogXApi.class));
    }

    public interface FakeCombatLogXApi {
        FakeCombatManager getCombatManager();
    }

    public interface FakeCombatManager {
        boolean isInCombat(Player player);
        boolean canBypass(Player player);
        int getMaxTimerSeconds(Player player);
        FakeTagInformation getTagInformation(Player player);
    }

    public interface FakeTagInformation {
        long getMillisLeftCombined();
    }

    public static final class FakeTagEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        FakeTagEvent(Player player) { this.player = player; }
        public Player getPlayer() { return player; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class FakeReTagEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        FakeReTagEvent(Player player) { this.player = player; }
        public Player getPlayer() { return player; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class FakeUntagEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        FakeUntagEvent(Player player) { this.player = player; }
        public Player getPlayer() { return player; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}
