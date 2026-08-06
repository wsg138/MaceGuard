package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LegacyWarzoneConverter {
    public ValidationResult<String> convert(java.nio.file.Path file) {
        ValidationResult<Map<String, Object>> parsed = StrictYaml.load(file);
        if (!parsed.valid()) return ValidationResult.invalid(parsed.errors());
        return convert(parsed.value());
    }

    public ValidationResult<String> convert(Map<String, Object> oldRoot) {
        List<String> errors = new ArrayList<>();
        Set<String> allowed = Set.of("config-version", "enabled", "target-region", "region", "rotation",
                "messages", "cobwebs", "rotations");
        oldRoot.keySet().stream().filter(key -> !allowed.contains(key))
                .forEach(key -> errors.add(key + " is unsupported by the legacy converter."));
        int version = oldRoot.get("config-version") instanceof Number number ? number.intValue() : 0;
        if (version < 1 || version > 2) errors.add("Legacy config-version must be 1 or 2.");

        Map<String, Object> region = map(oldRoot.get("region"));
        String world = string(region.getOrDefault("world", "world"));
        String id = string(region.getOrDefault("id", oldRoot.getOrDefault("target-region", "warzone")));
        if (world.isBlank()) errors.add("region.world must not be blank.");
        if (id.isBlank()) errors.add("region.id/target-region must not be blank.");

        Map<String, Object> rotations = map(oldRoot.get("rotations"));
        if (rotations.isEmpty()) errors.add("rotations must contain at least one rotation.");
        Set<RestrictionTarget> targets = new LinkedHashSet<>();
        Map<String, Map<String, Object>> convertedRotations = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rotations.entrySet()) {
            String path = "rotations." + entry.getKey();
            Map<String, Object> oldRotation = map(entry.getValue());
            Set<String> rotationAllowed = Set.of("display-name", "description", "duration", "cobwebs-allowed",
                    "disabled-items", "start-message", "message", "end-message", "warning-message");
            oldRotation.keySet().stream().filter(key -> !rotationAllowed.contains(key))
                    .forEach(key -> errors.add(path + "." + key + " is unsupported by the legacy converter."));
            Object disabledValue = oldRotation.getOrDefault("disabled-items", List.of());
            if (!(disabledValue instanceof List<?> disabled)) {
                errors.add(path + ".disabled-items must be a list.");
                continue;
            }
            Map<String, Object> restrictions = new LinkedHashMap<>();
            for (Object raw : disabled) {
                RestrictionTarget target = RestrictionTarget.parse(string(raw)).orElse(null);
                if (target == null) {
                    errors.add(path + ".disabled-items contains invalid target '" + raw + "'.");
                    continue;
                }
                targets.add(target);
                restrictions.put(target.id(), Map.of("mode", "DISABLED"));
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            copy(oldRotation, converted, "display-name", entry.getKey());
            copy(oldRotation, converted, "description", "Legacy rotation " + entry.getKey());
            copy(oldRotation, converted, "duration", null);
            copy(oldRotation, converted, "cobwebs-allowed", false);
            converted.put("restrictions", restrictions);
            Object start = oldRotation.containsKey("start-message") ? oldRotation.get("start-message")
                    : oldRotation.getOrDefault("message", "<yellow>The warzone meta changed.");
            converted.put("start-message", start);
            if (oldRotation.containsKey("end-message")) converted.put("end-message", oldRotation.get("end-message"));
            if (oldRotation.containsKey("warning-message")) converted.put("warning-message", oldRotation.get("warning-message"));
            convertedRotations.put(entry.getKey(), converted);
        }
        if (!errors.isEmpty()) return ValidationResult.invalid(errors);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", WarzoneConfigLoader.VERSION);
        yaml.set("enabled", oldRoot.getOrDefault("enabled", true));
        yaml.set("region.world", world);
        yaml.set("region.id", id);
        Map<String, Object> oldRotationSettings = map(oldRoot.get("rotation"));
        copySection(yaml, "rotation", oldRotationSettings);
        if (!oldRotationSettings.containsKey("warning-times")) yaml.set("rotation.warning-times", List.of());
        Map<String, Object> oldMessages = map(oldRoot.get("messages"));
        copySection(yaml, "messages", oldMessages);
        if (!oldMessages.containsKey("blocked-message-cooldown")) yaml.set("messages.blocked-message-cooldown", "1s");
        if (!oldMessages.containsKey("warning-audience")) yaml.set("messages.warning-audience", "global");
        if (!oldMessages.containsKey("transition-audience")) yaml.set("messages.transition-audience", "global");
        Map<String, Object> oldCobwebs = map(oldRoot.get("cobwebs"));
        copySection(yaml, "cobwebs", oldCobwebs);
        if (!oldCobwebs.containsKey("clear-after")) yaml.set("cobwebs.clear-after", "60s");
        if (!oldCobwebs.containsKey("clear-on-meta-change")) yaml.set("cobwebs.clear-on-meta-change", true);
        if (!oldCobwebs.containsKey("clear-on-disable")) yaml.set("cobwebs.clear-on-disable", true);
        for (RestrictionTarget target : targets) {
            yaml.set("restriction-targets." + target.id() + ".can-disable", true);
            yaml.set("restriction-targets." + target.id() + ".can-cooldown", false);
        }
        for (Map.Entry<String, Map<String, Object>> rotation : convertedRotations.entrySet())
            rotation.getValue().forEach((key, value) -> yaml.set("rotations." + rotation.getKey() + "." + key, value));
        String output = yaml.saveToString();
        ValidationResult<Map<String, Object>> reparsed = StrictYaml.loadText(output);
        if (!reparsed.valid()) return ValidationResult.invalid(reparsed.errors());
        ValidationResult<WarzoneConfig> validation = new WarzoneConfigLoader().load(reparsed.value());
        if (!validation.valid()) return ValidationResult.invalid(validation.errors());
        return new ValidationResult<>(output, List.of(),
                List.of("Converted legacy disabled-items entries to mode: DISABLED."));
    }

    private void copySection(YamlConfiguration yaml, String path, Map<String, Object> values) {
        values.forEach((key, value) -> yaml.set(path + "." + key, value));
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key, Object fallback) {
        Object value = source.getOrDefault(key, fallback);
        if (value != null) target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) return (Map<String, Object>) raw;
        return Map.of();
    }

    private String string(Object value) { return value == null ? "" : value.toString().trim(); }
}
