package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Server;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PaperLungeEventAdapterTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Listener owner = mock(Listener.class);
    private final Player player = mock(Player.class);
    private final PluginManager manager = mock(PluginManager.class);
    private BooleanSupplier accepted;
    private Player observed;
    private EventExecutor executor;

    @BeforeEach void setUp() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        PaperLungeEventAdapter.register(plugin, owner, NativeLunge.class, (actor, guard) -> {
            observed = actor;
            accepted = guard;
        });
        ArgumentCaptor<EventExecutor> capture = ArgumentCaptor.forClass(EventExecutor.class);
        verify(manager).registerEvent(eq(NativeLunge.class), eq(owner), eq(EventPriority.MONITOR),
                capture.capture(), eq(plugin), eq(true));
        executor = capture.getValue();
    }

    @Test void acceptsPositivePlayerLungeAndReadsFinalCancellation() throws Exception {
        NativeLunge event = new NativeLunge(player);
        executor.execute(owner, event);
        assertSame(player, observed);
        assertTrue(accepted.getAsBoolean());
        event.setCancelled(true);
        assertFalse(accepted.getAsBoolean());
    }

    @Test void zeroOrRemovedPowerIsNotAccepted() throws Exception {
        NativeLunge event = new NativeLunge(player);
        executor.execute(owner, event);
        event.power = 0;
        assertFalse(accepted.getAsBoolean());
        event.power = -1;
        assertFalse(accepted.getAsBoolean());
    }

    @Test void nonPlayerLungeIsIgnored() throws Exception {
        executor.execute(owner, new NativeLunge(mock(LivingEntity.class)));
        assertNull(observed);
        assertNull(accepted);
    }

    @Test void pinnedLegacyApiKeepsFallbackWithoutRegisteringNativeEvent() {
        assertFalse(PaperLungeEventAdapter.register(plugin, owner, (actor, guard) -> fail()));
    }

    @Test void incompatibleEventContractIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> PaperLungeEventAdapter.register(plugin, owner, EntityEvent.class,
                        (actor, guard) -> fail()));
    }

    /** Fixture matching the public 26.2 event contract, never packaged in the plugin. */
    public static final class NativeLunge extends EntityEvent implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private int power = 1;
        private boolean cancelled;
        NativeLunge(LivingEntity entity) { super(entity); }
        public int getLungePower() { return power; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}
