package com.lincoln.maceguard.warzone.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

public final class WarzoneMessagesLoader {
    private static final Set<String> KEYS = Set.of(
            "item-disabled", "item-cooldown", "ability-disabled",
            "ability-cooldown", "cobweb-unavailable", "elytra-unavailable",
            "firework-unavailable", "stasis-blocked", "rotation-warning");

    public ValidationResult<WarzoneMessages> load(Path file) {
        ValidationResult<Map<String, Object>> parsed = StrictYaml.load(file);
        if (!parsed.valid()) return ValidationResult.invalid(parsed.errors());
        var errors = new ArrayList<String>();
        parsed.value().keySet().stream().filter(key -> !KEYS.contains(key))
                .forEach(key -> errors.add(key + " is not a supported message key."));
        String disabled = text(parsed.value(), "item-disabled",
                "<red><item> is disabled during <white><meta><red>.", errors);
        String cooldown = text(parsed.value(), "item-cooldown",
                "<red><item> is on cooldown for <white><cooldown_remaining><red>.", errors);
        String abilityDisabled = text(parsed.value(), "ability-disabled",
                "<red><ability> is disabled during <white><meta><red>.", errors);
        String abilityCooldown = text(parsed.value(), "ability-cooldown",
                "<red><ability> is on cooldown for <white><cooldown_remaining><red>.", errors);
        String cobweb = text(parsed.value(), "cobweb-unavailable",
                "<red>Cobwebs are unavailable during <white><meta><red>.", errors);
        String elytra = text(parsed.value(), "elytra-unavailable",
                "<red>Elytra gliding is not active in the warzone this week.", errors);
        String firework = text(parsed.value(), "firework-unavailable",
                "<red>Firework boosting is disabled in the warzone this week.", errors);
        String stasis = text(parsed.value(), "stasis-blocked",
                "<red>Your Ender Pearl was identified as a stasis pearl and could not teleport you during Warzone combat. If you believe this was an error, contact a staff member.", errors);
        String warning = text(parsed.value(), "rotation-warning",
                "<yellow><meta> changes in <white><time_left><yellow>. Next: <next_meta><yellow>.",
                errors);
        if (!errors.isEmpty()) return ValidationResult.invalid(errors);
        return new ValidationResult<>(new WarzoneMessages(disabled, cooldown,
                abilityDisabled, abilityCooldown, cobweb, elytra, firework, stasis, warning),
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
