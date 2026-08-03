package com.lincoln.maceguard.warzone.runtime;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class WarzoneMigrationService {
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
        report.add("No WorldGuard regions, snapshots, arming state, or schedules were created or enabled.");

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
            report.add("Created clean schema-4 warzone.yml.");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(config.toFile());
        int version = yaml.getInt("config-version", -1);
        if (version == com.lincoln.maceguard.warzone.config.WarzoneConfigLoader.VERSION) {
            report.add("Existing warzone.yml already uses schema " + version + "; no rewrite.");
            return;
        }
        Path backup = timestampedBackup(config, "warzone-v" + version);
        plugin.saveResource("warzone.yml", true);
        report.add("Backed up incompatible warzone.yml to " + backup.getFileName() + ".");
        report.add("Installed clean schema-4 weekly configuration.");
        report.add("Old short sequential rotations were deliberately not reinterpreted.");
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
