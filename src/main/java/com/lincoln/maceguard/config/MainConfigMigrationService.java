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
    private static final String COBWEB_POLICY = "block-policies.cobweb-box";
    private static final List<String> STOCK_COBWEB_MATERIALS = List.of("COBWEB", "ICE");
    private static final List<String> STOCK_WATER_ONLY = List.of("WATER");
    private static final List<String> STOCK_WATER_AND_LAVA = List.of("WATER", "LAVA");

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
            if (version == ConfigLoader.VERSION) {
                if (!upgradeStockSchema8BucketDefaults(old)) return;
                Path backup = backup(config, version, "stock-defaults");
                old.save(config.toFile());
                plugin.getLogger().warning("Updated the stock schema-8 cobweb-box bucket defaults "
                        + "to allow WATER and LAVA; backup: " + backup.getFileName() + ".");
                return;
            }

            Path backup = backup(config, version, null);

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

    /**
     * Schema 8 predates the bundled Lava bucket allowance but is otherwise still the live schema.
     * Upgrade only the exact previous bundled cobweb-box policy. A server that customized any part
     * of that policy is intentionally left untouched rather than silently broadening its rules.
     */
    static boolean upgradeStockSchema8BucketDefaults(YamlConfiguration config) {
        if (config.getInt("config-version", -1) != ConfigLoader.VERSION) return false;
        if (!config.getBoolean(COBWEB_POLICY + ".place.deny-unlisted", false)
                || !STOCK_COBWEB_MATERIALS.equals(
                        config.getStringList(COBWEB_POLICY + ".place.materials"))) return false;
        if (!config.getBoolean(COBWEB_POLICY + ".break.deny-unlisted", false)
                || !STOCK_COBWEB_MATERIALS.equals(
                        config.getStringList(COBWEB_POLICY + ".break.materials"))) return false;
        if (!STOCK_WATER_ONLY.equals(config.getStringList(COBWEB_POLICY + ".buckets.empty"))
                || !STOCK_WATER_ONLY.equals(
                        config.getStringList(COBWEB_POLICY + ".buckets.fill"))) return false;
        if (!config.getBoolean(COBWEB_POLICY + ".liquids.confine-to-region", false)
                || !config.getBoolean(
                        COBWEB_POLICY + ".liquids.block-infinite-water-sources", false)
                || config.getBoolean(COBWEB_POLICY + ".allow-non-player-sources", true))
            return false;

        config.set(COBWEB_POLICY + ".buckets.empty", STOCK_WATER_AND_LAVA);
        config.set(COBWEB_POLICY + ".buckets.fill", STOCK_WATER_AND_LAVA);
        return true;
    }

    private Path backup(Path config, int version, String suffix) throws IOException {
        Path backups = plugin.getDataFolder().toPath().resolve("migration-backups");
        Files.createDirectories(backups);
        String label = suffix == null ? "" : "-" + suffix;
        Path backup = backups.resolve("config-v" + version + label + "-"
                + System.currentTimeMillis() + ".yml.bak");
        Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }
}
