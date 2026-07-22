package com.lincoln.maceguard.config;

import com.lincoln.maceguard.core.model.EndAccessSettings;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigLoader {
    public static final int VERSION = 7;
    public static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    public static final DateTimeFormatter EST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public MaceGuardConfig load(FileConfiguration config) {
        Set<String> errors = new LinkedHashSet<>();
        if (!config.contains("config-version", true) || config.getInt("config-version", -1) != VERSION) {
            errors.add("config-version must be " + VERSION + "; destructive features are disabled");
        }
        Map<String, ResetProfile> profiles = parseProfiles(config.getConfigurationSection("reset-profiles"), errors);
        var temporary = new MaceGuardConfig.TemporarySettings(
                positive(config, "temporary-blocks.cobweb-ttl-seconds", 60, errors),
                upperSet(config.getStringList("temporary-blocks.allowed-replacement-materials")),
                positive(config, "temporary-blocks.max-tracked-blocks", 10_000, errors));
        var performance = new MaceGuardConfig.PerformanceSettings(
                positive(config, "performance.capture-batch-size", 2_000, errors),
                positive(config, "performance.plan-batch-size", 4_000, errors),
                positive(config, "performance.restore-batch-size", 1_000, errors));
        EndAccessSettings access = new EndAccessSettings(
                config.getBoolean("end_access.manage_eyes", true), config.getBoolean("end_access.persist_auto_enable", true),
                config.getBoolean("end_access.allow_eyes", false), parseEst(config.getString("end_access.eyes_enable_at_est"), errors),
                config.getBoolean("end_access.allow_portals", false), parseEst(config.getString("end_access.portals_enable_at_est"), errors));
        EndIslandSettings island = new EndIslandSettings(config.getBoolean("end_island.enabled", true),
                Math.max(16, config.getInt("end_island.island_radius", 1024)), config.getBoolean("end_island.block_maces", true),
                config.getBoolean("end_island.block_spears", true));
        int durabilityCap = positive(config, "mace-durability.damage-per-armor-piece", 2, errors);
        return new MaceGuardConfig(errors.isEmpty(), config.getBoolean("enabled", true), config.getBoolean("debug", false),
                durabilityCap, temporary, performance,
                Map.copyOf(profiles), access, island, Set.copyOf(errors));
    }

    private Map<String, ResetProfile> parseProfiles(ConfigurationSection root, Set<String> errors) {
        Map<String, ResetProfile> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null || !section.isString("mode")) {
                errors.add("reset-profiles." + name + ".mode is required; profile disabled");
                continue;
            }
            ResetProfile.Mode mode;
            try { mode = ResetProfile.Mode.valueOf(section.getString("mode", "").trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { errors.add("reset-profiles." + name + ".mode is invalid; profile disabled"); continue; }
            int maxChanges = section.getInt("max-total-changes", -1);
            int maxAir = section.getInt("max-air-changes", -1);
            int maxCoordinates = section.getInt("max-coordinates", -1);
            if (maxCoordinates <= 0 || maxChanges < 0 || maxAir < 0 || maxAir > maxChanges || maxChanges > maxCoordinates) {
                errors.add("reset-profiles." + name + " has invalid safety thresholds; profile disabled");
                continue;
            }
            List<String> exclusions = section.getStringList("excluded-region-ids").stream().map(String::trim)
                    .filter(value -> !value.isEmpty()).map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
            result.put(name, new ResetProfile(name, mode, Math.max(0, section.getInt("interval-minutes", 0)), maxCoordinates, maxChanges, maxAir, exclusions));
        }
        return result;
    }

    private int positive(FileConfiguration config, String path, int fallback, Set<String> errors) {
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

    public Instant parseEst(String raw, Set<String> errors) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDateTime.parse(raw.trim(), EST_FORMAT).atZone(EST_ZONE).toInstant(); }
        catch (DateTimeParseException ex) { errors.add("invalid EST date: " + raw); return null; }
    }
}
