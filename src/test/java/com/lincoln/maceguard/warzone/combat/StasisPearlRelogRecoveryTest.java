package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Server;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static com.lincoln.maceguard.warzone.combat.PersistentDataTestSupport.container;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StasisPearlRelogRecoveryTest {
    private JavaPlugin plugin;

    @AfterEach void clearDiagnostics() {
        if (plugin != null) PearlEventDiagnostics.forPlugin(plugin).clear();
    }

    @Test void exactRecreatedIdentityRecoversDuringOwnedPearlReconciliation() {
        RecoveryFixture fixture = fixture();
        long originalLaunch = 5_000L;
        fixture.ledger.record(fixture.player, fixture.replacementId, originalLaunch);

        fixture.listener.reconcileOwnedPearls(fixture.player);

        StasisPearlMetadata.ReadResult restored = fixture.metadata.read(
                fixture.replacement, fixture.ownerId, 70_000L);
        assertTrue(restored.marked());
        assertFalse(restored.failClosed());
        assertEquals(originalLaunch, restored.launchedAtMillis());
    }

    @Test void validMarkedReplacementMigratesUniqueObsoleteLedgerUuid() {
        RecoveryFixture fixture = fixture();
        UUID obsoleteId = UUID.randomUUID();
        long originalLaunch = 5_000L;
        fixture.ledger.record(fixture.player, obsoleteId, originalLaunch);
        fixture.metadata.mark(fixture.replacement, fixture.ownerId, originalLaunch);

        fixture.listener.reconcileOwnedPearls(fixture.player);

        Map<UUID, Long> persisted = fixture.ledger.read(fixture.playerData);
        assertFalse(persisted.containsKey(obsoleteId));
        assertEquals(originalLaunch, persisted.get(fixture.replacementId));
        assertEquals(1, persisted.size());
    }

    @Test void replacementIdentityAtImpactConsumesOldestDurableLaunch() {
        RecoveryFixture fixture = fixture();
        UUID oldestId = UUID.randomUUID();
        UUID newerId = UUID.randomUUID();
        fixture.ledger.record(fixture.player, oldestId, 5_000L);
        fixture.ledger.record(fixture.player, newerId, 6_000L);

        StasisPearlMetadata.ReadResult restored = fixture.listener.recoverMetadata(
                fixture.replacement, fixture.ownerId, 70_000L);

        assertTrue(restored.marked());
        assertFalse(restored.failClosed());
        assertEquals(5_000L, restored.launchedAtMillis());
        Map<UUID, Long> persisted = fixture.ledger.read(fixture.playerData);
        assertFalse(persisted.containsKey(oldestId));
        assertEquals(5_000L, persisted.get(fixture.replacementId));
        assertEquals(6_000L, persisted.get(newerId));
    }

    @Test void unmarkedPearlWithoutDurableOwnerRecordStaysUnmarked() {
        RecoveryFixture fixture = fixture();

        StasisPearlMetadata.ReadResult restored = fixture.listener.recoverMetadata(
                fixture.replacement, fixture.ownerId, 70_000L);

        assertFalse(restored.marked());
    }

    private RecoveryFixture fixture() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        EnderPearl replacement = mock(EnderPearl.class);
        PersistentDataContainer playerData = container();
        PersistentDataContainer pearlData = container();
        UUID ownerId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();

        when(plugin.getName()).thenReturn("MaceGuard");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StasisPearlRelogRecoveryTest"));
        when(server.getPlayer(ownerId)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(ownerId);
        when(player.getPersistentDataContainer()).thenReturn(playerData);
        when(player.getEnderPearls()).thenReturn(List.of(replacement));
        when(replacement.getUniqueId()).thenReturn(replacementId);
        when(replacement.getOwnerUniqueId()).thenReturn(ownerId);
        when(replacement.getPersistentDataContainer()).thenReturn(pearlData);
        when(replacement.getServer()).thenReturn(server);

        StasisPearlListener.TimeSource time = new StasisPearlListener.TimeSource() {
            @Override public long wallMillis() { return 70_000L; }
            @Override public long nanoTime() { return 70_000_000_000L; }
        };
        StasisPearlListener listener = new StasisPearlListener(
                mock(CombatScopeService.class), mock(StasisPearlTracker.class),
                mock(WarzoneMessageService.class), Duration.ofSeconds(60), plugin, time);
        return new RecoveryFixture(listener, new StasisPearlLedger(), new StasisPearlMetadata(),
                player, replacement, playerData, ownerId, replacementId);
    }

    private record RecoveryFixture(StasisPearlListener listener, StasisPearlLedger ledger,
                                   StasisPearlMetadata metadata, Player player,
                                   EnderPearl replacement, PersistentDataContainer playerData,
                                   UUID ownerId, UUID replacementId) { }
}
