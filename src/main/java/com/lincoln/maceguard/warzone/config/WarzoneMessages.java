package com.lincoln.maceguard.warzone.config;

public record WarzoneMessages(
        String itemDisabled,
        String itemCooldown,
        String abilityDisabled,
        String abilityCooldown,
        String cobwebUnavailable,
        String elytraUnavailable,
        String fireworkUnavailable,
        String stasisBlocked,
        String rotationWarning
) {
    public WarzoneMessages(String itemDisabled, String itemCooldown, String abilityDisabled,
                           String abilityCooldown, String cobwebUnavailable,
                           String elytraUnavailable, String fireworkUnavailable,
                           String rotationWarning) {
        this(itemDisabled, itemCooldown, abilityDisabled, abilityCooldown, cobwebUnavailable,
                elytraUnavailable, fireworkUnavailable,
                "<red>Your Ender Pearl was identified as a stasis pearl and could not teleport you during Warzone combat. If you believe this was an error, contact a staff member.",
                rotationWarning);
    }
}
