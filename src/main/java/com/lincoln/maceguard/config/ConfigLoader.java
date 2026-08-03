package com.lincoln.maceguard.config;

import com.lincoln.maceguard.core.model.EndIslandSettings;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigLoader {
    public static final int VERSION = 8;

    public MaceGuardConfig load(FileConfiguration config) {
        Set<String> errors = new LinkedHashSet<>();
        if (!config.contains("config-version", true)
                || config.getInt("config-version", -1) != VERSION) {
            errors.add("config-version must be " + VERSION + "; destructive features are disabled");
        }

        Map<String, Set<Material>> groups = parseMaterialGroups(
                config.getConfigurationSection("material-groups"), errors);
        Map<String, BlockPolicy> policies = parseBlockPolicies(
                config.getConfigurationSection("block-policies"), errors);
        Map<String, ResetProfile> profiles = parseProfiles(
                config.getConfigurationSection("reset-profiles"), groups, errors);

        var temporary = new MaceGuardConfig.TemporarySettings(
                positive(config, "temporary-blocks.cobweb-ttl-seconds", 60, errors),
                upperSet(config.getStringList("temporary-blocks.allowed-replacement-materials")),
                positive(config, "temporary-blocks.max-tracked-blocks", 10_000, errors));
        var performance = new MaceGuardConfig.PerformanceSettings(
                positive(config, "performance.capture-batch-size", 2_000, errors),
                positive(config, "performance.plan-batch-size", 4_000, errors),
                positive(config, "performance.restore-batch-size", 1_000, errors));
        EndIslandSettings island = new EndIslandSettings(
                config.getBoolean("end_island.enabled", true),
                Math.max(16, config.getInt("end_island.island_radius", 1024)),
                config.getBoolean("end_island.block_maces", true),
                config.getBoolean("end_island.block_spears", true));
        int durabilityCap = positive(config,
                "mace-durability.damage-per-armor-piece", 2, errors);

        return new MaceGuardConfig(errors.isEmpty(), config.getBoolean("enabled", true),
                config.getBoolean("debug", false), durabilityCap, temporary, performance,
                Map.copyOf(policies), Map.copyOf(profiles), island, Set.copyOf(errors));
    }

    private Map<String, Set<Material>> parseMaterialGroups(ConfigurationSection root,
                                                            Set<String> errors) {
        Map<String, Set<Material>> result = new LinkedHashMap<>();
        if (root == null) {
            errors.add("material-groups is required");
            return result;
        }
        for (String name : root.getKeys(false)) {
            String path = "material-groups." + name;
            List<String> raw = root.getStringList(name);
            if (raw.isEmpty()) {
                errors.add(path + " must contain at least one explicit material");
                continue;
            }
            Set<Material> materials = parseMaterials(raw, path, errors);
            if (!materials.isEmpty()) result.put(name.toUpperCase(Locale.ROOT), materials);
        }
        if (!result.containsKey("WATER_FRAGILE"))
            errors.add("material-groups.WATER_FRAGILE is required for filtered restoration");
        return result;
    }

    private Map<String, BlockPolicy> parseBlockPolicies(ConfigurationSection root,
                                                         Set<String> errors) {
        Map<String, BlockPolicy> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String name : root.getKeys(false)) {
            int errorCountBeforePolicy = errors.size();
            String base = "block-policies." + name;
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                errors.add(base + " must be a mapping");
                continue;
            }
            BlockPolicy.MaterialRule place = parseMaterialRule(
                    section.getConfigurationSection("place"), base + ".place", errors);
            BlockPolicy.MaterialRule breakRule = parseMaterialRule(
                    section.getConfigurationSection("break"), base + ".break", errors);
            ConfigurationSection buckets = section.getConfigurationSection("buckets");
            if (buckets == null) errors.add(base + ".buckets is required");
            Set<Material> empty = buckets == null ? Set.of()
                    : parseMaterials(buckets.getStringList("empty"), base + ".buckets.empty", errors);
            Set<Material> fill = buckets == null ? Set.of()
                    : parseMaterials(buckets.getStringList("fill"), base + ".buckets.fill", errors);
            validateFluids(empty, base + ".buckets.empty", errors);
            validateFluids(fill, base + ".buckets.fill", errors);

            ConfigurationSection liquids = section.getConfigurationSection("liquids");
            if (liquids == null) errors.add(base + ".liquids is required");
            boolean confine = liquids != null
                    && liquids.getBoolean("confine-to-region", true);
            boolean infinite = liquids != null
                    && liquids.getBoolean("block-infinite-water-sources", true);
            boolean nonPlayer = section.getBoolean("allow-non-player-sources", false);
            if (errors.size() == errorCountBeforePolicy
                    && place != null && breakRule != null && buckets != null && liquids != null) {
                result.put(name.toLowerCase(Locale.ROOT),
                        new BlockPolicy(name.toLowerCase(Locale.ROOT), place, breakRule,
                                new BlockPolicy.BucketRule(empty, fill),
                                new BlockPolicy.LiquidRule(confine, infinite), nonPlayer));
            }
        }
        return result;
    }

    private BlockPolicy.MaterialRule parseMaterialRule(ConfigurationSection section,
                                                         String path, Set<String> errors) {
        if (section == null) {
            errors.add(path + " is required");
            return null;
        }
        if (!section.contains("deny-unlisted", true))
            errors.add(path + ".deny-unlisted is required");
        boolean deny = section.getBoolean("deny-unlisted", true);
        Set<Material> materials = parseMaterials(section.getStringList("materials"),
                path + ".materials", errors);
        if (materials.isEmpty()) errors.add(path + ".materials must not be empty");
        return new BlockPolicy.MaterialRule(deny, materials);
    }

    private Map<String, ResetProfile> parseProfiles(ConfigurationSection root,
                                                     Map<String, Set<Material>> groups,
                                                     Set<String> errors) {
        Map<String, ResetProfile> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String name : root.getKeys(false)) {
            String base = "reset-profiles." + name;
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null || !section.isString("mode")) {
                errors.add(base + ".mode is required; profile disabled");
                continue;
            }
            ResetProfile.Mode mode;
            try {
                mode = ResetProfile.Mode.valueOf(
                        section.getString("mode", "").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                errors.add(base + ".mode must be FULL_SNAPSHOT or FILTERED_SNAPSHOT");
                continue;
            }
            if (mode == ResetProfile.Mode.SPARSE_ORIGINALS) {
                errors.add(base + ".mode SPARSE_ORIGINALS is obsolete; use FILTERED_SNAPSHOT");
                continue;
            }

            int maxScan = section.getInt(mode == ResetProfile.Mode.FILTERED_SNAPSHOT
                    ? "max-scan-coordinates" : "max-coordinates", -1);
            int maxCaptured = mode == ResetProfile.Mode.FILTERED_SNAPSHOT
                    ? section.getInt("max-captured-coordinates", -1) : maxScan;
            int maxChanges = section.getInt("max-total-changes", -1);
            int maxAir = section.getInt("max-air-changes", -1);
            if (maxScan <= 0)
                errors.add(base + "." + (mode == ResetProfile.Mode.FILTERED_SNAPSHOT
                        ? "max-scan-coordinates" : "max-coordinates") + " must be positive");
            if (maxCaptured <= 0 || maxCaptured > maxScan)
                errors.add(base + ".max-captured-coordinates must be positive and no larger than scan coverage");
            if (maxChanges < 0 || maxAir < 0 || maxAir > maxChanges
                    || maxChanges > maxCaptured)
                errors.add(base + " has invalid change safety thresholds");

            Set<Material> captureMaterials = Set.of();
            Set<Material> restoreWhenCurrent = Set.of();
            ResetProfile.SolidConflictPolicy conflict =
                    ResetProfile.SolidConflictPolicy.SKIP_AND_REPORT;
            if (mode == ResetProfile.Mode.FILTERED_SNAPSHOT) {
                ConfigurationSection capture = section.getConfigurationSection("capture-materials");
                if (capture == null || !capture.isString("group")) {
                    errors.add(base + ".capture-materials.group is required");
                } else {
                    String group = capture.getString("group", "").trim().toUpperCase(Locale.ROOT);
                    captureMaterials = groups.getOrDefault(group, Set.of());
                    if (captureMaterials.isEmpty())
                        errors.add(base + ".capture-materials.group references missing or empty group '"
                                + group + "'");
                }
                restoreWhenCurrent = expandMaterialsAndGroups(
                        section.getStringList("restore-when-current"), groups,
                        base + ".restore-when-current", errors);
                if (restoreWhenCurrent.isEmpty())
                    errors.add(base + ".restore-when-current must not be empty");
                String rawConflict = section.getString("solid-conflict-policy", "");
                if (!rawConflict.equalsIgnoreCase("SKIP_AND_REPORT"))
                    errors.add(base + ".solid-conflict-policy must be SKIP_AND_REPORT");
            }

            List<String> exclusions = section.getStringList("excluded-region-ids").stream()
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
            int interval = Math.max(0, section.getInt("interval-minutes", 0));
            result.put(name, new ResetProfile(name, mode, interval, maxScan, maxCaptured,
                    maxChanges, maxAir, captureMaterials, restoreWhenCurrent, conflict, exclusions));
        }
        return result;
    }

    private Set<Material> expandMaterialsAndGroups(List<String> raw,
                                                    Map<String, Set<Material>> groups,
                                                    String path, Set<String> errors) {
        Set<Material> result = new LinkedHashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            String value = raw.get(index).trim().toUpperCase(Locale.ROOT);
            if (groups.containsKey(value)) {
                result.addAll(groups.get(value));
                continue;
            }
            Material material = strictMaterial(value);
            if (material == null) errors.add(path + "[" + index + "] has invalid material or group '"
                    + raw.get(index) + "'");
            else result.add(material);
        }
        return Set.copyOf(result);
    }

    private Set<Material> parseMaterials(List<String> values, String path, Set<String> errors) {
        Set<Material> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String raw = values.get(index);
            Material material = strictMaterial(raw);
            if (material == null) errors.add(path + "[" + index + "] has invalid material '" + raw + "'");
            else result.add(material);
        }
        return Set.copyOf(result);
    }

    private Material strictMaterial(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.indexOf(':') >= 0 || normalized.indexOf(' ') >= 0)
            return null;
        try { return Material.valueOf(normalized); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private void validateFluids(Set<Material> materials, String path, Set<String> errors) {
        for (Material material : materials)
            if (material != Material.WATER && material != Material.LAVA)
                errors.add(path + " may contain only WATER or LAVA; found " + material);
    }

    private int positive(FileConfiguration config, String path, int fallback,
                         Set<String> errors) {
        int value = config.getInt(path, fallback);
        if (value > 0) return value;
        errors.add(path + " must be positive; safe fallback used");
        return fallback;
    }

    private Set<String> upperSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.trim().toUpperCase(Locale.ROOT)));
        return Set.copyOf(result);
    }
}
