package com.lincoln.maceguard.warzone.config;

public record WarzoneMessages(
        String itemDisabled,
        String itemCooldown,
        String itemCooldownStarted,
        String abilityDisabled,
        String abilityCooldown,
        String abilityCooldownStarted,
        String cobwebUnavailable,
        String elytraUnavailable,
        String fireworkUnavailable,
        String blockPlaceDenied,
        String blockBreakDenied,
        String bucketUseDenied,
        String stasisBlocked,
        String rotationWarning
) {
    public WarzoneMessages(String itemDisabled, String itemCooldown, String abilityDisabled,
                           String abilityCooldown, String cobwebUnavailable,
                           String elytraUnavailable, String fireworkUnavailable,
                           String stasisBlocked, String rotationWarning) {
        this(itemDisabled, itemCooldown,
                "<yellow><ready_action> in <white><cooldown><yellow>.",
                abilityDisabled, abilityCooldown,
                "<yellow><ready_action> in <white><cooldown><yellow>.",
                cobwebUnavailable, elytraUnavailable, fireworkUnavailable,
                "<red>You cannot place <white><item><red> under the current MaceGuard block rules.",
                "<red>You cannot break <white><item><red> under the current MaceGuard block rules.",
                "<red>You cannot use <white><item><red> here under the current MaceGuard block rules.",
                stasisBlocked, rotationWarning);
    }

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
