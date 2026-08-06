package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CooldownReloadHandoffTest {
    private static final RestrictionTarget PEARL =
            RestrictionTarget.parse("ENDER_PEARL").orElseThrow();

    @Test void internalCooldownSnapshotSurvivesAndClampsToShorterNewConfiguration() {
        AtomicLong clock = new AtomicLong(1_000L);
        UUID player = UUID.randomUUID();
        CooldownService old = new CooldownService(clock::get);
        old.start(player, PEARL, Duration.ofSeconds(30));
        clock.addAndGet(5_000L);

        CooldownService replacement = new CooldownService(clock::get);
        replacement.restore(old.snapshot(), Map.of(PEARL, Duration.ofSeconds(10)));
        assertEquals(Duration.ofSeconds(10), replacement.remaining(player, PEARL));
        old.clear();
        assertEquals(Duration.ofSeconds(10), replacement.remaining(player, PEARL));
    }

    @Test void removedModifierDoesNotRestoreAuthoritativeCooldown() {
        CooldownService old = new CooldownService(() -> 1_000L);
        UUID player = UUID.randomUUID();
        old.start(player, PEARL, Duration.ofSeconds(30));
        CooldownService replacement = new CooldownService(() -> 1_000L);
        replacement.restore(old.snapshot(), Map.of());
        assertFalse(replacement.active(player, PEARL));
    }

    @Test void replacementAdoptsOverlayAndOldReleaseDoesNotClearIt() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger cooldown = new AtomicInteger(300);
        AtomicLong tick = new AtomicLong(0L);
        Player player = mockPlayer(playerId, cooldown);
        Server server = mock(Server.class);
        when(server.getPlayer(playerId)).thenReturn(player);
        VisualCooldownService old = new VisualCooldownService(server, () -> 1_000L, tick::get);
        VisualCooldownService.Snapshot snapshot = new VisualCooldownService.Snapshot(List.of(
                new VisualCooldownService.SnapshotEntry(playerId, Material.ENDER_PEARL,
                        0, 300, 0L, 16_000L)));
        old.restore(snapshot);

        VisualCooldownService replacement = new VisualCooldownService(server, () -> 1_000L, tick::get);
        replacement.restore(old.snapshot());
        replacement.reapply(player, Map.of(PEARL, Duration.ofSeconds(10)));
        assertEquals(200, cooldown.get());
        old.releaseOwnership();
        old.clearOwned();
        assertEquals(200, cooldown.get());
    }

    @Test void failedReplacementCanRelinquishWithoutTouchingOldOverlay() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger cooldown = new AtomicInteger(300);
        Player player = mockPlayer(playerId, cooldown);
        Server server = mock(Server.class);
        when(server.getPlayer(playerId)).thenReturn(player);
        VisualCooldownService replacement = new VisualCooldownService(server, () -> 1_000L, () -> 0L);
        replacement.restore(new VisualCooldownService.Snapshot(List.of(
                new VisualCooldownService.SnapshotEntry(playerId, Material.ENDER_PEARL,
                        0, 300, 0L, 16_000L))));
        replacement.releaseOwnership();
        replacement.clearOwned();
        assertEquals(300, cooldown.get());
        verify(player, never()).setCooldown(any(Material.class), anyInt());
    }

    @Test void removedModifierClearsAdoptedOverlayAndDisableClearsOnlyOnce() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger cooldown = new AtomicInteger(300);
        Player player = mockPlayer(playerId, cooldown);
        Server server = mock(Server.class);
        when(server.getPlayer(playerId)).thenReturn(player);
        VisualCooldownService service = new VisualCooldownService(server, () -> 1_000L, () -> 0L);
        service.restore(new VisualCooldownService.Snapshot(List.of(
                new VisualCooldownService.SnapshotEntry(playerId, Material.ENDER_PEARL,
                        0, 300, 0L, 16_000L))));
        service.reapply(player, Map.of());
        assertEquals(0, cooldown.get());
        service.clearOwned();
        verify(player, times(1)).setCooldown(Material.ENDER_PEARL, 0);
    }

    private Player mockPlayer(UUID id, AtomicInteger cooldown) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getCooldown(any(Material.class))).thenAnswer(invocation -> cooldown.get());
        doAnswer(invocation -> {
            cooldown.set(invocation.getArgument(1));
            return null;
        }).when(player).setCooldown(any(Material.class), anyInt());
        return player;
    }
}
