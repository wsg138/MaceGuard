package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpearProjectileTrackerTest {
    @Test void recordsOnlySpearProjectiles() {
        SpearProjectileTracker tracker = new SpearProjectileTracker();
        UUID projectile = UUID.randomUUID();
        tracker.record(projectile, UUID.randomUUID(), Material.ENDER_PEARL, true, false, 100);
        assertEquals(0, tracker.size());
        tracker.record(projectile, UUID.randomUUID(), Material.WOODEN_SPEAR, true, false, 100);
        assertEquals(1, tracker.size());
    }

    @Test void findDoesNotConsumeButRemoveDoes() {
        SpearProjectileTracker tracker = new SpearProjectileTracker();
        UUID projectile = UUID.randomUUID();
        tracker.record(projectile, UUID.randomUUID(), Material.WOODEN_SPEAR, true, false, 100);
        assertTrue(tracker.find(projectile, 50).isPresent());
        assertTrue(tracker.find(projectile, 50).isPresent());
        assertTrue(tracker.remove(projectile, 50).isPresent());
        assertTrue(tracker.find(projectile, 50).isEmpty());
    }

    @Test void expiredAttemptIsNeverReturned() {
        SpearProjectileTracker tracker = new SpearProjectileTracker();
        UUID projectile = UUID.randomUUID();
        tracker.record(projectile, UUID.randomUUID(), Material.WOODEN_SPEAR, false, false, 100);
        assertTrue(tracker.find(projectile, 100).isEmpty());
        assertEquals(0, tracker.size());
    }


    @Test void attemptPreservesLaunchScopeAndBypassSnapshot() {
        SpearProjectileTracker tracker = new SpearProjectileTracker();
        UUID projectile = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        tracker.record(projectile, owner, Material.WOODEN_SPEAR, true, true, 100);

        SpearProjectileTracker.Attempt attempt = tracker.find(projectile, 50).orElseThrow();
        assertEquals(owner, attempt.playerId());
        assertEquals(Material.WOODEN_SPEAR, attempt.material());
        assertTrue(attempt.sourceInside());
        assertTrue(attempt.bypass());
    }

    @Test void cleanupRemovesOnlyExpiredAttempts() {
        SpearProjectileTracker tracker = new SpearProjectileTracker();
        tracker.record(UUID.randomUUID(), UUID.randomUUID(), Material.WOODEN_SPEAR, true, false, 10);
        tracker.record(UUID.randomUUID(), UUID.randomUUID(), Material.WOODEN_SPEAR, true, true, 20);
        assertEquals(1, tracker.cleanup(15));
        assertEquals(1, tracker.size());
    }
}
