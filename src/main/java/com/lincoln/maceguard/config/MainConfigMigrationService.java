package com.lincoln.maceguard.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

public final class MainConfigMigrationService {
    private static final List<String> SAFE_PATHS = List.of(
            "enabled",
            "debug",
            "mace-durability.damage-per-armor-piece",
            "temporary-blocks.cobweb-ttl-seconds",
            "temporary-blocks.allowed-replacement-materials",
            "temporary-blocks.max-tracked-blocks",
            "performance.capture-batch-size",
            "performance.plan-batch-size",
            "performance.restore-batch-size",
            "end_island.enabled",
            "end_island.island_radius",
            "end_island.block_maces",
            "end_island.block_spears"
    );

    private final JavaPlugin plugin;

    public MainConfigMigrationService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void prepare() {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        if (!Files.isRegularFile(config)) return;
        try {
            YamlConfiguration old = new YamlConfiguration();
            old.load(config.toFile());
            int version = old.getInt("config-version", -1);
            if (version == ConfigLoader.VERSION) return;

            Path backups = plugin.getDataFolder().toPath().resolve("migration-backups");
            Files.createDirectories(backups);
            Path backup = backups.resolve("config-v" + version + "-"
                    + System.currentTimeMillis() + ".yml.bak");
            Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);

            plugin.saveResource("config.yml", true);
            YamlConfiguration clean = new YamlConfiguration();
            clean.load(config.toFile());
            for (String path : SAFE_PATHS)
                if (old.contains(path, true)) clean.set(path, old.get(path));
            clean.set("config-version", ConfigLoader.VERSION);
            clean.save(config.toFile());

            Path reports = plugin.getDataFolder().toPath().resolve("migration-reports");
            Files.createDirectories(reports);
            Path report = reports.resolve("main-config-" + System.currentTimeMillis() + ".txt");
            Files.writeString(report, String.join(System.lineSeparator(),
                    "MaceGuard main configuration migration review",
                    "Generated: " + Instant.now(),
                    "Old schema: " + version,
                    "New schema: " + ConfigLoader.VERSION,
                    "Backup: " + backup.toAbsolutePath(),
                    "Preserved supported End-island restrictions and non-legacy runtime tuning.",
                    "Obsolete coordinate zones, gameplay_zones, zones, old reset modes, duel footprint controls, End scheduling, explosion percentages, backstop scanning, placement lists, and weekly reset fields were not copied.",
                    "No WorldGuard regions were created or modified.",
                    "No snapshots were captured.",
                    "No reset profile was armed.",
                    "No reset schedule was enabled automatically.",
                    ""
            ), StandardCharsets.UTF_8);
            plugin.getLogger().warning("Migrated config.yml to schema "
                    + ConfigLoader.VERSION + "; review " + report.getFileName() + ".");
        } catch (IOException | InvalidConfigurationException ex) {
            String message = "Could not migrate config.yml safely; the existing file "
                    + "was left for review: " + ex.getMessage();
            plugin.getLogger().severe(message);
            throw new IllegalStateException(message, ex);
        }
    }
}
