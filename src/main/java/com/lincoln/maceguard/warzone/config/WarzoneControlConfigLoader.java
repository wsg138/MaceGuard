package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.rotation.ModifierSelector;
import com.lincoln.maceguard.warzone.rotation.RepeatingSchedule;
import org.bukkit.Material;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict schema-7 loader. Gameplay parsing remains delegated to the proven schema-5 parser. */
public final class WarzoneControlConfigLoader {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    public ValidationResult<WarzoneControlConfig> load(Path file) {
        ValidationResult<Map<String, Object>> parsed = StrictYaml.load(file);
        if (!parsed.valid()) return ValidationResult.invalid(parsed.errors());
        return load(parsed.value());
    }

    public ValidationResult<WarzoneControlConfig> load(Map<String, Object> root) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        keys(root, "<root>", Set.of("config-version", "enabled", "region", "rotation", "messages",
                "combat", "cobwebs", "restriction-targets", "modifiers", "conflict-groups", "kits", "gui"), errors);
        int version = integer(root.get("config-version"), "config-version", errors, -1);
        if (version != WarzoneControlConfig.VERSION)
            errors.add("config-version must be " + WarzoneControlConfig.VERSION + ".");
        requireCombatSection(root, errors);

        Map<String, Object> rotation = map(root.get("rotation"), "rotation", errors);
        keys(rotation, "rotation", Set.of("schedule", "selection", "special-rules", "warning-times"), errors);
        WarzoneControlConfig.Schedule schedule = parseSchedule(
                map(rotation.get("schedule"), "rotation.schedule", errors), errors);

        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("config-version", WarzoneConfigLoader.VERSION);
        copy(root, legacy, "enabled");
        copy(root, legacy, "region");
        Map<String, Object> legacyRotation = new LinkedHashMap<>();
        Map<String, Object> legacySchedule = new LinkedHashMap<>();
        legacySchedule.put("day", schedule.anchorDate().getDayOfWeek().name());
        legacySchedule.put("time", schedule.time().toString());
        legacySchedule.put("timezone", schedule.timezone().getId());
        legacyRotation.put("schedule", legacySchedule);
        copy(rotation, legacyRotation, "selection");
        copy(rotation, legacyRotation, "special-rules");
        copy(rotation, legacyRotation, "warning-times");
        legacy.put("rotation", legacyRotation);
        copy(root, legacy, "messages");
        copy(root, legacy, "combat");
        copy(root, legacy, "cobwebs");
        copy(root, legacy, "restriction-targets");
        copy(root, legacy, "modifiers");
        copy(root, legacy, "conflict-groups");

        ValidationResult<WarzoneConfig> gameplayResult = new WarzoneConfigLoader().load(legacy);
        errors.addAll(gameplayResult.errors());
        warnings.addAll(gameplayResult.warnings());
        WarzoneConfig gameplay = gameplayResult.value();

        Map<String, WarzoneControlConfig.Kit> kits = parseKits(
                map(root.getOrDefault("kits", Map.of()), "kits", errors), errors);
        WarzoneControlConfig.Gui gui = parseGui(
                map(root.getOrDefault("gui", Map.of()), "gui", errors), errors);

