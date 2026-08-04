package com.lincoln.maceguard.warzone.gui;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.rotation.ActiveSelection;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.rotation.SelectionSourceType;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarzoneGuiSecurityTest {
    @Test void everyClickAgainstManagedTopInventoryIsCancelledBeforeActionHandling() {
        WarzoneGuiManager manager = manager(mock(WarzoneRuntime.class));
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder()).thenReturn(new WarzoneGuiManager.ManagedHolder(
                UUID.randomUUID(), WarzoneGuiManager.Screen.MAIN));

        manager.onClick(event);
        verify(event).setCancelled(true);
    }

    @Test void dragIntoManagedInventoryIsCancelled() {
        WarzoneGuiManager manager = manager(mock(WarzoneRuntime.class));
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getHolder()).thenReturn(new WarzoneGuiManager.ManagedHolder(
                UUID.randomUUID(), WarzoneGuiManager.Screen.MODIFIERS));

        manager.onDrag(event);
        verify(event).setCancelled(true);
    }

    @Test void matchingCloseCancelsPendingSession() {
        WarzoneRuntime runtime = runtimeWith(active(List.of("cobwebs")));
        WarzoneGuiManager manager = manager(runtime);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        WarzoneGuiManager.Session session = manager.start(player,
                WarzoneGuiManager.Operation.MODIFIER_ADD);
        session.screen = WarzoneGuiManager.Screen.PREVIEW;
        session.currentViewId = UUID.randomUUID();
        WarzoneGuiManager.ManagedHolder holder = new WarzoneGuiManager.ManagedHolder(
                session.id, session.screen, session.currentViewId);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getPlayer()).thenReturn(player);

        manager.onClose(event);
        assertEquals(0, manager.sessionCount());
    }

    @Test void staleOriginalSelectionIsRejectedAtFinalConfirmation() {
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.activeSelection()).thenReturn(active(List.of("cobwebs")),
                active(List.of("no-lunge")));
        WarzoneGuiManager manager = manager(runtime);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        WarzoneGuiManager.Session session = manager.start(player,
                WarzoneGuiManager.Operation.MODIFIER_ADD);

        assertThrows(IllegalStateException.class,
                () -> manager.requireUnchangedOriginal(session));
    }

    private WarzoneGuiManager manager(WarzoneRuntime runtime) {
        return new WarzoneGuiManager(mock(JavaPlugin.class), runtime);
    }

    private WarzoneRuntime runtimeWith(ActiveSelection selection) {
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.activeSelection()).thenReturn(selection);
        return runtime;
    }

    private ActiveSelection active(List<String> ids) {
        WarzoneConfig.ActiveSet set = new WarzoneConfig.ActiveSet(ids, "test", "test",
                Set.of(), Map.of());
        return new ActiveSelection(SelectionSourceType.CUSTOM_OVERRIDE, null, set, true);
    }
}
