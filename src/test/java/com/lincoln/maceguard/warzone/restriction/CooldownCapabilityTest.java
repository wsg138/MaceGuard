package com.lincoln.maceguard.warzone.restriction;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CooldownCapabilityTest {
    @Test void classifiesRegistryIndependentSuccessEvents() {
        assertTrue(RestrictionTarget.parse("ENDER_PEARL").orElseThrow()
                .supports(CooldownCapability.PROJECTILE));
        assertTrue(RestrictionTarget.parse("DIAMOND_SWORD").orElseThrow()
                .supports(CooldownCapability.DIRECT_ATTACK));
        assertTrue(RestrictionTarget.SPEAR_LUNGE.supports(CooldownCapability.LUNGE_EFFECT));
        assertFalse(RestrictionTarget.parse("STONE").orElseThrow().supportsCooldown());
        assertFalse(RestrictionTarget.parse("SHIELD").orElseThrow().supportsCooldown());
    }

    @Test void visibleCooldownTicksRoundUpAndClampAtZero() {
        assertEquals(0, VisualCooldownService.toTicks(Duration.ZERO));
        assertEquals(1, VisualCooldownService.toTicks(Duration.ofMillis(1)));
        assertEquals(1, VisualCooldownService.toTicks(Duration.ofMillis(50)));
        assertEquals(2, VisualCooldownService.toTicks(Duration.ofMillis(51)));
        assertEquals(300, VisualCooldownService.toTicks(Duration.ofSeconds(15)));
    }

    @Test void strongerExistingCooldownIsNeverShortened() {
        assertFalse(VisualCooldownService.shouldApply(400, 300));
        assertFalse(VisualCooldownService.shouldApply(300, 300));
        assertTrue(VisualCooldownService.shouldApply(20, 300));
    }

    @Test void removingOwnedOverlayRestoresOnlyRecognizablePreviousState() {
        assertEquals(20, VisualCooldownService.reconciledTicks(299, 300, 20));
        assertEquals(-1, VisualCooldownService.reconciledTicks(450, 300, 20));
        assertEquals(-1, VisualCooldownService.reconciledTicks(100, 300, 20));
    }

    @Test void previousAndOwnedCooldownsUseActualServerTicks() {
        assertEquals(250, VisualCooldownService.remainingTicks(300, 50));
        assertEquals(0, VisualCooldownService.remainingTicks(20, 50));
        assertEquals(300, VisualCooldownService.remainingTicks(300, -5));
    }
}
