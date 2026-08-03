package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.rotation.ModifierSelector;
import com.lincoln.maceguard.warzone.util.DurationParser;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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
    public static final int VERSION = 4;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    public ValidationResult<WarzoneConfig> load(Path file) {
        ValidationResult<Map<String, Object>> parsed = StrictYaml.load(file);
        if (!parsed.valid()) return ValidationResult.invalid(parsed.errors());
        return load(parsed.value());
    }

    public ValidationResult<WarzoneConfig> load(Map<String, Object> root) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        keys(root, "<root>", Set.of("config-version", "enabled", "region", "rotation", "messages", "cobwebs",
                "restriction-targets", "modifiers", "conflict-groups"), errors);

        int version = integer(root.get("config-version"), "config-version", errors, -1);
        if (version != VERSION) errors.add("config-version must be " + VERSION + ".");
        boolean enabled = bool(root.getOrDefault("enabled", Boolean.TRUE), "enabled", errors, true);

        Map<String, Object> regionRaw = map(root.get("region"), "region", errors);
        keys(regionRaw, "region", Set.of("world", "id", "excluded-region-ids"), errors);
        String world = nonBlank(regionRaw.get("world"), "region.world", errors);
        String regionId = lowerId(regionRaw.get("id"), "region.id", errors);
        List<String> excluded = idList(regionRaw.getOrDefault("excluded-region-ids", List.of()),
                "region.excluded-region-ids", errors);
        if (excluded.contains(regionId)) errors.add("region.excluded-region-ids must not include region.id.");

        Map<String, Object> rotationRaw = map(root.get("rotation"), "rotation", errors);
        keys(rotationRaw, "rotation", Set.of("schedule", "selection", "warning-times"), errors);
        WarzoneConfig.Schedule schedule = parseSchedule(map(rotationRaw.get("schedule"),
                "rotation.schedule", errors), errors);
        WarzoneConfig.Selection selection = parseSelection(map(rotationRaw.get("selection"),
                "rotation.selection", errors), errors);
        List<Duration> warningTimes = durationList(rotationRaw.getOrDefault("warning-times", List.of()),
                "rotation.warning-times", errors);
        if (new LinkedHashSet<>(warningTimes).size() != warningTimes.size())
            errors.add("rotation.warning-times must not contain duplicates.");
        warningTimes.stream().filter(value -> value.isZero() || value.isNegative())
                .forEach(value -> errors.add("rotation.warning-times entries must be positive."));
        warningTimes = warningTimes.stream().distinct().sorted(Comparator.reverseOrder()).toList();

        Map<String, Object> messagesRaw = map(root.getOrDefault("messages", Map.of()), "messages", errors);
        keys(messagesRaw, "messages", Set.of("blocked-message-cooldown", "warning-audience",
                "transition-audience"), errors);
        Duration blocked = duration(messagesRaw.getOrDefault("blocked-message-cooldown", "2s"),
                "messages.blocked-message-cooldown", errors);
        if (blocked.isNegative()) errors.add("messages.blocked-message-cooldown must not be negative.");
        WarzoneConfig.Audience warningAudience = audience(messagesRaw.get("warning-audience"),
                "messages.warning-audience", errors);
        WarzoneConfig.Audience transitionAudience = audience(messagesRaw.get("transition-audience"),
                "messages.transition-audience", errors);

        Map<String, Object> cobwebRaw = map(root.getOrDefault("cobwebs", Map.of()), "cobwebs", errors);
        keys(cobwebRaw, "cobwebs", Set.of("clear-after", "clear-on-meta-change", "clear-on-disable"), errors);
        Duration clearAfter = duration(cobwebRaw.getOrDefault("clear-after", "60s"),
                "cobwebs.clear-after", errors);
        if (clearAfter.isZero() || clearAfter.isNegative()) errors.add("cobwebs.clear-after must be positive.");
        WarzoneConfig.Cobwebs cobwebs = new WarzoneConfig.Cobwebs(clearAfter,
                bool(cobwebRaw.getOrDefault("clear-on-meta-change", Boolean.TRUE),
                        "cobwebs.clear-on-meta-change", errors, true),
                bool(cobwebRaw.getOrDefault("clear-on-disable", Boolean.TRUE),
                        "cobwebs.clear-on-disable", errors, true));

        Map<RestrictionTarget, WarzoneConfig.TargetPolicy> policies =
                parsePolicies(map(root.get("restriction-targets"), "restriction-targets", errors), errors, warnings);
        Map<String, WarzoneConfig.Modifier> modifiers =
                parseModifiers(map(root.get("modifiers"), "modifiers", errors), policies, errors);
        Map<String, Set<String>> conflicts =
                parseConflictGroups(map(root.getOrDefault("conflict-groups", Map.of()),
                        "conflict-groups", errors), modifiers.keySet(), errors);

        if (modifiers.isEmpty()) errors.add("modifiers must contain at least one modifier.");
        if (selection.maximum() > modifiers.size())
            errors.add("rotation.selection.maximum must not exceed the number of configured modifiers.");

        WarzoneConfig config = new WarzoneConfig(VERSION, enabled,
                new WarzoneConfig.Region(world, regionId, excluded), schedule, selection, warningTimes,
                new WarzoneConfig.Messages(blocked, warningAudience, transitionAudience), cobwebs,
                policies, modifiers, conflicts);
        if (errors.isEmpty()) {
            try {
                if (new ModifierSelector(new java.util.Random(0L)).validCombinations(config).isEmpty())
                    errors.add("No valid modifier combination satisfies selection limits and conflict groups.");
            } catch (RuntimeException ex) {
                errors.add("Modifier selection is invalid: " + ex.getMessage());
            }
        }
        return errors.isEmpty()
                ? new ValidationResult<>(config, List.of(), List.copyOf(warnings))
                : new ValidationResult<>(null, List.copyOf(errors), List.copyOf(warnings));
    }

    private WarzoneConfig.Schedule parseSchedule(Map<String, Object> raw, List<String> errors) {
        keys(raw, "rotation.schedule", Set.of("day", "time", "timezone"), errors);
        DayOfWeek day = DayOfWeek.SUNDAY;
        Object dayRaw = raw.get("day");
        if (dayRaw instanceof String text) {
            try { day = DayOfWeek.valueOf(text.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { errors.add("rotation.schedule.day is not a valid weekday."); }
        } else errors.add("rotation.schedule.day must be a weekday name.");
        LocalTime time = LocalTime.of(4, 0);
        Object timeRaw = raw.get("time");
        if (timeRaw instanceof String text) {
            try { time = LocalTime.parse(text.trim()); }
            catch (DateTimeParseException ex) { errors.add("rotation.schedule.time must use HH:mm or HH:mm:ss."); }
        } else errors.add("rotation.schedule.time must be a quoted local time.");
        ZoneId zone = ZoneId.of("UTC");
        Object zoneRaw = raw.get("timezone");
        if (zoneRaw instanceof String text) {
            try { zone = ZoneId.of(text.trim()); }
            catch (RuntimeException ex) { errors.add("rotation.schedule.timezone is not a valid IANA timezone."); }
        } else errors.add("rotation.schedule.timezone must be an IANA timezone.");
        return new WarzoneConfig.Schedule(day, time, zone);
    }

    private WarzoneConfig.Selection parseSelection(Map<String, Object> raw, List<String> errors) {
        keys(raw, "rotation.selection", Set.of("mode", "minimum", "maximum", "prevent-identical-repeat"), errors);
        WarzoneConfig.Selection.Mode mode = WarzoneConfig.Selection.Mode.RANDOM_MODIFIERS;
        Object modeRaw = raw.get("mode");
        if (!(modeRaw instanceof String text) || !text.trim().equalsIgnoreCase("RANDOM_MODIFIERS"))
            errors.add("rotation.selection.mode must be RANDOM_MODIFIERS.");
        int minimum = integer(raw.get("minimum"), "rotation.selection.minimum", errors, 1);
        int maximum = integer(raw.get("maximum"), "rotation.selection.maximum", errors, 1);
        if (minimum < 1) errors.add("rotation.selection.minimum must be at least 1.");
        if (maximum < minimum) errors.add("rotation.selection.maximum must be at least minimum.");
        if (maximum > 16) errors.add("rotation.selection.maximum must not exceed 16.");
        boolean prevent = bool(raw.getOrDefault("prevent-identical-repeat", Boolean.TRUE),
                "rotation.selection.prevent-identical-repeat", errors, true);
        return new WarzoneConfig.Selection(mode, minimum, maximum, prevent);
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
            Map<String, Object> section = map(entry.getValue(), path, errors);
            keys(section, path, Set.of("can-disable", "can-cooldown", "maximum-cooldown"), errors);
            boolean canDisable = bool(section.getOrDefault("can-disable", Boolean.FALSE),
                    path + ".can-disable", errors, false);
            boolean canCooldown = bool(section.getOrDefault("can-cooldown", Boolean.FALSE),
                    path + ".can-cooldown", errors, false);
            Duration maximum = section.containsKey("maximum-cooldown")
                    ? duration(section.get("maximum-cooldown"), path + ".maximum-cooldown", errors) : null;
            if (canCooldown && !target.supportsCooldown())
                errors.add(path + ".can-cooldown is unsafe because this target has no reliable success event.");
            if (canCooldown && (maximum == null || maximum.isZero() || maximum.isNegative()))
                errors.add(path + ".maximum-cooldown must be positive when can-cooldown is true.");
            if (!canCooldown && maximum != null)
                errors.add(path + ".maximum-cooldown is only valid when can-cooldown is true.");
            if (!canDisable && !canCooldown) warnings.add(path + " permits no restriction modes.");
            result.put(target, new WarzoneConfig.TargetPolicy(canDisable, canCooldown, maximum));
        }
        return Map.copyOf(result);
    }

    private Map<String, WarzoneConfig.Modifier> parseModifiers(
            Map<String, Object> raw, Map<RestrictionTarget, WarzoneConfig.TargetPolicy> policies,
            List<String> errors) {
        Map<String, WarzoneConfig.Modifier> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            String path = "modifiers." + id;
            if (!ID.matcher(id).matches()) errors.add(path + " has an invalid ID.");
            Map<String, Object> section = map(entry.getValue(), path, errors);
            keys(section, path, Set.of("display-name", "description", "effects", "restrictions",
                    "start-message", "end-message", "warning-message"), errors);
            String display = nonBlank(section.get("display-name"), path + ".display-name", errors);
            String description = nonBlank(section.get("description"), path + ".description", errors);
            Set<WarzoneConfig.Effect> effects = effectSet(section.getOrDefault("effects", List.of()),
                    path + ".effects", errors);
            Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions = parseRestrictions(
                    map(section.getOrDefault("restrictions", Map.of()), path + ".restrictions", errors),
                    path + ".restrictions", policies, errors);
            String start = optionalString(section.get("start-message"), path + ".start-message", errors);
            String end = optionalString(section.get("end-message"), path + ".end-message", errors);
            String warning = optionalString(section.get("warning-message"), path + ".warning-message", errors);
            if (effects.isEmpty() && restrictions.isEmpty())
                errors.add(path + " must define at least one effect or restriction.");
            result.put(id, new WarzoneConfig.Modifier(id, display, description, effects,
                    restrictions, start, end, warning));
        }
        return Map.copyOf(result);
    }

    private Map<RestrictionTarget, WarzoneConfig.Restriction> parseRestrictions(
            Map<String, Object> raw, String basePath,
            Map<RestrictionTarget, WarzoneConfig.TargetPolicy> policies, List<String> errors) {
        Map<RestrictionTarget, WarzoneConfig.Restriction> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String path = basePath + "." + entry.getKey();
            RestrictionTarget target = RestrictionTarget.parse(entry.getKey()).orElse(null);
            if (target == null) {
                errors.add(path + " is not a Bukkit material or supported special target.");
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
                errors.add(path + " requests DISABLED but the target policy disallows it.");
            if (policy != null && mode == RestrictionMode.COOLDOWN && !policy.canCooldown())
                errors.add(path + " requests COOLDOWN but the target policy disallows it.");
            if (policy != null && mode == RestrictionMode.COOLDOWN && cooldown != null
                    && policy.maximumCooldown() != null && cooldown.compareTo(policy.maximumCooldown()) > 0)
                errors.add(path + ".cooldown exceeds its configured maximum.");
            if (mode != null) result.put(target, new WarzoneConfig.Restriction(target, mode, cooldown));
        }
        return Map.copyOf(result);
    }

    private Map<String, Set<String>> parseConflictGroups(Map<String, Object> raw, Set<String> modifiers,
                                                          List<String> errors) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String path = "conflict-groups." + entry.getKey();
            if (!ID.matcher(entry.getKey()).matches()) errors.add(path + " has an invalid group ID.");
            List<String> values = idList(entry.getValue(), path, errors);
            if (values.size() < 2) errors.add(path + " must contain at least two modifier IDs.");
            for (String value : values)
                if (!modifiers.contains(value)) errors.add(path + " references unknown modifier '" + value + "'.");
            result.put(entry.getKey(), Set.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private Set<WarzoneConfig.Effect> effectSet(Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> values)) {
            errors.add(path + " must be a list.");
            return Set.of();
        }
        Set<WarzoneConfig.Effect> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            Object item = values.get(index);
            if (!(item instanceof String text)) {
                errors.add(path + "[" + index + "] must be an effect name.");
                continue;
            }
            try { result.add(WarzoneConfig.Effect.valueOf(text.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ex) { errors.add(path + "[" + index + "] is not a supported effect."); }
        }
        return Set.copyOf(result);
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
        } catch (RuntimeException ex) {
            errors.add(path + " is invalid: " + ex.getMessage() + ".");
            return Duration.ZERO;
        }
    }

    private List<String> idList(Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> values)) {
            errors.add(path + " must be a list.");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Object raw = values.get(index);
            if (!(raw instanceof String text) || text.isBlank()) {
                errors.add(path + "[" + index + "] must be a non-blank string.");
                continue;
            }
            String id = text.trim().toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) errors.add(path + "[" + index + "] has an invalid ID.");
            if (!result.contains(id)) result.add(id);
        }
        return List.copyOf(result);
    }

    private String lowerId(Object value, String path, List<String> errors) {
        String text = nonBlank(value, path, errors).toLowerCase(Locale.ROOT);
        if (!ID.matcher(text).matches()) errors.add(path + " has an invalid ID.");
        return text;
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
