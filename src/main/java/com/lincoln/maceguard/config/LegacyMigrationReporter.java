package com.lincoln.maceguard.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Produces review material only. It never rewrites config or WorldGuard state. */
public final class LegacyMigrationReporter {
    private final JavaPlugin plugin;

    public LegacyMigrationReporter(JavaPlugin plugin) { this.plugin = plugin; }

    public void inspect() {
        File source = new File(plugin.getDataFolder(), "config.yml");
        if (!source.isFile()) return;
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(source);
        if (legacy.getInt("config-version", -1) == ConfigLoader.VERSION && !legacy.contains("gameplay_zones") && !legacy.contains("zones")) return;
        File migration = new File(plugin.getDataFolder(), "migration");
        if (!migration.mkdirs() && !migration.isDirectory()) {
            plugin.getLogger().severe("Cannot create migration directory; legacy configuration remains untouched and resets are disabled.");
            return;
        }
        try {
            File backup = new File(migration, "legacy-config.yml.bak");
            if (!backup.exists()) Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            File report = new File(migration, "legacy-migration-report.txt");
            Files.writeString(report.toPath(), report(legacy), StandardCharsets.UTF_8);
            plugin.getLogger().warning("Legacy configuration detected. No geography, flags, schedules, or reset modes were migrated. Review " + report.getAbsolutePath());
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not create legacy migration backup/report; resets are disabled: " + ex.getMessage());
        }
    }

    private String report(YamlConfiguration legacy) {
        List<String> lines = new ArrayList<>();
        lines.add("MaceGuard legacy migration proposal");
        lines.add("Created: " + Instant.now());
        lines.add("");
        lines.add("No changes were applied. Verify region geometry in WorldGuard before using these suggestions.");
        lines.add("Old snapshots and sparse baselines are untrusted and will not be loaded.");
        lines.add("");
        for (Map<?, ?> raw : legacy.getMapList("gameplay_zones")) {
            Object rawName = raw.get("name");
            String id = rawName == null ? "unnamed" : String.valueOf(rawName);
            lines.add("Legacy zone: " + id);
            lines.add("  Review/create the WorldGuard region manually, then choose only applicable flags:");
            Object durability = raw.get("mace_armor_durability");
            if (durability instanceof Map<?, ?> map && Boolean.parseBoolean(String.valueOf(map.get("enabled"))))
                lines.add("  /rg flag " + id + " maceguard-mace-durability allow");
            if (raw.containsKey("cobweb_policy")) lines.add("  /rg flag " + id + " maceguard-cobwebs allow");
            Object mode = raw.get("reset_mode");
            if (mode != null && !"AIR".equalsIgnoreCase(String.valueOf(mode)))
                lines.add("  # After creating and reviewing a reset profile: /rg flag " + id + " maceguard-reset-profile <profile>");
            lines.add("");
        }
        lines.add("Production checklist: install WorldGuard; verify each region/world/priority/parent; add flags manually; install reviewed config v7; capture; validate; preflight; arm; preflight again.");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }
}
