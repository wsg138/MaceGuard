package com.lincoln.maceguard;

import com.lincoln.maceguard.listener.MaceListener;
import com.lincoln.maceguard.listener.EndAccessListener;
import com.lincoln.maceguard.listener.EndIslandListener;
import com.lincoln.maceguard.listener.ZoneFlowListener;
import com.lincoln.maceguard.listener.ZoneGuardListener;
import com.lincoln.maceguard.region.BuildBoxManager;
import com.lincoln.maceguard.region.RegionManager;
import com.lincoln.maceguard.region.Zone;
import com.lincoln.maceguard.region.ZoneManager;
import com.lincoln.maceguard.snapshot.SnapshotStore;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class MaceGuardPlugin extends JavaPlugin implements TabExecutor {

    private RegionManager regionManager;   // mace zones (damage behavior)
    private ZoneManager zoneManager;       // gameplay zones (build/flow/etc)
    private SnapshotStore snapshotStore;   // snapshots for SNAPSHOT zones

    private boolean enabled;
    private boolean playersOnly;
    private double healthFloor;
    private boolean debug;

    private boolean limitArmorDurability;
    private int durabilityPerHit;

    private ZoneGuardListener zoneGuardListener;
    private int tick5sTask = -1;

    private boolean endEyesEnabled;
    private boolean endPortalsEnabled;
    private Instant endEyesEnableAt;
    private Instant endPortalsEnableAt;
    private static final ZoneId EST_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter EST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // End main-island rules
    public record ExplosiveRule(double powerPercent, boolean playerPlacedOnly) {
        public double clampedScale() { return Math.max(0.0D, Math.min(1.0D, powerPercent / 100.0D)); }
        public boolean isZero() { return powerPercent <= 0.0D; }
    }
    private boolean endIslandEnabled;
    private int endIslandRadius;
    private boolean endIslandBlockMaces;
    private boolean endIslandFunBedSleep;
    private Map<String, ExplosiveRule> endExplosives = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocal();

        if (!Compat.isMaceSupported()) {
            getLogger().warning("Material.MACE not found; mace-specific logic will no-op.");
        }

        // Listeners
        getServer().getPluginManager().registerEvents(new MaceListener(this), this);
        zoneGuardListener = new ZoneGuardListener(this);
        getServer().getPluginManager().registerEvents(zoneGuardListener, this);
        getServer().getPluginManager().registerEvents(new ZoneFlowListener(this, zoneGuardListener), this);
        getServer().getPluginManager().registerEvents(new EndAccessListener(this), this);
        getServer().getPluginManager().registerEvents(new EndIslandListener(this), this);

        // Commands + tab completion
        PluginCommand cmd = getCommand("maceguard");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        start5sTicker();
        getLogger().info("MaceGuard enabled.");
    }

    public void reloadLocal() {
        mergeConfigDefaults();
        reloadConfig();
        this.enabled = getConfig().getBoolean("enabled", true);
        this.playersOnly = getConfig().getBoolean("players_only", true);
        this.healthFloor = getConfig().getDouble("health_floor", 4.0D);
        this.debug = getConfig().getBoolean("debug", false);

        this.limitArmorDurability = getConfig().getBoolean("mace.limit_armor_durability", true);
        this.durabilityPerHit = Math.max(1, getConfig().getInt("mace.durability_per_hit", 1));

        if (this.regionManager == null) this.regionManager = new RegionManager(this);
        this.regionManager.loadFromConfig();

        if (this.snapshotStore == null) this.snapshotStore = new SnapshotStore(this);

        if (this.zoneManager == null) this.zoneManager = new ZoneManager(this);
        this.zoneManager.loadFromConfig();

        this.endEyesEnabled = getConfig().getBoolean("end_access.allow_eyes", false);
        this.endPortalsEnabled = getConfig().getBoolean("end_access.allow_portals", false);
        this.endEyesEnableAt = parseEstInstant(getConfig().getString("end_access.eyes_enable_at_est", ""));
        this.endPortalsEnableAt = parseEstInstant(getConfig().getString("end_access.portals_enable_at_est", ""));
        // Auto-flip to enabled if the configured time has passed
        areEndEyesAllowed();
        areEndPortalsAllowed();

        this.endIslandEnabled = getConfig().getBoolean("end_island.enabled", true);
        this.endIslandRadius = Math.max(16, getConfig().getInt("end_island.island_radius", 1024));
        this.endIslandBlockMaces = getConfig().getBoolean("end_island.block_maces", true);
        this.endIslandFunBedSleep = getConfig().getBoolean("end_island.fun_bed_sleep", true);
        loadExplosiveRules();

        if (debug) {
            getLogger().info("[Debug] enabled=" + enabled + " playersOnly=" + playersOnly + " floor=" + healthFloor);
            regionManager.getZones().forEach(z -> getLogger().info("[Debug] mace zone: " + z));
            zoneManager.getZones().forEach(z -> getLogger().info("[Debug] gameplay zone: " + z));
        }
    }

    private void start5sTicker() {
        if (tick5sTask != -1) {
            Bukkit.getScheduler().cancelTask(tick5sTask);
            tick5sTask = -1;
        }
        tick5sTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            zoneManager.tick5s(zoneGuardListener);
        }, 100L, 100L); // every 5 seconds
    }

    @Override
    public void onDisable() {
        if (tick5sTask != -1) Bukkit.getScheduler().cancelTask(tick5sTask);
        getLogger().info("MaceGuard disabled.");
    }

    // ---- Command + TabComplete ----
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("maceguard")) return false;

        if (args.length == 0) {
            sender.sendMessage("§eUsage: §f/maceguard reload§7, §f/maceguard debug§7, §f/maceguard here§7, §f/maceguard clear [zone]§7, §f/maceguard snapshot <zone>§7, §f/maceguard reset <zone>§7, §f/maceguard endeyes <on|off|at time>§7, §f/maceguard endportal <on|off|at time>§7, §f/maceguard endstatus");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                reloadLocal();
                start5sTicker();
                sender.sendMessage("§aMaceGuard configuration reloaded.");
                return true;
            }
            case "debug" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                this.debug = !this.debug;
                getConfig().set("debug", this.debug);
                saveConfig();
                sender.sendMessage("§eMaceGuard debug: " + (this.debug ? "§aON" : "§cOFF"));
                return true;
            }
            case "here" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cOnly players can use this."); return true; }
                Location loc = p.getLocation();
                boolean inMaceZone = regionManager.isInAnyZone(loc);
                var zones = zoneManager.namesAt(loc);
                sender.sendMessage("§eWorld: §f" + loc.getWorld().getName());
                sender.sendMessage("§eBlock: §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                sender.sendMessage("§eIn Mace zone: " + (inMaceZone ? "§aYES" : "§cNO"));
                sender.sendMessage("§eGameplay zones here: §f" + (zones.isEmpty() ? "§7(none)" : String.join(", ", zones)));
                return true;
            }
            case "clear" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                String name = (args.length >= 2) ? args[1] : null;
                if (name != null && zoneManager.getZones().stream().noneMatch(z -> z.name.equalsIgnoreCase(name))) {
                    sender.sendMessage("§cUnknown zone: §f" + name);
                    return true;
                }
                int count = zoneGuardListener.clearTracked(name);
                sender.sendMessage("§aCleared §f" + count + " §aedited blocks" + (name != null ? " in §f" + name : "") + "§a.");
                return true;
            }
            case "snapshot" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                if (args.length < 2) { sender.sendMessage("§eUsage: §f/maceguard snapshot <zone>"); return true; }
                Zone z = zoneByName(args[1]);
                if (z == null) { sender.sendMessage("§cUnknown zone: §f" + args[1]); return true; }
                snapshotStore.captureAsync(z.name, z.cuboid);
                sender.sendMessage("§aSnapshot started for zone §f" + z.name + "§a (batched).");
                return true;
            }
            case "reset" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                if (args.length < 2) { sender.sendMessage("§eUsage: §f/maceguard reset <zone>"); return true; }
                Zone z = zoneByName(args[1]);
                if (z == null) { sender.sendMessage("§cUnknown zone: §f" + args[1]); return true; }
                zoneGuardListener.resetZone(z);
                sender.sendMessage("§aReset executed for zone §f" + z.name + "§a.");
                return true;
            }
            case "endeyes" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                if (args.length < 2) { sender.sendMessage("§eUsage: §f/maceguard endeyes <on|off|at yyyy-MM-dd HH:mm EST>"); return true; }
                return handleEndAccess(sender, true, args);
            }
            case "endportal" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                if (args.length < 2) { sender.sendMessage("§eUsage: §f/maceguard endportal <on|off|at yyyy-MM-dd HH:mm EST>"); return true; }
                return handleEndAccess(sender, false, args);
            }
            case "endstatus" -> {
                if (!sender.hasPermission("maceguard.reload")) { sender.sendMessage("§cNo permission."); return true; }
                sender.sendMessage(statusLine(true));
                sender.sendMessage(statusLine(false));
                return true;
            }
        }

        sender.sendMessage("§eUsage: §f/maceguard reload§7, §f/maceguard debug§7, §f/maceguard here§7, §f/maceguard clear [zone]§7, §f/maceguard snapshot <zone>§7, §f/maceguard reset <zone>§7, §f/maceguard endeyes <on|off|at time>§7, §f/maceguard endportal <on|off|at time>§7, §f/maceguard endstatus");
        return true;
    }

    private Zone zoneByName(String name) {
        return zoneManager.getZones().stream()
                .filter(z -> z.name.equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> zones = zoneManager.getZones().stream().map(z -> z.name).toList();

        if (args.length == 1) {
            return filter(args[0],
                    "reload","debug","here","clear","snapshot","reset","endeyes","endportal","endstatus");
        }
        if (args.length == 2 && Stream.of("clear","snapshot","reset").anyMatch(k -> k.equalsIgnoreCase(args[0]))) {
            return filter(args[1], zones.toArray(new String[0]));
        }
        if (args.length == 2 && Stream.of("endeyes","endportal").anyMatch(k -> k.equalsIgnoreCase(args[0]))) {
            return filter(args[1], "on","off","at");
        }
        return List.of();
    }

    private List<String> filter(String prefix, String... items) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String i : items) if (i.toLowerCase(Locale.ROOT).startsWith(p)) out.add(i);
        return out;
    }

    // ---- accessors ----
    public RegionManager regions() { return regionManager; }
    public ZoneManager gameplay() { return zoneManager; }
    public SnapshotStore snapshots() { return snapshotStore; }

    public boolean isPluginEnabled() { return enabled; }
    public boolean isPlayersOnly() { return playersOnly; }
    public double getHealthFloor() { return healthFloor; }
    public boolean isDebug() { return debug; }
    public boolean isLimitArmorDurability() { return limitArmorDurability; }
    public int getDurabilityPerHit() { return durabilityPerHit; }

    // ---- End island rules ----
    public boolean isEndIslandEnabled() { return endIslandEnabled; }
    public int getEndIslandRadius() { return endIslandRadius; }
    public boolean isEndIslandBlockMaces() { return endIslandBlockMaces; }
    public boolean isEndIslandFunBedSleep() { return endIslandFunBedSleep; }
    public ExplosiveRule getExplosiveRule(String key) { return endExplosives.get(key); }
    public boolean isOnEndIsland(org.bukkit.Location loc) {
        if (!endIslandEnabled) return false;
        if (loc == null || loc.getWorld() == null) return false;
        if (loc.getWorld().getEnvironment() != World.Environment.THE_END) return false;
        double x = loc.getX(), z = loc.getZ();
        return (x * x + z * z) <= (double) endIslandRadius * (double) endIslandRadius;
    }

    // ---- End access gating ----
    public boolean areEndEyesAllowed() {
        if (endEyesEnabled) return true;
        if (endEyesEnableAt != null && Instant.now().isAfter(endEyesEnableAt)) {
            setEndEyes(true, null);
            return true;
        }
        return false;
    }

    public boolean areEndPortalsAllowed() {
        if (endPortalsEnabled) return true;
        if (endPortalsEnableAt != null && Instant.now().isAfter(endPortalsEnableAt)) {
            setEndPortals(true, null);
            return true;
        }
        return false;
    }

    public String statusLine(boolean eyes) {
        boolean allowed = eyes ? endEyesEnabled : endPortalsEnabled;
        Instant at = eyes ? endEyesEnableAt : endPortalsEnableAt;
        String label = eyes ? "Ender Eyes" : "End Portals";
        if (allowed) return "§a" + label + " are enabled.";
        if (at != null) return "§e" + label + " enable at §f" + formatEst(at) + " EST";
        return "§c" + label + " are disabled.";
    }

    private boolean handleEndAccess(CommandSender sender, boolean eyes, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "on" -> {
                if (eyes) setEndEyes(true, null); else setEndPortals(true, null);
                sender.sendMessage("§a" + (eyes ? "Ender Eyes" : "End Portals") + " enabled now.");
                return true;
            }
            case "off" -> {
                if (eyes) setEndEyes(false, null); else setEndPortals(false, null);
                sender.sendMessage("§c" + (eyes ? "Ender Eyes" : "End Portals") + " disabled.");
                return true;
            }
            case "at" -> {
                if (args.length < 4) {
                    sender.sendMessage("§eUsage: §f/maceguard " + (eyes ? "endeyes" : "endportal") + " at yyyy-MM-dd HH:mm §7(EST)");
                    return true;
                }
                String timeStr = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                Instant at = parseEstInstant(timeStr);
                if (at == null) {
                    sender.sendMessage("§cInvalid time. Use yyyy-MM-dd HH:mm in EST, e.g. 2025-12-05 20:00");
                    return true;
                }
                if (eyes) setEndEyes(false, at); else setEndPortals(false, at);
                sender.sendMessage("§e" + (eyes ? "Ender Eyes" : "End Portals") + " scheduled for §f" + formatEst(at) + " EST§e.");
                return true;
            }
            default -> {
                sender.sendMessage("§eUsage: §f/maceguard " + (eyes ? "endeyes" : "endportal") + " <on|off|at yyyy-MM-dd HH:mm EST>");
                return true;
            }
        }
    }

    private void setEndEyes(boolean enabled, Instant enableAt) {
        this.endEyesEnabled = enabled;
        this.endEyesEnableAt = enabled ? null : enableAt;
        getConfig().set("end_access.allow_eyes", this.endEyesEnabled);
        getConfig().set("end_access.eyes_enable_at_est", this.endEyesEnableAt == null ? "" : formatEst(this.endEyesEnableAt));
        saveConfig();
    }

    private void setEndPortals(boolean enabled, Instant enableAt) {
        this.endPortalsEnabled = enabled;
        this.endPortalsEnableAt = enabled ? null : enableAt;
        getConfig().set("end_access.allow_portals", this.endPortalsEnabled);
        getConfig().set("end_access.portals_enable_at_est", this.endPortalsEnableAt == null ? "" : formatEst(this.endPortalsEnableAt));
        saveConfig();
    }

    private Instant parseEstInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            LocalDateTime ldt = LocalDateTime.parse(raw.trim(), EST_FORMAT);
            return ldt.atZone(EST_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            getLogger().warning("Failed to parse EST time: \"" + raw + "\" (expected yyyy-MM-dd HH:mm)");
            return null;
        }
    }

    private String formatEst(Instant instant) {
        return EST_FORMAT.withZone(EST_ZONE).format(instant);
    }

    private void loadExplosiveRules() {
        endExplosives = new HashMap<>();
        endExplosives.put("end_crystal", ruleFor("end_island.explosives.end_crystal.power_percent", 0.0D,
                getConfig().getBoolean("end_island.explosives.end_crystal.player_placed_only", true)));
        endExplosives.put("tnt", ruleFor("end_island.explosives.tnt.power_percent", 0.0D, false));
        endExplosives.put("tnt_minecart", ruleFor("end_island.explosives.tnt_minecart.power_percent", 0.0D, false));
        endExplosives.put("respawn_anchor", ruleFor("end_island.explosives.respawn_anchor.power_percent", 0.0D, false));
        endExplosives.put("bed", ruleFor("end_island.explosives.bed.power_percent", 0.0D, false));
    }

    private ExplosiveRule ruleFor(String path, double defPercent, boolean playerPlacedOnly) {
        double pct = Math.max(0.0D, Math.min(100.0D, getConfig().getDouble(path, defPercent)));
        return new ExplosiveRule(pct, playerPlacedOnly);
    }

    public BuildBoxManager buildBoxes() {
        return null;
    }

    public boolean blockInfiniteSources() {
        return false;
    }

    public Collection<Object> getAllowedPlace() {
        return List.of();
    }

    /**
     * Fills in any missing config keys from the bundled defaults without overwriting user edits.
     */
    private void mergeConfigDefaults() {
        saveDefaultConfig(); // ensure file exists on first run
        File configFile = new File(getDataFolder(), "config.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        try (InputStream defStream = getResource("config.yml")) {
            if (defStream == null) {
                getLogger().warning("Default config.yml missing from jar; skipping defaults merge.");
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException e) {
            getLogger().warning("Failed to merge default config.yml: " + e.getMessage());
        }
    }
}
