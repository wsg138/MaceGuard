package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.config.ValidationResult;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfigLoader;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WarzoneMigrationService {
    private static final int SCHEMA_FOUR = 4;
    private static final int SCHEMA_FIVE = 5;
    private static final int SCHEMA_SIX = 6;
    private static final Set<String> SAFE_MODIFIER_FIELDS = Set.of(
            "enabled", "weight", "combat-carryover", "display-name", "description", "effects", "restrictions",
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
        report.add("MaceGuard Warzone schema-7 migration review");
        report.add("Generated: " + Instant.now());
        report.add("No WorldGuard regions, snapshots, arming state, or reset schedules were created or enabled.");
        try {
            prepareWarzoneConfig(report);
            prepareMessages(report);
            preserveLegacyState(report);
            if (Files.isDirectory(legacyFolder))
                report.add("Standalone WarzoneRotator directory preserved unchanged for rollback: "
                        + legacyFolder.toAbsolutePath());
            writeReport(report);
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException ex) {
            plugin.getLogger().severe("Warzone migration preparation failed safely: " + ex.getMessage());
            return false;
        }
    }

    private void prepareWarzoneConfig(List<String> report)
            throws IOException, InvalidConfigurationException {
        Path config = dataFolder.resolve("warzone.yml");
        if (!Files.isRegularFile(config)) {
            plugin.saveResource("warzone.yml", false);
            report.add("Created clean schema-" + WarzoneControlConfig.VERSION + " warzone.yml.");
            return;
        }
        YamlConfiguration old = new YamlConfiguration();
        old.load(config.toFile());
        int version = old.getInt("config-version", -1);
        if (version == WarzoneControlConfig.VERSION) {
            report.add("Existing warzone.yml already uses schema " + version + "; no rewrite.");
            return;
        }

        Path backup = timestampedBackup(config, "warzone-v" + version);
        YamlConfiguration defaults = bundledDefaults();
        if (version == SCHEMA_SIX) {
            saveValidatedAtomically(migrateSchema6(old, defaults), config);
            report.add("Backed up schema-6 warzone.yml to " + backup.getFileName() + ".");
            report.add("Migrated schema 6 to schema 7 while preserving kits, modifiers, weights, "
                    + "schedules, messages, restriction targets, and GUI settings. Combat carryover "
                    + "defaults to false for existing modifiers and stasis defaults to 60 seconds.");
            return;
        }
        if (version == SCHEMA_FIVE) {
            ValidationResult<com.lincoln.maceguard.warzone.config.WarzoneConfig> oldValidation =
                    new WarzoneConfigLoader().load(config);
            if (!oldValidation.valid())
                throw new IOException("Existing schema-5 warzone.yml is invalid and was not migrated: "
                        + String.join("; ", oldValidation.errors()));
            saveValidatedAtomically(migrateSchema5(old, defaults), config);
            report.add("Backed up schema-5 warzone.yml to " + backup.getFileName() + ".");
            report.add("Migrated schema 5 while preserving all legacy modifier definitions; "
                    + "missing combat-carryover fields were explicitly set to false.");
            return;
        }
        if (version == SCHEMA_FOUR) {
            YamlConfiguration schemaFive = migrateSchema4(old, schemaFiveDefaults(defaults));
            saveValidatedAtomically(migrateSchema5(schemaFive, defaults), config);
            report.add("Backed up schema-4 warzone.yml to " + backup.getFileName() + ".");
            report.add("Migrated schema 4 through a safe schema-5 representation; every source "
                    + "modifier missing combat-carryover was explicitly set to false.");
            return;
        }

        plugin.saveResource("warzone.yml", true);
        report.add("Backed up incompatible warzone.yml to " + backup.getFileName() + ".");
        report.add("Installed clean schema-" + WarzoneControlConfig.VERSION
                + " configuration, disabled by default.");
    }

    static YamlConfiguration migrateSchema6(YamlConfiguration old, YamlConfiguration defaults) {
        YamlConfiguration migrated = cloneYaml(defaults);
        migrated.set("config-version", WarzoneControlConfig.VERSION);
        copyPath(old, migrated, "enabled");
        copyPath(old, migrated, "region");
        copyPath(old, migrated, "rotation");
        copyPath(old, migrated, "messages");
        copyPath(old, migrated, "cobwebs");
        copyPath(old, migrated, "kits");
        copyPath(old, migrated, "gui");
        mergeSections(old, migrated, "restriction-targets");
        preserveModifierDefinitions(old, migrated);
        defaultMissingLegacyCarryover(old, migrated);
        mergeSections(old, migrated, "conflict-groups");
        if (old.contains("combat")) overlaySection(old, migrated, "combat");
        return migrated;
    }

    static YamlConfiguration migrateSchema5(YamlConfiguration old, YamlConfiguration defaults) {
        YamlConfiguration migrated = cloneYaml(defaults);
        migrated.set("config-version", WarzoneControlConfig.VERSION);
        migrated.set("enabled", old.getBoolean("enabled", false));
        copyPath(old, migrated, "region");
        copyPath(old, migrated, "rotation.selection");
        copyPath(old, migrated, "rotation.special-rules");
        copyPath(old, migrated, "rotation.warning-times");
        String timezone = old.getString("rotation.schedule.timezone",
                defaults.getString("rotation.schedule.timezone", "UTC"));
        String time = old.getString("rotation.schedule.time",
                defaults.getString("rotation.schedule.time", "04:00"));
        String weekday = old.getString("rotation.schedule.day", "SUNDAY");
        DayOfWeek day;
        try { day = DayOfWeek.valueOf(weekday.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "rotation.schedule.day is not a valid weekday: " + weekday, ex);
        }
        LocalDate anchor = LocalDate.of(1970, 1, 5).with(TemporalAdjusters.nextOrSame(day));
        migrated.set("rotation.schedule.enabled", true);
        migrated.set("rotation.schedule.timezone", timezone);
        migrated.set("rotation.schedule.anchor-date", anchor.toString());
        migrated.set("rotation.schedule.time", time);
        migrated.set("rotation.schedule.cadence.every", 1);
        migrated.set("rotation.schedule.cadence.unit", "WEEKS");
        migrated.set("rotation.schedule.cycle", List.of(java.util.Map.of("type", "RANDOM")));
        copyPath(old, migrated, "messages");
        copyPath(old, migrated, "cobwebs");
        mergeSections(old, migrated, "restriction-targets");
        preserveModifierDefinitions(old, migrated);
        defaultMissingLegacyCarryover(old, migrated);
        mergeSections(old, migrated, "conflict-groups");
        ConfigurationSection bundledModifiers = defaults.getConfigurationSection("modifiers");
        if (bundledModifiers != null) {
            for (String id : bundledModifiers.getKeys(false)) {
                if (!old.contains("modifiers." + id))
                    migrated.set("modifiers." + id + ".enabled", false);
            }
        }
        if (!old.contains("modifiers.lunge-cooldown-10")) {
            migrated.set("restriction-targets.SPEAR_LUNGE.can-cooldown", true);
            migrated.set("restriction-targets.SPEAR_LUNGE.maximum-cooldown",
                    defaults.getString("restriction-targets.SPEAR_LUNGE.maximum-cooldown", "60s"));
        }
        migrated.set("kits.spear.enabled", false);
        disableKitsWithUnavailableMembers(migrated);
        return migrated;
    }

    static YamlConfiguration migrateSchema4(YamlConfiguration old, YamlConfiguration migrated) {
        migrated.set("config-version", SCHEMA_FIVE);
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
        defaultMissingLegacyCarryover(old, migrated);
        mergeSections(old, migrated, "conflict-groups");
        return migrated;
    }

    private YamlConfiguration bundledDefaults() throws IOException {
        try (InputStream stream = plugin.getResource("warzone.yml")) {
            if (stream == null) throw new IOException("Bundled warzone.yml is missing.");
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    static YamlConfiguration schemaFiveDefaults(YamlConfiguration schemaSix) {
        YamlConfiguration result = cloneYaml(schemaSix);
        result.set("config-version", SCHEMA_FIVE);
        String anchor = schemaSix.getString("rotation.schedule.anchor-date", "1970-01-04");
        result.set("rotation.schedule", null);
        result.set("rotation.schedule.day", LocalDate.parse(anchor).getDayOfWeek().name());
        result.set("rotation.schedule.time", schemaSix.getString("rotation.schedule.time", "04:00"));
        result.set("rotation.schedule.timezone", schemaSix.getString("rotation.schedule.timezone", "UTC"));
        result.set("kits", null);
        result.set("gui", null);
        result.set("restriction-targets.SPEAR", null);
        result.set("restriction-targets.SPEAR_DAMAGE", null);
        result.set("restriction-targets.SPEAR_LUNGE.can-cooldown", false);
        result.set("restriction-targets.SPEAR_LUNGE.maximum-cooldown", null);
        result.set("modifiers.spear-disabled", null);
        result.set("modifiers.spear-damage-cooldown-10", null);
        result.set("modifiers.lunge-cooldown-10", null);
        result.set("conflict-groups.spear-mode", null);
        result.set("conflict-groups.spear-damage-mode", null);
        result.set("conflict-groups.spear-lunge-mode", null);
        return result;
    }

    private static void disableKitsWithUnavailableMembers(YamlConfiguration migrated) {
        ConfigurationSection kits = migrated.getConfigurationSection("kits");
        if (kits == null) return;
        for (String kitId : kits.getKeys(false)) {
            String base = "kits." + kitId;
            if (!migrated.getBoolean(base + ".enabled", false)) continue;
            boolean unavailable = migrated.getStringList(base + ".modifiers").stream()
                    .anyMatch(id -> !migrated.contains("modifiers." + id)
                            || !migrated.getBoolean("modifiers." + id + ".enabled", false));
            if (unavailable) migrated.set(base + ".enabled", false);
        }
    }

    static void preserveModifierDefinitions(YamlConfiguration old, YamlConfiguration migrated) {
        ConfigurationSection modifiers = old.getConfigurationSection("modifiers");
        if (modifiers == null) return;
        for (String id : modifiers.getKeys(false)) {
            String base = "modifiers." + id;
            boolean bundled = migrated.contains(base);
            for (String field : SAFE_MODIFIER_FIELDS)
                preserveModifierField(old, migrated, base, bundled, field);
        }
    }


    private static void preserveModifierField(YamlConfiguration old, YamlConfiguration migrated,
                                              String base, boolean bundled, String field) {
        String path = base + "." + field;
        if (old.contains(path)) {
            copyPath(old, migrated, path);
            return;
        }
        if (bundled) return;
        if (field.equals("enabled")) migrated.set(base + ".enabled", true);
        else if (field.equals("weight")) migrated.set(base + ".weight", 10);
    }

    static void defaultMissingLegacyCarryover(YamlConfiguration source, YamlConfiguration migrated) {
        ConfigurationSection modifiers = source.getConfigurationSection("modifiers");
        if (modifiers == null) return;
        for (String id : modifiers.getKeys(false)) {
            String path = "modifiers." + id + ".combat-carryover";
            if (!source.contains(path)) migrated.set(path, false);
        }
    }

    private static void mergeSections(YamlConfiguration source, YamlConfiguration target, String path) {
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) copyPath(source, target, path + "." + key);
    }

    private static void overlaySection(YamlConfiguration source, YamlConfiguration target, String path) {
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) return;
        overlaySection(section, target, path);
    }

    private static void overlaySection(ConfigurationSection source, YamlConfiguration target,
                                       String targetPath) {
        for (String key : source.getKeys(false)) {
            String childPath = targetPath + "." + key;
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child == null) target.set(childPath, source.get(key));
            else overlaySection(child, target, childPath);
        }
    }

    private static void copyPath(YamlConfiguration source, YamlConfiguration target, String path) {
        if (!source.contains(path)) return;
        ConfigurationSection section = source.getConfigurationSection(path);
        if (section == null) {
            target.set(path, source.get(path));
            return;
        }
        target.set(path, null);
        copySection(section, target, path);
    }

    private static void copySection(ConfigurationSection source, YamlConfiguration target,
                                    String targetPath) {
        for (String key : source.getKeys(false)) {
            String childPath = targetPath + "." + key;
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) copySection(child, target, childPath);
            else target.set(childPath, source.get(key));
        }
    }

    private static YamlConfiguration cloneYaml(YamlConfiguration source) {
        YamlConfiguration result = new YamlConfiguration();
        try { result.loadFromString(source.saveToString()); }
        catch (InvalidConfigurationException ex) { throw new IllegalStateException(ex); }
        return result;
    }

    static void saveValidatedAtomically(YamlConfiguration yaml, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
        ValidationResult<WarzoneControlConfig> validation = new WarzoneControlConfigLoader().load(temporary);
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
            report.add("Preserved invalid old warzone state as " + backup.getFileName() + ".");
            return;
        }
        if (yaml.contains("state-version") || yaml.contains("selection.active-modifiers")) {
            report.add("Existing Warzone state retained; it will be validated and versioned at startup.");
            return;
        }
        Path backup = timestampedBackup(state, "warzone-state-sequential");
        Files.deleteIfExists(state);
        report.add("Preserved incompatible sequential rotation state as " + backup.getFileName() + ".");
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
        Path report = reports.resolve("warzone-schema-7-" + System.currentTimeMillis() + ".txt");
        Files.writeString(report, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        plugin.getLogger().info("Warzone migration review written to " + report.getFileName() + ".");
    }
}
