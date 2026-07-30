package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.config.LegacyWarzoneConverter;
import com.lincoln.maceguard.warzone.config.ValidationResult;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

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
        Path config = dataFolder.resolve("warzone.yml");
        boolean configReady = Files.isRegularFile(config);
        if (!configReady && Files.isRegularFile(legacyFolder.resolve("config.yml"))) {
            ValidationResult<String> conversion = new LegacyWarzoneConverter().convert(legacyFolder.resolve("config.yml"));
            if (!conversion.valid()) {
                conversion.errors().forEach(error -> plugin.getLogger().severe(
                        "Warzone migration error in " + legacyFolder.resolve("config.yml") + ": " + error));
                plugin.getLogger().severe("Legacy WarzoneRotator files were left unchanged; create a valid MaceGuard/warzone.yml before reloading.");
                return false;
            }
            try {
                writeNew(config, conversion.value().getBytes(StandardCharsets.UTF_8));
                plugin.getLogger().info("Imported WarzoneRotator/config.yml to MaceGuard/warzone.yml (disabled-items became DISABLED restrictions).");
                configReady = true;
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not write imported MaceGuard/warzone.yml: " + ex.getMessage());
                return false;
            }
        }
        if (!configReady) {
            plugin.saveResource("warzone.yml", false);
            plugin.getLogger().info("Created default MaceGuard/warzone.yml.");
        }

        migrateMessages();
        migrateState();
        return true;
    }

    private void migrateMessages() {
        Path target = dataFolder.resolve("warzone-messages.yml");
        if (Files.exists(target)) return;
        Path old = legacyFolder.resolve("messages.yml");
        if (Files.isRegularFile(old)) {
            var validation = new com.lincoln.maceguard.warzone.config.WarzoneMessagesLoader().load(old);
            if (!validation.valid()) {
                validation.errors().forEach(error -> plugin.getLogger().severe(
                        "Warzone migration error in " + old + ": " + error));
                return;
            }
            try {
                writeNew(target, Files.readAllBytes(old));
                plugin.getLogger().info("Imported WarzoneRotator/messages.yml to MaceGuard/warzone-messages.yml.");
                return;
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not import WarzoneRotator/messages.yml: " + ex.getMessage());
            }
        }
        plugin.saveResource("warzone-messages.yml", false);
        plugin.getLogger().info("Created default MaceGuard/warzone-messages.yml.");
    }

    private void migrateState() {
        Path target = dataFolder.resolve("state").resolve("warzone-state.yml");
        Path old = legacyFolder.resolve("state.yml");
        if (Files.exists(target) || !Files.isRegularFile(old)) return;
        try {
            YamlConfiguration source = new YamlConfiguration();
            source.load(old.toFile());
            if (!source.contains("rotation.active-id")) return;
            YamlConfiguration destination = new YamlConfiguration();
            for (String key : java.util.List.of("active-id", "started-at", "ends-at", "next-id", "emitted-warnings"))
                destination.set("rotation." + key, source.get("rotation." + key));
            writeNew(target, destination.saveToString().getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("Imported WarzoneRotator/state.yml rotation fields to MaceGuard/state/warzone-state.yml; legacy cobweb entries were not imported.");
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe("Could not import WarzoneRotator/state.yml; the legacy file was left unchanged: " + ex.getMessage());
        }
    }

    private void writeNew(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
