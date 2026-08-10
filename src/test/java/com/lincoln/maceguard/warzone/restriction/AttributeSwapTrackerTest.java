package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AttributeSwapTrackerTest {
    @Test void tracksMaceAndSpearOnlyInsideBoundedWindow() {
        AtomicLong clock = new AtomicLong(1_000L);
        AttributeSwapTracker tracker = new AttributeSwapTracker(clock::get, Duration.ofNanos(250));
        UUID player = UUID.randomUUID();

        tracker.recordTransition(player, Material.MACE, Material.DIAMOND_SWORD);
        assertEquals(Material.MACE, tracker.recent(player).orElseThrow());

        clock.addAndGet(251L);
        assertTrue(tracker.recent(player).isEmpty());

        tracker.recordTransition(player, Material.IRON_SPEAR, Material.DIAMOND_AXE);
        assertEquals(Material.IRON_SPEAR, tracker.recent(player).orElseThrow());

        tracker.recordTransition(player, Material.DIAMOND_SWORD, Material.DIAMOND_AXE);
        assertEquals(Material.IRON_SPEAR, tracker.recent(player).orElseThrow(),
                "unrelated swaps must not erase the still-live restricted source");
    }

    @Test void bindsAtMostOneImmediateAttackPerPlayerAndTarget() {
        AtomicLong clock = new AtomicLong(5_000L);
        AttributeSwapTracker tracker = new AttributeSwapTracker(clock::get, Duration.ofNanos(250));
        UUID player = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID otherTarget = UUID.randomUUID();
        RestrictionDecision ready = new RestrictionDecision(
                RestrictionDecision.Result.COOLDOWN_READY,
                RestrictionTarget.parse("MACE").orElseThrow(), null, Duration.ZERO);

        tracker.recordAttack(player, target, Material.MACE, ready, RestrictionDecision.unrestricted());
        assertTrue(tracker.findAttack(player, otherTarget).isEmpty());
        assertTrue(tracker.findAttack(player, target).isPresent());
        assertEquals(Material.MACE, tracker.consumeAttack(player, target).orElseThrow().material());
        assertTrue(tracker.consumeAttack(player, target).isEmpty());
    }

    @Test void expiredAttackCannotStartCooldownLater() {
        AtomicLong clock = new AtomicLong(9_000L);
        AttributeSwapTracker tracker = new AttributeSwapTracker(clock::get, Duration.ofNanos(100));
        UUID player = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        tracker.recordAttack(player, target, Material.GOLDEN_SPEAR,
                RestrictionDecision.unrestricted(), RestrictionDecision.unrestricted());
        clock.addAndGet(101L);
        assertTrue(tracker.consumeAttack(player, target).isEmpty());
    }
}