        if (gameplay != null) {
            validateKits(gameplay, kits, errors);
            validateSchedule(gameplay, kits, schedule, errors);
        }
        if (!errors.isEmpty()) return new ValidationResult<>(null, List.copyOf(errors), List.copyOf(warnings));
        return new ValidationResult<>(new WarzoneControlConfig(version, gameplay, kits, schedule, gui),
                List.of(), List.copyOf(warnings));
    }

    private void requireCombatSection(Map<String, Object> root, List<String> errors) {
        Object combatValue = root.get("combat");
        if (!(combatValue instanceof Map<?, ?> combat)) {
            errors.add("combat must be a section in schema 7.");
            return;
        }
        Object stasisValue = combat.get("stasis");
        if (!(stasisValue instanceof Map<?, ?> stasis)) {
            errors.add("combat.stasis must be a section in schema 7.");
            return;
        }
        if (!stasis.containsKey("minimum-age"))
            errors.add("combat.stasis.minimum-age is required in schema 7.");
    }

    private WarzoneControlConfig.Schedule parseSchedule(Map<String, Object> raw, List<String> errors) {
        keys(raw, "rotation.schedule", Set.of("enabled", "timezone", "anchor-date", "time",
                "cadence", "cycle"), errors);
        boolean enabled = bool(raw.getOrDefault("enabled", Boolean.TRUE),
                "rotation.schedule.enabled", errors, true);
        ZoneId zone = zone(raw.get("timezone"), "rotation.schedule.timezone", errors);
        LocalDate anchor = date(raw.get("anchor-date"), "rotation.schedule.anchor-date", errors);
        LocalTime time = time(raw.get("time"), "rotation.schedule.time", errors);
        Map<String, Object> cadenceRaw = map(raw.get("cadence"), "rotation.schedule.cadence", errors);
        keys(cadenceRaw, "rotation.schedule.cadence", Set.of("every", "unit"), errors);
        int every = integer(cadenceRaw.get("every"), "rotation.schedule.cadence.every", errors, 1);
        if (every < 1) errors.add("rotation.schedule.cadence.every must be positive.");
        WarzoneControlConfig.CadenceUnit unit = enumValue(cadenceRaw.get("unit"),
                WarzoneControlConfig.CadenceUnit.class, "rotation.schedule.cadence.unit", errors,
                WarzoneControlConfig.CadenceUnit.WEEKS);
        List<WarzoneControlConfig.Entry> cycle = parseCycle(raw.get("cycle"), errors);
        if (enabled && cycle.isEmpty()) errors.add("rotation.schedule.cycle must not be empty when enabled.");
        return new WarzoneControlConfig.Schedule(enabled, zone, anchor, time,
                new WarzoneControlConfig.Cadence(every, unit), cycle);
    }

    private List<WarzoneControlConfig.Entry> parseCycle(Object value, List<String> errors) {
        if (!(value instanceof List<?> raw)) {
            errors.add("rotation.schedule.cycle must be a list.");
            return List.of();
        }
        List<WarzoneControlConfig.Entry> result = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            String path = "rotation.schedule.cycle[" + index + "]";
            Map<String, Object> entry = map(raw.get(index), path, errors);
            keys(entry, path, Set.of("type", "kit", "modifiers"), errors);
            WarzoneControlConfig.EntryType type = enumValue(entry.get("type"),
                    WarzoneControlConfig.EntryType.class, path + ".type", errors,
                    WarzoneControlConfig.EntryType.NONE);
            String kit = entry.containsKey("kit") ? lowerId(entry.get("kit"), path + ".kit", errors) : null;
            List<String> modifiers = entry.containsKey("modifiers")
                    ? idList(entry.get("modifiers"), path + ".modifiers", errors) : List.of();
            switch (type) {
                case RANDOM, NONE -> {
                    if (kit != null || !modifiers.isEmpty())
                        errors.add(path + " type " + type + " must not define kit or modifiers.");
                }
                case KIT -> {
                    if (kit == null || kit.isBlank()) errors.add(path + ".kit is required for KIT.");
                    if (!modifiers.isEmpty()) errors.add(path + " cannot combine a kit with modifiers.");
                }
                case MODIFIERS -> {
                    if (kit != null) errors.add(path + " cannot combine modifiers with a kit.");
                    if (modifiers.isEmpty()) errors.add(path + ".modifiers must not be empty; use NONE.");
                }
            }
            result.add(new WarzoneControlConfig.Entry(type, kit, modifiers));
        }
        return List.copyOf(result);
    }

    private Map<String, WarzoneControlConfig.Kit> parseKits(Map<String, Object> raw,
                                                             List<String> errors) {
        Map<String, WarzoneControlConfig.Kit> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
            String path = "kits." + id;
            if (!ID.matcher(id).matches()) errors.add(path + " has an invalid kit ID.");
            Map<String, Object> section = map(entry.getValue(), path, errors);
            keys(section, path, Set.of("enabled", "display-name", "description", "icon", "modifiers"), errors);
            boolean enabled = bool(section.getOrDefault("enabled", Boolean.TRUE), path + ".enabled", errors, true);
            String display = nonBlank(section.get("display-name"), path + ".display-name", errors);
            String description = nonBlank(section.get("description"), path + ".description", errors);
            Material icon = material(section.get("icon"), path + ".icon", errors);
            List<String> modifiers = idList(section.get("modifiers"), path + ".modifiers", errors);
            if (modifiers.isEmpty()) errors.add(path + ".modifiers must not be empty.");
            if (result.putIfAbsent(id, new WarzoneControlConfig.Kit(id, enabled, display,
                    description, icon, modifiers)) != null) errors.add(path + " is duplicated.");
        }
        return Map.copyOf(result);
    }

    private WarzoneControlConfig.Gui parseGui(Map<String, Object> raw, List<String> errors) {
        keys(raw, "gui", Set.of("show-disabled-kits", "session-timeout-seconds"), errors);
        boolean showDisabled = bool(raw.getOrDefault("show-disabled-kits", Boolean.FALSE),
                "gui.show-disabled-kits", errors, false);
        int timeout = integer(raw.getOrDefault("session-timeout-seconds", 60),
                "gui.session-timeout-seconds", errors, 60);
        if (timeout < 10 || timeout > 600)
            errors.add("gui.session-timeout-seconds must be from 10 through 600.");
        return new WarzoneControlConfig.Gui(showDisabled, timeout);
    }

    private void validateKits(WarzoneConfig gameplay, Map<String, WarzoneControlConfig.Kit> kits,
                              List<String> errors) {
        ModifierSelector selector = new ModifierSelector(new java.util.Random(0));
        for (WarzoneControlConfig.Kit kit : kits.values()) {
            String path = "kits." + kit.id();
            Set<String> unique = new LinkedHashSet<>(kit.modifierIds());
            if (unique.size() != kit.modifierIds().size())
                errors.add(path + ".modifiers contains a duplicate modifier.");
            if (!kit.enabled()) continue;
            try { selector.composeExact(gameplay, kit.modifierIds()); }
            catch (IllegalArgumentException ex) { errors.add(path + " is invalid: " + ex.getMessage()); }
        }
    }

    private void validateSchedule(WarzoneConfig gameplay, Map<String, WarzoneControlConfig.Kit> kits,
                                  WarzoneControlConfig.Schedule schedule, List<String> errors) {
        ModifierSelector selector = new ModifierSelector(new java.util.Random(0));
        boolean random = false;
        for (int index = 0; index < schedule.cycle().size(); index++) {
            WarzoneControlConfig.Entry entry = schedule.cycle().get(index);
            String path = "rotation.schedule.cycle[" + index + "]";
            switch (entry.type()) {
                case RANDOM -> random = true;
                case KIT -> {
                    WarzoneControlConfig.Kit kit = kits.get(entry.kitId());
                    if (kit == null) errors.add(path + " references unknown kit '" + entry.kitId() + "'.");
                    else if (!kit.enabled()) errors.add(path + " references disabled kit '" + entry.kitId() + "'.");
                }
                case MODIFIERS -> {
                    if (new LinkedHashSet<>(entry.modifierIds()).size() != entry.modifierIds().size())
                        errors.add(path + ".modifiers contains a duplicate modifier.");
                    try { selector.composeExact(gameplay, entry.modifierIds()); }
                    catch (IllegalArgumentException ex) { errors.add(path + " is invalid: " + ex.getMessage()); }
                }
                case NONE -> { }
            }
        }
        if (random) {
            try {
                if (selector.selectableCombinations(gameplay).isEmpty())
                    errors.add("RANDOM appears in the cycle, but no valid random selection exists.");
            } catch (RuntimeException ex) {
                errors.add("RANDOM appears in the cycle, but selection is invalid: " + ex.getMessage());
            }
        }
        if (!schedule.cycle().isEmpty()) {
            try {
                RepeatingSchedule repeating = new RepeatingSchedule(schedule);
                RepeatingSchedule.Slot current = repeating.slotAt(Instant.now());
                repeating.slot(Math.addExact(current.index(), 1));
            } catch (RuntimeException ex) {
                errors.add("rotation.schedule cannot calculate the current and next slot: "
                        + ex.getMessage());
            }
        }
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static void keys(Map<String, Object> raw, String path, Set<String> allowed,
                             List<String> errors) {
        for (String key : raw.keySet())
            if (!allowed.contains(key)) errors.add(path + " contains unknown key '" + key + "'.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String path, List<String> errors) {
        if (value instanceof Map<?, ?> raw) return (Map<String, Object>) raw;
        errors.add(path + " must be a mapping.");
        return Map.of();
    }

    private static boolean bool(Object value, String path, List<String> errors, boolean fallback) {
        if (value instanceof Boolean result) return result;
        errors.add(path + " must be true or false.");
        return fallback;
    }

    private static int integer(Object value, String path, List<String> errors, int fallback) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric == Math.rint(numeric)
                    && numeric >= Integer.MIN_VALUE && numeric <= Integer.MAX_VALUE)
                return number.intValue();
        }
        errors.add(path + " must be a 32-bit integer.");
        return fallback;
    }

    private static String nonBlank(Object value, String path, List<String> errors) {
        if (value instanceof String text && !text.isBlank()) return text;
        errors.add(path + " must be a non-blank string.");
        return "";
    }

    private static String lowerId(Object value, String path, List<String> errors) {
        String result = nonBlank(value, path, errors).trim().toLowerCase(Locale.ROOT);
        if (!result.isEmpty() && !ID.matcher(result).matches()) errors.add(path + " is not a valid ID.");
        return result;
    }

    private static List<String> idList(Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> raw)) {
            errors.add(path + " must be a list of modifier IDs.");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++)
            result.add(lowerId(raw.get(index), path + "[" + index + "]", errors));
        return List.copyOf(result);
    }

    private static ZoneId zone(Object value, String path, List<String> errors) {
        try { return ZoneId.of(nonBlank(value, path, errors).trim()); }
        catch (RuntimeException ex) { errors.add(path + " must be a valid IANA timezone."); return ZoneId.of("UTC"); }
    }

    private static LocalDate date(Object value, String path, List<String> errors) {
        if (value instanceof LocalDate parsed) return parsed;
        if (value instanceof java.util.Date parsed)
            return parsed.toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
        if (value != null) {
            try { return LocalDate.parse(value.toString().trim()); }
            catch (DateTimeParseException ignored) { }
        }
        errors.add(path + " must use YYYY-MM-DD.");
        return LocalDate.of(1970, 1, 1);
    }

    private static LocalTime time(Object value, String path, List<String> errors) {
        try { return LocalTime.parse(nonBlank(value, path, errors).trim()); }
        catch (DateTimeParseException ex) { errors.add(path + " must use HH:mm or HH:mm:ss."); return LocalTime.MIDNIGHT; }
    }

    private static Material material(Object value, String path, List<String> errors) {
        try { return Material.valueOf(nonBlank(value, path, errors).trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { errors.add(path + " is not a valid Bukkit material."); return Material.BARRIER; }
    }

    private static <E extends Enum<E>> E enumValue(Object value, Class<E> type, String path,
                                                    List<String> errors, E fallback) {
        try { return Enum.valueOf(type, nonBlank(value, path, errors).trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { errors.add(path + " is not one of " + List.of(type.getEnumConstants()) + "."); return fallback; }
    }
}
