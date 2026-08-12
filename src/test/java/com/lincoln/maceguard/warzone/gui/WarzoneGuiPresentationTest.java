package com.lincoln.maceguard.warzone.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarzoneGuiPresentationTest {
    @Test
    void internalIdentifiersAreHumanizedForFallbackDisplay() {
        assertEquals("Ender Pearl Cooldown",
                WarzoneGuiManager.humanizeIdentifier("ender-pearl-cooldown"));
        assertEquals("Spear Lunge",
                WarzoneGuiManager.humanizeIdentifier("SPEAR_LUNGE"));
        assertEquals("Wind Charge Cooldown 10",
                WarzoneGuiManager.humanizeIdentifier("wind-charge-cooldown-10"));
        assertEquals("Mace Disabled",
                WarzoneGuiManager.humanizeIdentifier("mace_disabled"));
    }

    @Test
    void emptyIdentifiersHaveAReadableFallback() {
        assertEquals("None", WarzoneGuiManager.humanizeIdentifier(null));
        assertEquals("None", WarzoneGuiManager.humanizeIdentifier("  "));
    }
}
