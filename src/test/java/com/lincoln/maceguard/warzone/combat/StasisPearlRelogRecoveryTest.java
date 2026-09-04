package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StasisPearlRelogRecoveryTest {
    private JavaPlugin plugin;

    @AfterEach void clearDiagnostics() {
        if (plugin != null) PearlEventDiagnostics.forPlugin(plugin).clear();
    }

    @Test void reconstructedPearlGetsOriginalLaunchTimeAndNewIdentityIsPersisted() {
        UUID ownerId = UUID.randomUUID();
        UUID originalPearlId = UUID.randomUUID();
        UUID replacementPearlId = UUID.randomUUID();
        long originalLaunch = 5_000L;

        Player player = mock(Player.class);
        PersistentDataContainer playerData = container();
        when(player.getUniqueId()).thenReturn(ownerId);
        when(player.getPersistentDataContainer()).thenReturn(playerData);

        EnderPearl replacement = mock(EnderPearl.class);
        PersistentDataContainer pearlData = container();
        when(replacement.getUniqueId()).thenReturn(replacementPearlId);
        when(replacement.getOwnerUniqueId()).thenReturn(ownerId);
        when(replacement.getPersistentDataContainer()).thenReturn(pearlData);
        when(player.getEnderPearls()).thenReturn(List.of(replacement));

        StasisPearlLedger ledger = new StasisPearlLedger();
        ledger.record(player, originalPearlId, originalLaunch);

        StasisPearlListener listener = listener();
        listener.reconcileOwnedPearls(player);

        StasisPearlMetadata.ReadResult restored =
                new StasisPearlMetadata().read(replacement, ownerId, 70_000L);
        assertTrue(restored.marked());
        assertFalse(restored.failClosed());
        assertEquals(originalLaunch, restored.launchedAtMillis());

        Map<UUID, Long> persisted = ledger.read(playerData);
        assertFalse(persisted.containsKey(originalPearlId));
        assertEquals(originalLaunch, persisted.get(replacementPearlId));
    }

    @Test void ambiguousReconstructionDoesNotGuessWhichPearlWasTracked() {
        UUID ownerId = UUID.randomUUID();
        Player player = mock(Player.class);
        PersistentDataContainer playerData = container();
        when(player.getUniqueId()).thenReturn(ownerId);
        when(player.getPersistentDataContainer()).thenReturn(playerData);

        EnderPearl replacement = mock(EnderPearl.class);
        PersistentDataContainer pearlData = container();
        when(replacement.getUniqueId()).thenReturn(UUID.randomUUID());
        when(replacement.getOwnerUniqueId()).thenReturn(ownerId);
        when(replacement.getPersistentDataContainer()).thenReturn(pearlData);
        when(player.getEnderPearls()).thenReturn(List.of(replacement));

        StasisPearlLedger ledger = new StasisPearlLedger();
        ledger.record(player, UUID.randomUUID(), 5_000L);
        ledger.record(player, UUID.randomUUID(), 6_000L);

        listener().reconcileOwnedPearls(player);

        assertFalse(new StasisPearlMetadata().read(replacement, ownerId, 70_000L).marked());
        assertEquals(2, ledger.read(playerData).size());
    }

    private StasisPearlListener listener() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getName()).thenReturn("MaceGuard");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StasisPearlRelogRecoveryTest"));
        StasisPearlListener.TimeSource time = new StasisPearlListener.TimeSource() {
            @Override public long wallMillis() { return 70_000L; }
            @Override public long nanoTime() { return 70_000_000_000L; }
        };
        return new StasisPearlListener(mock(CombatScopeService.class),
                mock(StasisPearlTracker.class), mock(WarzoneMessageService.class),
                Duration.ofSeconds(60), plugin, time);
    }

    private PersistentDataContainer container() {
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> values = new HashMap<>();
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        when(data.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            values.remove(invocation.getArgument(0));
            return null;
        }).when(data).remove(any(NamespacedKey.class));
        return data;
    }
}
