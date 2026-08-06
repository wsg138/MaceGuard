package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CooldownConcreteMaterialTest {
    @Test void wholeSpearCooldownSharesAuthorityButProjectsOnlyTheUsedMaterial() {
        AtomicLong clock = new AtomicLong(1_000L);
        CooldownService cooldowns = new CooldownService(clock::get);
        UUID player = UUID.randomUUID();
        cooldowns.start(player, RestrictionTarget.SPEAR, Duration.ofSeconds(10),
                Material.IRON_SPEAR);

        assertTrue(cooldowns.active(player, RestrictionTarget.SPEAR));
        assertEquals(Map.of(Material.IRON_SPEAR, Duration.ofSeconds(10)),
                cooldowns.activeVisualsFor(player));
        assertFalse(cooldowns.activeVisualsFor(player).containsKey(Material.WOODEN_SPEAR));
    }

    @Test void wholeSpearConcreteMaterialSurvivesReloadAndClamping() {
        AtomicLong clock = new AtomicLong(1_000L);
        UUID player = UUID.randomUUID();
        CooldownService old = new CooldownService(clock::get);
        old.start(player, RestrictionTarget.SPEAR, Duration.ofSeconds(30),
                Material.GOLDEN_SPEAR);
        clock.addAndGet(5_000L);

        CooldownService replacement = new CooldownService(clock::get);
        replacement.restore(old.snapshot(), Map.of(RestrictionTarget.SPEAR,
                Duration.ofSeconds(10)));
        assertEquals(Material.GOLDEN_SPEAR,
                replacement.concreteMaterial(player, RestrictionTarget.SPEAR));
        assertEquals(Duration.ofSeconds(10),
                replacement.activeVisualsFor(player).get(Material.GOLDEN_SPEAR));
    }

    @Test void spearDamageAndLungeNeverProduceWholeItemVisuals() {
        CooldownService cooldowns = new CooldownService(() -> 1_000L);
        UUID player = UUID.randomUUID();
        cooldowns.start(player, RestrictionTarget.SPEAR_DAMAGE, Duration.ofSeconds(10),
                Material.IRON_SPEAR);
        cooldowns.start(player, RestrictionTarget.SPEAR_LUNGE, Duration.ofSeconds(10),
                Material.IRON_SPEAR);
        assertTrue(cooldowns.activeVisualsFor(player).isEmpty());
    }

    @Test void invalidConcreteMaterialCannotCreateAWholeSpearOverlay() {
        CooldownService cooldowns = new CooldownService(() -> 1_000L);
        UUID player = UUID.randomUUID();
        cooldowns.start(player, RestrictionTarget.SPEAR, Duration.ofSeconds(10), Material.MACE);
        assertNull(cooldowns.concreteMaterial(player, RestrictionTarget.SPEAR));
        assertTrue(cooldowns.activeVisualsFor(player).isEmpty());
    }
}
