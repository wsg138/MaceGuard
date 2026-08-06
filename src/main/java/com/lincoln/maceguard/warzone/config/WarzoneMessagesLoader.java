package com.lincoln.maceguard.warzone.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

public final class WarzoneMessagesLoader {
    private static final Set<String> KEYS = Set.of(
            "item-disabled", "item-cooldown", "item-cooldown-started",
            "ability-disabled", "ability-cooldown", "ability-cooldown-started",
            "cobweb-unavailable", "elytra-unavailable", "firework-unavailable",
            "block-place-denied", "block-break-denied", "bucket-use-denied",
            "stasis-blocked", "rotation-warning");

    public ValidationResult<WarzoneMessages> load(Path file) {
        ValidationResult<Map<String, Object>> parsed = StrictYaml.load(file);
        if (!parsed.valid()) return ValidationResult.invalid(parsed.errors());
        var errors = new ArrayList<String>();
        parsed.value().keySet().stream().filter(key -> !KEYS.contains(key))
                .forEach(key -> errors.add(key + " is not a supported message key."));
        String disabled = text(parsed.value(), "item-disabled",
                "<red><item> is disabled during <white><meta><red>.", errors);
        String cooldown = text(parsed.value(), "item-cooldown",
                "<red>You must wait <white><cooldown_remaining><red> before <action>.", errors);
        String cooldownStarted = text(parsed.value(), "item-cooldown-started",
                "<yellow><ready_action> in <white><cooldown><yellow>.", errors);
        String abilityDisabled = text(parsed.value(), "ability-disabled",
                "<red><ability> is disabled during <white><meta><red>.", errors);
        String abilityCooldown = text(parsed.value(), "ability-cooldown",
                "<red>You must wait <white><cooldown_remaining><red> before <action>.", errors);
        String abilityCooldownStarted = text(parsed.value(), "ability-cooldown-started",
                "<yellow><ready_action> in <white><cooldown><yellow>.", errors);
        String cobweb = text(parsed.value(), "cobweb-unavailable",
                "<red>Cobwebs are unavailable during <white><meta><red>.", errors);
        String elytra = text(parsed.value(), "elytra-unavailable",
                "<red>You cannot begin gliding while combat-tagged under the current Warzone rules.", errors);
        String firework = text(parsed.value(), "firework-unavailable",
                "<red>You cannot boost with a Firework Rocket while combat-tagged under the current Warzone rules.", errors);
        String blockPlace = text(parsed.value(), "block-place-denied",
                "<red>You cannot place <white><item><red> under the current MaceGuard block rules.", errors);
        String blockBreak = text(parsed.value(), "block-break-denied",
                "<red>You cannot break <white><item><red> under the current MaceGuard block rules.", errors);
        String bucketUse = text(parsed.value(), "bucket-use-denied",
                "<red>You cannot use <white><item><red> here under the current MaceGuard block rules.", errors);
        String stasis = text(parsed.value(), "stasis-blocked",
                "<red>Your Ender Pearl was identified as a stasis pearl and could not teleport you during Warzone combat. If you believe this was an error, contact a staff member.", errors);
        String warning = text(parsed.value(), "rotation-warning",
                "<yellow><meta> changes in <white><time_left><yellow>. Next: <next_meta><yellow>.",
                errors);
        if (!errors.isEmpty()) return ValidationResult.invalid(errors);
        return new ValidationResult<>(new WarzoneMessages(disabled, cooldown, cooldownStarted,
                abilityDisabled, abilityCooldown, abilityCooldownStarted, cobweb, elytra,
                firework, blockPlace, blockBreak, bucketUse, stasis, warning),
                java.util.List.of(), java.util.List.of());
    }

    private String text(Map<String, Object> root, String key, String fallback,
                        java.util.List<String> errors) {
        Object value = root.get(key);
        if (value == null) return fallback;
        if (value instanceof String text && !text.isBlank()) return text;
        errors.add(key + " must be a non-blank string.");
        return fallback;
    }
}
