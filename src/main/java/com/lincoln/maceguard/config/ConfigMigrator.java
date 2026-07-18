package com.lincoln.maceguard.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConfigMigrator {
    public static final int CURRENT_CONFIG_VERSION = 6;
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JavaPlugin plugin;
    private final Logger logger;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void migrateBundledConfig(String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults(resourceName);
        if (defaults == null) {
            return;
        }

        int existingVersion = config.getInt("config-version", 1);
        List<String> addedPaths = new ArrayList<>();
        addMissingPath(config, defaults, "config-version", addedPaths);
        addKnownMigrationPaths(config, defaults, addedPaths);
        for (String path : flattenedPaths(defaults)) {
            addMissingPath(config, defaults, path, addedPaths);
        }
        if (existingVersion < CURRENT_CONFIG_VERSION) {
            config.set("config-version", CURRENT_CONFIG_VERSION);
            addedPaths.add("config-version: " + existingVersion + " -> " + CURRENT_CONFIG_VERSION);
        }

        if (addedPaths.isEmpty()) {
            return;
        }

        File backup = backup(file);
        if (backup == null) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.warning("Skipping migration for " + resourceName + " because a backup could not be created.");
            }
            return;
        }
        try {
            config.save(file);
            if (logger.isLoggable(Level.INFO)) {
                logger.info("Migrated " + resourceName + " from config-version " + existingVersion + " to " + CURRENT_CONFIG_VERSION + ".");
                logger.info("Config backup saved to " + backup.getName() + ".");
                logger.info("Added/migrated config paths: " + String.join(", ", addedPaths));
            }
        } catch (IOException ex) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.warning("Failed to save migrated " + resourceName + ": " + ex.getMessage());
                logger.warning("Backup remains available at " + backup.getAbsolutePath() + ".");
            }
        }
    }

    private void addKnownMigrationPaths(YamlConfiguration config, YamlConfiguration defaults, List<String> addedPaths) {
        addMissingPath(config, defaults, "end_island.block_spears", Boolean.TRUE, addedPaths);
        addMissingGameplayZonePath(config, "war-pit", "suppress_snapshot_drops", Boolean.TRUE, addedPaths);
        addMissingGameplayZonePath(config, "warzone", "allowed_break", List.of(), addedPaths);
        addMissingGameplayZonePath(config, "warzone", "allow_break_replaceable", Boolean.TRUE, addedPaths);
        addMissingGameplayZonePath(config, "warzone", "cobweb_policy", "WARZONE_ROTATOR", addedPaths);
        addMissingGameplayZonePath(config, "warzone", "reset_mode", "SPARSE_SNAPSHOT", addedPaths);
        addMissingGameplayZonePath(config, "warzone", "weekly_reset", Map.of("enabled", true, "day", "SUNDAY", "time", "04:00", "timezone", "America/Indiana/Indianapolis"), addedPaths);
    }

    @SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.AvoidInstantiatingObjectsInLoops"})
    private void addMissingGameplayZonePath(YamlConfiguration config, String zoneName, String key, Object value, List<String> addedPaths) {
        List<Map<?, ?>> rawZones = config.getMapList("gameplay_zones");
        if (rawZones.isEmpty()) {
            return;
        }

        boolean changed = false;
        List<Map<String, Object>> migratedZones = new ArrayList<>(rawZones.size());
        for (Map<?, ?> rawZone : rawZones) {
            Map<String, Object> zone = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawZone.entrySet()) {
                zone.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            if (zoneName.equalsIgnoreCase(String.valueOf(zone.get("name"))) && !zone.containsKey(key)) {
                zone.put(key, value);
                changed = true;
                addedPaths.add("gameplay_zones." + zoneName + "." + key);
            }
            migratedZones.add(zone);
        }

        if (changed) {
            config.set("gameplay_zones", migratedZones);
        }
    }

    private void addMissingPath(YamlConfiguration config, YamlConfiguration defaults, String path, List<String> addedPaths) {
        addMissingPath(config, defaults, path, null, addedPaths);
    }

    private void addMissingPath(YamlConfiguration config, YamlConfiguration defaults, String path, Object fallback, List<String> addedPaths) {
        if (config.contains(path, true) || defaults.isConfigurationSection(path)) {
            return;
        }
        Object value = defaults.get(path);
        if (value == null) {
            value = fallback;
        }
        if (value == null) {
            return;
        }
        config.set(path, value);
        addedPaths.add(path);
    }

    private YamlConfiguration loadDefaults(String resourceName) {
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input == null) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.warning("Cannot migrate " + resourceName + " because bundled defaults were not found.");
                }
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.warning("Cannot read bundled defaults for " + resourceName + ": " + ex.getMessage());
            }
            return null;
        }
    }

    private List<String> flattenedPaths(ConfigurationSection section) {
        List<String> paths = new ArrayList<>();
        collectPaths(section, "", paths);
        return paths;
    }

    private void collectPaths(ConfigurationSection section, String prefix, List<String> paths) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collectPaths(section.getConfigurationSection(key), path, paths);
            } else {
                paths.add(path);
            }
        }
    }

    private File backup(File file) {
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        if (!backupDirectory.mkdirs() && !backupDirectory.isDirectory()) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.warning("Failed to create config backup directory: " + backupDirectory.getAbsolutePath());
            }
            return null;
        }
        String backupStamp = LocalDateTime.now().format(BACKUP_FORMAT).replace(':', '-');
        File backup = new File(backupDirectory, file.getName() + "." + backupStamp + ".bak");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException ex) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.warning("Failed to back up " + file.getName() + " before migration: " + ex.getMessage());
            }
            return null;
        }
    }
}
