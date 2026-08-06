package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedCombatLogXGatewayTest {
    @Test void registersDependencyListenerExactlyOnce() {
        Fixture fixture = new Fixture();
        FakeGateway initial = new FakeGateway(true, null);
        ManagedCombatLogXGateway managed = new ManagedCombatLogXGateway(fixture.owner, initial);
        RecordingLifecycle lifecycle = new RecordingLifecycle();

        managed.register(lifecycle);
        managed.register(lifecycle);

        verify(fixture.pluginManager).registerEvents(eq(managed), eq(fixture.owner));
        assertTrue(managed.lifecycleListenerRegistered());
        assertEquals(1, initial.registerCalls);
    }

    @Test void staleCallbacksCannotCrossRuntimeGeneration() {
        Fixture fixture = new Fixture();
        FakeGateway first = new FakeGateway(true, null);
        ManagedCombatLogXGateway managed = new ManagedCombatLogXGateway(fixture.owner, first);
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        managed.register(lifecycle);

        FakeGateway replacement = new FakeGateway(true, null);
        managed.replaceDelegateForTest(replacement);
        first.emitTagged(player, location);
        replacement.emitTagged(player, location);

        assertEquals(List.of("available", "tagged"), lifecycle.events);
        assertTrue(first.closed);
        assertFalse(replacement.closed);
    }

    @Test void unavailableReplacementClearsIntegrationAndDoesNotReuseOldObjects() {
        Fixture fixture = new Fixture();
        FakeGateway first = new FakeGateway(true, null);
        ManagedCombatLogXGateway managed = new ManagedCombatLogXGateway(fixture.owner, first);
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        managed.register(lifecycle);

        FakeGateway unavailable = new FakeGateway(false, "binary mismatch");
        managed.replaceDelegateForTest(unavailable);

        assertEquals(List.of("unavailable"), lifecycle.events);
        assertTrue(first.closed);
        assertFalse(managed.available());
        assertEquals("binary mismatch", managed.unavailableReason());
    }

    @Test void closeFencesAllDelayedCallbacks() {
        Fixture fixture = new Fixture();
        FakeGateway initial = new FakeGateway(true, null);
        ManagedCombatLogXGateway managed = new ManagedCombatLogXGateway(fixture.owner, initial);
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        managed.register(lifecycle);
        managed.close();

        initial.emitTagged(mock(Player.class), mock(Location.class));

        assertTrue(lifecycle.events.isEmpty());
        assertTrue(initial.closed);
        assertFalse(managed.lifecycleListenerRegistered());
    }

    private static final class Fixture {
        final JavaPlugin owner = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final PluginManager pluginManager = mock(PluginManager.class);

        Fixture() {
            when(owner.getServer()).thenReturn(server);
            when(owner.getLogger()).thenReturn(Logger.getLogger("ManagedCombatLogXGatewayTest"));
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getOnlinePlayers()).thenReturn(Set.of());
        }
    }

    private static final class RecordingLifecycle implements CombatLogXGateway.Lifecycle {
        final List<String> events = new CopyOnWriteArrayList<>();
        @Override public void tagged(Player player, Location tagLocation) { events.add("tagged"); }
        @Override public void untagged(Player player) { events.add("untagged"); }
        @Override public void integrationUnavailable() { events.add("unavailable"); }
        @Override public void integrationAvailable() { events.add("available"); }
    }

    private static final class FakeGateway implements CombatLogXGateway {
        final boolean availableState;
        final String reason;
        Lifecycle lifecycle;
        int registerCalls;
        boolean closed;

        FakeGateway(boolean available, String reason) {
            this.availableState = available;
            this.reason = reason;
        }

        @Override public boolean available() { return availableState; }
        @Override public String unavailableReason() { return reason; }
        @Override public boolean inCombat(Player player) { return false; }
        @Override public boolean bypass(Player player) { return false; }
        @Override public int maximumSeconds(Player player) { return 0; }
        @Override public Duration remaining(Player player) { return Duration.ZERO; }
        @Override public void register(Lifecycle lifecycle) {
            registerCalls++;
            this.lifecycle = lifecycle;
        }
        @Override public void close() { closed = true; }
        void emitTagged(Player player, Location location) {
            if (lifecycle != null) lifecycle.tagged(player, location);
        }
    }
}
