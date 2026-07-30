package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.util.DurationParser;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class WarzoneConfigLoader {
    public static final int VERSION = 3;
    private static final Pattern ROTATION_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    public ValidationResult<WarzoneConfig> load(Path file) {
        ValidationResult<Map<String, Object>> parsed = StrictYaml.load(file);
        if (!parsed.valid()) return ValidationResult.invalid(parsed.errors());
        return load(parsed.value());
    }

    public ValidationResult<WarzoneConfig> load(Map<String, Object> root) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        keys(root, "<root>", Set.of("config-version", "enabled", "region", "rotation", "messages", "cobwebs",
                "restriction-targets", "rotations"), errors);
        int version = integer(root.get("config-version"), "config-version", errors, -1);
        if (version != VERSION) errors.add("config-version must be " + VERSION + ".");
        boolean enabled = bool(root.getOrDefault("enabled", Boolean.TRUE), "enabled", errors, true);

        Map<String, Object> regionRaw = map(root.get("region"), "region", errors);
        keys(regionRaw, "region", Set.of("world", "id"), errors);
        String world = nonBlank(regionRaw.get("world"), "region.world", errors);
        String regionId = nonBlank(regionRaw.get("id"), "region.id", errors);

        Map<String, Object> rotationRaw = map(root.get("rotation"), "rotation", errors);
        keys(rotationRaw, "rotation", Set.of("warning-times"), errors);
        List<Duration> warningTimes = durationList(rotationRaw.getOrDefault("warning-times", List.of()),
                "rotation.warning-times", errors);
        if (new LinkedHashSet<>(warningTimes).size() != warningTimes.size())
            errors.add("rotation.warning-times must not contain duplicates.");
        warningTimes.stream().filter(value -> value.isZero() || value.isNegative())
                .forEach(value -> errors.add("rotation.warning-times entries must be positive."));
        warningTimes = warningTimes.stream().distinct().sorted(Comparator.reverseOrder()).toList();

        Map<String, Object> messagesRaw = map(root.getOrDefault("messages", Map.of()), "messages", errors);
        keys(messagesRaw, "messages", Set.of("blocked-message-cooldown", "warning-audience", "transition-audience"), errors);
        Duration blocked = duration(messagesRaw.getOrDefault("blocked-message-cooldown", "2s"),
                "messages.blocked-message-cooldown", errors);
        if (blocked.isNegative()) errors.add("messages.blocked-message-cooldown must not be negative.");
        WarzoneConfig.Audience warningAudience = audience(messagesRaw.get("warning-audience"),
                "messages.warning-audience", errors);
        WarzoneConfig.Audience transitionAudience = audience(messagesRaw.get("transition-audience"),
                "messages.transition-audience", errors);

        Map<String, Object> cobwebRaw = map(root.getOrDefault("cobwebs", Map.of()), "cobwebs", errors);
        keys(cobwebRaw, "cobwebs", Set.of("clear-after", "clear-on-meta-change", "clear-on-disable"), errors);
        Duration clearAfter = duration(cobwebRaw.getOrDefault("clear-after", "60s"), "cobwebs.clear-after", errors);
        if (clearAfter.isZero() || clearAfter.isNegative()) errors.add("cobwebs.clear-after must be positive.");
        WarzoneConfig.Cobwebs cobwebs = new WarzoneConfig.Cobwebs(clearAfter,
                bool(cobwebRaw.getOrDefault("clear-on-meta-change", Boolean.TRUE), "cobwebs.clear-on-meta-change", errors, true),
                bool(cobwebRaw.getOrDefault("clear-on-disable", Boolean.TRUE), "cobwebs.clear-on-disable", errors, true));

        Map<RestrictionTarget, WarzoneConfig.TargetPolicy> policies =
                parsePolicies(map(root.get("restriction-targets"), "restriction-targets", errors), errors, warnings);
        List<WarzoneConfig.Rotation> rotations =
                parseRotations(map(root.get("rotations"), "rotations", errors), policies, errors);
        if (rotations.isEmpty()) errors.add("rotations must contain at least one rotation.");
        for (WarzoneConfig.Rotation rotation : rotations) {
            warningTimes.stream().filter(value -> value.compareTo(rotation.duration()) >= 0)
                    .forEach(value -> warnings.add("rotation.warning-times entry " + value.getSeconds()
                            + "s is not used by rotations." + rotation.id() + " because it is not shorter than its duration."));
        }

        if (!errors.isEmpty()) return new ValidationResult<>(null, List.copyOf(errors), List.copyOf(warnings));
        WarzoneConfig config = new WarzoneConfig(VERSION, enabled, new WarzoneConfig.Region(world, regionId),
                warningTimes, new WarzoneConfig.Messages(blocked, warningAudience, transitionAudience), cobwebs,
                Map.copyOf(policies), List.copyOf(rotations));
        return new ValidationResult<>(config, List.of(), List.copyOf(warnings));
    }

    private Map<RestrictionTarget, WarzoneConfig.TargetPolicy> parsePolicies(
            Map<String, Object> raw, List<String> errors, List<String> warnings) {
        Map<RestrictionTarget, WarzoneConfig.TargetPolicy> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String path = "restriction-targets." + entry.getKey();
            RestrictionTarget target = RestrictionTarget.parse(entry.getKey()).orElse(null);
            if (target == null) {
                errors.add(path + " is not a Bukkit material or supported special target.");
                continue;
            }
            if (result.containsKey(target)) {
                errors.add(path + " duplicates normalized target " + target.id() + ".");
                continue;
            }
            Map<String, Object> section = map(entry.getValue(), path, errors);
            keys(section, path, Set.of("can-disable", "can-cooldown", "maximum-cooldown"), errors);
            boolean canDisable = bool(section.getOrDefault("can-disable", Boolean.FALSE), path + ".can-disable", errors, false);
            boolean canCooldown = bool(section.getOrDefault("can-cooldown", Boolean.FALSE), path + ".can-cooldown", errors, false);
            Duration maximum = section.containsKey("maximum-cooldown")
                    ? duration(section.get("maximum-cooldown"), path + ".maximum-cooldown", errors) : null;
            if (canCooldown && (maximum == null || maximum.isZero() || maximum.isNegative()))
                errors.add(path + ".maximum-cooldown must be positive when can-cooldown is true.");
            if (!canCooldown && maximum != null)
                errors.add(path + ".maximum-cooldown is only valid when can-cooldown is true.");
            if (!canDisable && !canCooldown) warnings.add(path + " permits no restriction modes.");
            result.put(target, new WarzoneConfig.TargetPolicy(canDisable, canCooldown, maximum));
        }
        return result;
    }

    private List<WarzoneConfig.Rotation> parseRotations(
            Map<String, Object> raw, Map<RestrictionTarget, WarzoneConfig.TargetPolicy> policies, List<String> errors) {
        List<WarzoneConfig.Rotation> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            String path = "rotations." + id;
            if (!ROTATION_ID.matcher(id).matches())
                errors.add(path + " has an invalid ID; use lowercase letters, numbers, '_' or '-'.");
            Map<String, Object> section = map(entry.getValue(), path, errors);
            keys(section, path, Set.of("display-name", "description", "duration", "cobwebs-allowed",
                    "restrictions", "start-message", "end-message", "warning-message"), errors);
            String display = nonBlank(section.get("display-name"), path + ".display-name", errors);
            String description = nonBlank(section.get("description"), path + ".description", errors);
            Duration duration = duration(section.get("duration"), path + ".duration", errors);
            if (duration.isZero() || duration.isNegative()) errors.add(path + ".duration must be positive.");
            boolean cobwebs = bool(section.getOrDefault("cobwebs-allowed", Boolean.FALSE),
                    path + ".cobwebs-allowed", errors, false);
            String start = nonBlank(section.get("start-message"), path + ".start-message", errors);
            String end = optionalString(section.get("end-message"), path + ".end-message", errors);
            String warning = optionalString(section.get("warning-message"), path + ".warning-message", errors);
            Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions = parseRestrictions(
                    map(section.getOrDefault("restrictions", Map.of()), path + ".restrictions", errors),
                    path + ".restrictions", policies, errors);
            if (restrictions.containsKey(RestrictionTarget.SPEAR)
                    && restrictions.keySet().stream().anyMatch(target -> target.kind() == RestrictionTarget.Kind.MATERIAL
                    && RestrictionTarget.isSpear(target.material())))
                errors.add(path + ".restrictions must not combine SPEAR with a specific spear material.");
            result.add(new WarzoneConfig.Rotation(id, display, description, duration, cobwebs,
                    Map.copyOf(restrictions), start, end, warning));
        }
        return result;
    }

    private Map<RestrictionTarget, WarzoneConfig.Restriction> parseRestrictions(
            Map<String, Object> raw, String basePath, Map<RestrictionTarget, WarzoneConfig.TargetPolicy> policies,
            List<String> errors) {
        Map<RestrictionTarget, WarzoneConfig.Restriction> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String path = basePath + "." + entry.getKey();
            RestrictionTarget target = RestrictionTarget.parse(entry.getKey()).orElse(null);
            if (target == null) {
                errors.add(path + " is not a Bukkit material or supported special target.");
                continue;
            }
            if (result.containsKey(target)) {
                errors.add(path + " duplicates normalized target " + target.id() + ".");
                continue;
            }
            WarzoneConfig.TargetPolicy policy = policies.get(target);
            if (policy == null) errors.add(path + " is not declared in restriction-targets.");
            Map<String, Object> section = map(entry.getValue(), path, errors);
            keys(section, path, Set.of("mode", "cooldown"), errors);
            RestrictionMode mode = mode(section.get("mode"), path + ".mode", errors);
            Duration cooldown = section.containsKey("cooldown")
                    ? duration(section.get("cooldown"), path + ".cooldown", errors) : null;
            if (mode == RestrictionMode.DISABLED && cooldown != null)
                errors.add(path + ".cooldown is only valid with mode COOLDOWN.");
            if (mode == RestrictionMode.COOLDOWN && (cooldown == null || cooldown.isZero() || cooldown.isNegative()))
                errors.add(path + ".cooldown must be positive with mode COOLDOWN.");
            if (policy != null && mode == RestrictionMode.DISABLED && !policy.canDisable())
                errors.add(path + " requests DISABLED but restriction-targets." + target.id() + ".can-disable is false.");
            if (policy != null && mode == RestrictionMode.COOLDOWN && !policy.canCooldown())
                errors.add(path + " requests COOLDOWN but restriction-targets." + target.id() + ".can-cooldown is false.");
            if (policy != null && mode == RestrictionMode.COOLDOWN && cooldown != null
                    && policy.maximumCooldown() != null && cooldown.compareTo(policy.maximumCooldown()) > 0)
                errors.add(path + ".cooldown exceeds restriction-targets." + target.id() + ".maximum-cooldown.");
            if (mode != null) result.put(target, new WarzoneConfig.Restriction(target, mode, cooldown));
        }
        return result;
    }

    private RestrictionMode mode(Object value, String path, List<String> errors) {
        if (!(value instanceof String text)) {
            errors.add(path + " must be DISABLED or COOLDOWN.");
            return null;
        }
        try { return RestrictionMode.valueOf(text.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            errors.add(path + " must be DISABLED or COOLDOWN.");
            return null;
        }
    }

    private WarzoneConfig.Audience audience(Object value, String path, List<String> errors) {
        if (value == null) return WarzoneConfig.Audience.GLOBAL;
        if (!(value instanceof String text)) {
            errors.add(path + " must be 'global' or 'warzone'.");
            return WarzoneConfig.Audience.GLOBAL;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "global" -> WarzoneConfig.Audience.GLOBAL;
            case "warzone" -> WarzoneConfig.Audience.WARZONE;
            default -> {
                errors.add(path + " must be 'global' or 'warzone'.");
                yield WarzoneConfig.Audience.GLOBAL;
            }
        };
    }

    private List<Duration> durationList(Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> values)) {
            errors.add(path + " must be a list.");
            return List.of();
        }
        List<Duration> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++)
            result.add(duration(values.get(index), path + "[" + index + "]", errors));
        return result;
    }

    private Duration duration(Object value, String path, List<String> errors) {
        try {
            Duration parsed = DurationParser.parse(value);
            parsed.toMillis();
            return parsed;
        }
        catch (RuntimeException ex) {
            errors.add(path + " is invalid: " + ex.getMessage() + ".");
            return Duration.ZERO;
        }
    }

    private Map<String, Object> map(Object value, String path, List<String> errors) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    errors.add(path + " contains a non-string key.");
                    continue;
                }
                result.put(key, entry.getValue());
            }
            return result;
        }
        errors.add(path + " must be a mapping.");
        return Map.of();
    }

    private void keys(Map<String, Object> values, String path, Set<String> allowed, List<String> errors) {
        values.keySet().stream().filter(key -> !allowed.contains(key))
                .forEach(key -> errors.add((path.equals("<root>") ? "" : path + ".") + key + " is not supported."));
    }

    private String nonBlank(Object value, String path, List<String> errors) {
        if (!(value instanceof String text) || text.isBlank()) {
            errors.add(path + " must be a non-blank string.");
            return "";
        }
        return text;
    }

    private String optionalString(Object value, String path, List<String> errors) {
        if (value == null) return null;
        if (!(value instanceof String text)) {
            errors.add(path + " must be a string.");
            return null;
        }
        return text;
    }

    private boolean bool(Object value, String path, List<String> errors, boolean fallback) {
        if (value instanceof Boolean result) return result;
        errors.add(path + " must be true or false.");
        return fallback;
    }

    private int integer(Object value, String path, List<String> errors, int fallback) {
        if (value instanceof Number number) return number.intValue();
        errors.add(path + " must be an integer.");
        return fallback;
    }
}
