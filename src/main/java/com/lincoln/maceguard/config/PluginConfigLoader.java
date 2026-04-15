package com.lincoln.maceguard.config;

import com.lincoln.maceguard.core.model.CuboidRegion;
import com.lincoln.maceguard.core.model.EndAccessSettings;
import com.lincoln.maceguard.core.model.EndIslandExplosiveSettings;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.MaceDurabilityRule;
import com.lincoln.maceguard.core.model.ProtectedRegion;
import com.lincoln.maceguard.core.model.ResetMode;
import com.lincoln.maceguard.core.model.ResetScope;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class PluginConfigLoader {
    public static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    public static final DateTimeFormatter EST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Logger logger;

    public PluginConfigLoader(Logger logger) {
        this.logger = logger;
    }

    public PluginSettings load(FileConfiguration config) {
        boolean enabled = config.getBoolean("enabled", true);
        boolean debug = config.getBoolean("debug", false);

        Set<String> allowedWorlds = new LinkedHashSet<>();
        for (String world : config.getStringList("allowed_worlds")) {
            if (world != null && !world.isBlank()) {
                allowedWorlds.add(world.trim());
            }
        }

        List<ProtectedRegion> protectedRegions = new ArrayList<>();
        protectedRegions.addAll(parseProtectedRegions(config.getMapList("zones"), "zones"));
        protectedRegions.addAll(parseProtectedRegions(config.getMapList("protected_regions"), "protected_regions"));

        List<GameplayZone> gameplayZones = new ArrayList<>();
        for (Map<?, ?> rawZone : config.getMapList("gameplay_zones")) {
            GameplayZone zone = parseGameplayZone(rawZone);
            if (zone != null) {
                gameplayZones.add(zone);
            }
        }

        EndAccessSettings endAccess = new EndAccessSettings(
                config.getBoolean("end_access.allow_eyes", false),
                parseEstInstant(config.getString("end_access.eyes_enable_at_est", "")),
                config.getBoolean("end_access.allow_portals", false),
                parseEstInstant(config.getString("end_access.portals_enable_at_est", ""))
        );

        EndIslandSettings endIsland = new EndIslandSettings(
                config.getBoolean("end_island.enabled", true),
                Math.max(16, config.getInt("end_island.island_radius", 1024)),
                config.getBoolean("end_island.block_maces", true),
                config.getBoolean("end_island.fun_bed_sleep", true),
                new EndIslandExplosiveSettings(
                        config.getDouble("end_island.explosives.tnt.power_percent", 0.0D),
                        config.getDouble("end_island.explosives.tnt_minecart.power_percent", 0.0D),
                        config.getDouble("end_island.explosives.respawn_anchor.power_percent", 0.0D),
                        config.getDouble("end_island.explosives.bed.power_percent", 0.0D)
                )
        );

        return new PluginSettings(enabled, debug, Set.copyOf(allowedWorlds), List.copyOf(protectedRegions), List.copyOf(gameplayZones), endAccess, endIsland);
    }

    public Instant parseEstInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), EST_FORMAT).atZone(EST_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            logger.warning("Failed to parse EST time \"" + raw + "\". Expected yyyy-MM-dd HH:mm.");
            return null;
        }
    }

    private List<ProtectedRegion> parseProtectedRegions(List<Map<?, ?>> entries, String path) {
        List<ProtectedRegion> protectedRegions = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            CuboidRegion region = parseRegion(entry);
            if (region == null) {
                logger.warning("Skipping invalid protected region under " + path + ".");
                continue;
            }
            protectedRegions.add(new ProtectedRegion(region.name(), region));
        }
        return protectedRegions;
    }

    @SuppressWarnings("unchecked")
    private GameplayZone parseGameplayZone(Map<?, ?> rawZone) {
        CuboidRegion region = parseRegion(rawZone);
        if (region == null) {
            return null;
        }

        boolean allowAllPlace = bool(rawZone.get("allow_all_place"), false);
        boolean allowAllBreak = bool(rawZone.get("allow_all_break"), false);
        Set<String> allowedPlace = materialNameSet(rawZone.get("allowed_place"));
        Set<String> denyPlace = materialNameSet(rawZone.get("deny_place"));
        boolean confineLiquids = bool(rawZone.get("confine_liquids"), false);
        boolean blockInfiniteSources = bool(rawZone.get("block_infinite_sources"), false);
        int ttlSeconds = Math.max(0, integer(rawZone.get("ttl_seconds"), 0));
        int fullResetMinutes = Math.max(0, integer(rawZone.get("full_reset_minutes"), 0));
        ResetMode resetMode = enumValue(rawZone.get("reset_mode"), ResetMode.AIR);
        ResetScope resetScope = enumValue(rawZone.get("reset_scope"), ResetScope.CHANGED);
        List<Integer> warnBeforeSeconds = integerList(rawZone.get("warn_before_seconds"));
        warnBeforeSeconds.sort(Comparator.reverseOrder());
        int priority = integer(rawZone.get("priority"), 0);

        MaceDurabilityRule maceDurabilityRule = MaceDurabilityRule.DISABLED;
        Object maceRaw = rawZone.get("mace_armor_durability");
        if (maceRaw instanceof Map<?, ?> maceMap) {
            boolean enabled = bool(maceMap.get("enabled"), false);
            int damagePerArmorPiece = Math.max(0, integer(maceMap.get("damage_per_armor_piece"), 2));
            maceDurabilityRule = new MaceDurabilityRule(enabled, damagePerArmorPiece);
        }

        return new GameplayZone(
                region.name(),
                region,
                priority,
                allowAllPlace,
                allowAllBreak,
                Set.copyOf(allowedPlace),
                Set.copyOf(denyPlace),
                confineLiquids,
                blockInfiniteSources,
                ttlSeconds,
                fullResetMinutes,
                resetMode,
                resetScope,
                List.copyOf(warnBeforeSeconds),
                maceDurabilityRule
        );
    }

    private CuboidRegion parseRegion(Map<?, ?> rawRegion) {
        String name = string(rawRegion.get("name"), "unnamed");
        String world = string(rawRegion.get("world"), "world");
        Map<String, Object> min = asStringObjectMap(rawRegion.get("min"));
        Map<String, Object> max = asStringObjectMap(rawRegion.get("max"));
        if (min == null || max == null) {
            return null;
        }
        return CuboidRegion.of(
                name,
                world,
                integer(min.get("x"), 0),
                integer(min.get("y"), 0),
                integer(min.get("z"), 0),
                integer(max.get("x"), 0),
                integer(max.get("y"), 0),
                integer(max.get("z"), 0)
        );
    }

    private Set<String> materialNameSet(Object raw) {
        Set<String> values = new HashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry == null) {
                    continue;
                }
                values.add(String.valueOf(entry).trim().toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    private List<Integer> integerList(Object raw) {
        List<Integer> values = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object entry : collection) {
                values.add(Math.max(0, integer(entry, 0)));
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return (Map<String, Object>) map.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)
        );
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private <E extends Enum<E>> E enumValue(Object value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
