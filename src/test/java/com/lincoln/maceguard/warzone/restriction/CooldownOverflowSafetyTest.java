package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CooldownOverflowSafetyTest {
    @Test void authoritativeExpirySaturatesInsteadOfThrowing() {
        long now = Long.MAX_VALUE - 5L;
        CooldownService service = new CooldownService(() -> now);
        UUID player = UUID.randomUUID();

        assertDoesNotThrow(() -> service.start(player,
                RestrictionTarget.parse("ENDER_PEARL").orElseThrow(), Duration.ofMillis(10)));
        assertEquals(Duration.ofMillis(5), service.remaining(player,
                RestrictionTarget.parse("ENDER_PEARL").orElseThrow()));
    }

    @Test void extremeDurationConvertsToSaturatedVisualTicks() {
        assertEquals(Integer.MAX_VALUE,
                VisualCooldownService.toTicks(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test void saturatedOwnedVisualCooldownRemainsRecognizable() {
        assertEquals(10, VisualCooldownService.reconciledTicks(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 10));
        assertEquals(-1, VisualCooldownService.reconciledTicks(
                Integer.MAX_VALUE, Integer.MAX_VALUE - 3, 10));
    }

    @Test void materialTargetStillNormalizesAtSaturatedExpiry() {
        CooldownService service = new CooldownService(() -> Long.MAX_VALUE - 1L);
        UUID player = UUID.randomUUID();
        RestrictionTarget target = RestrictionTarget.parse("MACE").orElseThrow();
        service.start(player, target, Duration.ofSeconds(1), Material.MACE);
        assertEquals(Material.MACE, service.concreteMaterial(player, target));
    }
}
