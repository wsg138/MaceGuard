package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.config.ValidationResult;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneConfigLoader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class WarzoneMigrationService {
    private static final int SCHEMA_FOUR = 4;
    private static final Set<String> SAFE_MODIFIER_FIELDS = Set.of(
            "display-name", "description", "effects", "restrictions",
            "start-message", "end-message", "warning-message");

    private final JavaPlugin plugin;
    private final Path dataFolder;
    private final Path legacyFolder;

    public WarzoneMigrationService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder().toPath();
        Path pluginsFolder = dataFolder.getParent();
        this.legacyFolder = pluginsFolder == null ? Path.of("plugins", "WarzoneRotator")
                : pluginsFolder.resolve("WarzoneRotator");
    }

    public boolean prepare() {
        List<String> report = new ArrayList<>();
        report.add("MaceGuard weekly warzone migration review");
        report.add("Generated: " + Instant.now());
        report.add("No WorldGuard regions, snapshots, arming state, or reset schedules were created or enabled.");

        try {
            prepareWarzoneConfig(report);
            prepareMessages(report);
            preserveLegacyState(report);
            if (Files.isDirectory(legacyFolder)) {
                report.add("Standalone WarzoneRotator directory preserved unchanged for rollback: "
                        + legacyFolder.toAbsolutePath());
                report.add("Standalone sequential rotations were not imported into the weekly random system.");
            }
            writeReport(report);
            return true;
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe("Warzone migration preparation failed safely: " + ex.getMessage());
            return false;
        }
    }

    private void prepareWarzoneConfig(List<String> report)
            throws IOException, InvalidConfigurationException {
        Path config = dataFolder.resolve("warzone.yml");
        if (!Files.isRegularFile(config)) {
            plugin.saveResource("warzone.yml", false);
            report.add("Created clean schema-" + WarzoneConfigLoader.VERSION + " warzone.yml.");
            return;
        }
        YamlConfiguration old = new YamlConfiguration();
        old.load(config.toFile());
        int version = old.getInt("config-version", -1);
        if (version == WarzoneConfigLoader.VERSION) {
            report.add("Existing warzone.yml already uses schema " + version + "; no rewrite.");
            return;
        }

        Path backup = timestampedBackup(config, "warzone-v" + version);
        if (version == SCHEMA_FOUR) {
            YamlConfiguration migrated = migrateSchema4(old, bundledDefaults());
            saveValidatedAtomically(migrated, config);
            report.add("Backed up schema-4 warzone.yml to " + backup.getFileName() + ".");
            report.add("Migrated to schema-" + WarzoneConfigLoader.VERSION
                    + " while preserving enabled state, scope IDs, schedule, messages, cobweb settings, "
                    + "restriction policies, built-in modifiers, valid custom modifiers, and conflict groups.");
            report.add("Custom schema-4 modifiers receive enabled: true and weight: 10 only when those fields "
                    + "were absent; invalid custom definitions abort migration without replacing the original file.");
            report.add("Added count weights, Pearl/Wind outcomes, and Elytra special-rule defaults.");
            report.add("Existing weekly state was retained; disabled or invalid persisted IDs reroll "
                    + "without moving their stored transition boundary.");
            return;
        }

        plugin.saveResource("warzone.yml", true);
        report.add("Backed up incompatible warzone.yml to " + backup.getFileName() + ".");
        report.add("Installed clean schema-" + WarzoneConfigLoader.VERSION + " weekly configuration.");
        report.add("The replacement remains disabled by default; old short sequential rotations were not reinterpreted.");
    }

    static YamlConfiguration migrateSchema4(YamlConfiguration old,
                                             YamlConfiguration migrated) {
        migrated.set("config-version", WarzoneConfigLoader.VERSION);
        migrated.set("enabled", old.getBoolean("enabled", false));
        copyPath(old, migrated, "region.world");
        copyPath(old, migrated, "region.id");
        copyPath(old, migrated, "region.excluded-region-ids");
        copyPath(old, migrated, "rotation.schedule");
        copyPath(old, migrated, "rotation.warning-times");
        copyPath(old, migrated, "messages");
        copyPath(old, migrated, "cobwebs");
        mergeSections(old, migrated, "restriction-targets");
        preserveModifierDefinitions(old, migrated);
        mergeSections(old, migrated, "conflict-groups");
        return migrated;
    }

    private YamlConfiguration bundledDefaults() throws IOException {
        try (InputStream stream = plugin.getResource("warzone.yml")) {
            if (stream == null) throw new IOException("Bundled warzone.yml is missing.");
            try (InputStreamReader reader = new InputStreamReader(
                    stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    static void preserveModifierDefinitions(YamlConfiguration old,
                                            YamlConfiguration migrated) {
        ConfigurationSection modifiers = old.getConfigurationSection("modifiers");
        if (modifiers == null) return;
        for (String id : modifiers.getKeys(false)) {
            String base = "modifiers." + id;
            boolean bundledModifier = migrated.contains(base);
            migrated.set(base + ".enabled", old.getBoolean(base + ".enabled", true));
            if (old.contains(base + ".weight")) {
                copyPath(old, migrated, base + ".weight");
            } else if (!bundledModifier) {
                migrated.set(base + ".weight", 10);
            }
            for (String field : SAFE_MODIFIER_FIELDS)
                copyPath(old, migrated, base + "." + field);
        }
    }

    private static void mergeSections(YamlConfiguration source,
                                      YamlConfiguration target, String path) {
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false))
            copyPath(source, target, path + "." + key);
    }

    private static void copyPath(YamlConfiguration source,
                                 YamlConfiguration target, String path) {
        if (!source.contains(path)) return;
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) {
            target.set(path, source.get(path));
            return;
        }
        target.set(path, null);
        copySection(section, target, path);
    }

    private static void copySection(ConfigurationSection source,
                                    YamlConfiguration target, String targetPath) {
        for (String key : source.getKeys(false)) {
            String childPath = targetPath + "." + key;
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) copySection(child, target, childPath);
            else target.set(childPath, source.get(key));
        }
    }

    static void saveValidatedAtomically(YamlConfiguration yaml, Path target)
            throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        ValidationResult<WarzoneConfig> validation = new WarzoneConfigLoader().load(temporary);
        if (!validation.valid()) {
            Files.deleteIfExists(temporary);
            throw new IOException("Migrated warzone.yml did not validate: "
                    + String.join("; ", validation.errors()));
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void prepareMessages(List<String> report) {
        Path target = dataFolder.resolve("warzone-messages.yml");
        if (!Files.exists(target)) {
            plugin.saveResource("warzone-messages.yml", false);
            report.add("Created default warzone-messages.yml.");
        }
    }

    private void preserveLegacyState(List<String> report) throws IOException {
        Path state = dataFolder.resolve("state").resolve("warzone-state.yml");
        if (!Files.isRegularFile(state)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(state.toFile()); }
        catch (InvalidConfigurationException ex) {
            Path backup = timestampedBackup(state, "warzone-state-invalid");
            Files.deleteIfExists(state);
            report.add("Preserved invalid old warzone state as " + backup.getFileName()
                    + "; weekly state will be selected fresh.");
            return;
        }
        if (yaml.contains("selection.active-modifiers")) {
            report.add("Existing weekly state retained for restart continuity.");
            return;
        }
        Path backup = timestampedBackup(state, "warzone-state-sequential");
        Files.deleteIfExists(state);
        report.add("Preserved sequential rotation state as " + backup.getFileName() + ".");
        report.add("A fresh weekly selection will be persisted; no short-rotation deadline was reinterpreted.");
    }

    private Path timestampedBackup(Path source, String label) throws IOException {
        Files.createDirectories(dataFolder.resolve("migration-backups"));
        Path target = dataFolder.resolve("migration-backups")
                .resolve(label + "-" + System.currentTimeMillis() + ".yml.bak");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    private void writeReport(List<String> lines) throws IOException {
        Path reports = dataFolder.resolve("migration-reports");
        Files.createDirectories(reports);
        Path report = reports.resolve("weekly-warzone-" + System.currentTimeMillis() + ".txt");
        Files.writeString(report, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        plugin.getLogger().info("Warzone migration review written to " + report.getFileName() + ".");
    }
}
