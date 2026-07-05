package com.lincoln.maceguard.adapter.bukkit.command;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.GameplayZone;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MaceGuardCommand implements TabExecutor {
    private static final String RELOAD_PERMISSION = "maceguard.reload";
    private static final String END_EYES_COMMAND = "endeyes";
    private static final String END_PORTAL_COMMAND = "endportal";
    private static final int SUBCOMMAND_INDEX = 0;
    private static final int TARGET_INDEX = 1;
    private static final String[] SUBCOMMANDS = {
            "reload", "debug", "stats", "here", "clear", "snapshot", "reset", END_EYES_COMMAND, END_PORTAL_COMMAND, "endstatus"
    };
    private static final String[] ZONE_COMMANDS = {"clear", "snapshot", "reset"};
    private static final String[] END_TOGGLE_COMMANDS = {END_EYES_COMMAND, END_PORTAL_COMMAND};

    private final MaceGuardPlugin plugin;

    public MaceGuardCommand(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        PluginCommand command = plugin.getCommand("maceguard");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            return handleCommand(sender, command, args);
        } catch (CommandPermissionException ignored) {
            sender.sendMessage("\u00A7cNo permission.");
            return true;
        }
    }

    private boolean handleCommand(CommandSender sender, Command command, String[] args) {
        if (!command.getName().equalsIgnoreCase("maceguard")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(usage());
            return true;
        }

        String sub = args[SUBCOMMAND_INDEX].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                return handleReload(sender);
            }
            case "debug" -> {
                return handleDebug(sender);
            }
            case "here" -> {
                return handleHere(sender);
            }
            case "clear" -> {
                return handleClear(sender, args);
            }
            case "snapshot" -> {
                return handleSnapshot(sender, args);
            }
            case "reset" -> {
                return handleReset(sender, args);
            }
            case "stats" -> {
                return handleStats(sender);
            }
            case END_EYES_COMMAND -> {
                requirePermission(sender, RELOAD_PERMISSION);
                return handleEndToggle(sender, true, args);
            }
            case END_PORTAL_COMMAND -> {
                requirePermission(sender, RELOAD_PERMISSION);
                return handleEndToggle(sender, false, args);
            }
            case "endstatus" -> {
                return handleEndStatus(sender);
            }
            default -> {
                sender.sendMessage(usage());
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> zoneNames = plugin.runtime().zoneRegistry().allGameplayZones().stream().map(GameplayZone::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (args.length == 1) {
            return filter(args[SUBCOMMAND_INDEX], SUBCOMMANDS);
        }
        if (args.length == 2 && Stream.of(ZONE_COMMANDS).anyMatch(sub -> sub.equalsIgnoreCase(args[SUBCOMMAND_INDEX]))) {
            return filter(args[TARGET_INDEX], zoneNames.toArray(String[]::new));
        }
        if (args.length == 2 && Stream.of(END_TOGGLE_COMMANDS).anyMatch(sub -> sub.equalsIgnoreCase(args[SUBCOMMAND_INDEX]))) {
            return filter(args[TARGET_INDEX], "on", "off", "at");
        }
        return List.of();
    }

    private boolean handleEndToggle(CommandSender sender, boolean eyes, String[] args) {
        if (eyes && !plugin.runtime().endAccessService().managesEyes()) {
            sender.sendMessage("\u00A77Ender Eyes are not managed by MaceGuard because \u00A7fend_access.manage_eyes\u00A77 is false.");
            return true;
        }
        if (args.length < 2) {
            sendEndToggleUsage(sender, eyes);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "on" -> setEndAccessNow(sender, eyes, true);
            case "off" -> setEndAccessNow(sender, eyes, false);
            case "at" -> scheduleEndAccess(sender, eyes, args);
            default -> {
                sendEndToggleUsage(sender, eyes);
                yield true;
            }
        };
    }

    private boolean setEndAccessNow(CommandSender sender, boolean eyes, boolean enabled) {
        setEndAccess(eyes, enabled, null);
        plugin.saveConfig();
        sender.sendMessage((enabled ? "\u00A7a" : "\u00A7c") + endAccessName(eyes) + (enabled ? " enabled now." : " disabled."));
        return true;
    }

    private boolean scheduleEndAccess(CommandSender sender, boolean eyes, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard " + endAccessCommand(eyes) + " at yyyy-MM-dd HH:mm");
            return true;
        }
        String timestamp = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        Instant instant = plugin.runtime().endAccessService().parseEst(timestamp);
        if (instant == null) {
            sender.sendMessage("\u00A7cInvalid time. Use yyyy-MM-dd HH:mm in EST.");
            return true;
        }
        setEndAccess(eyes, false, instant);
        plugin.saveConfig();
        sender.sendMessage("\u00A7e" + endAccessName(eyes) + " scheduled for \u00A7f" + plugin.runtime().endAccessService().formatEst(instant) + " EST\u00A7e.");
        return true;
    }

    private void setEndAccess(boolean eyes, boolean enabled, Instant enableAt) {
        if (eyes) {
            plugin.runtime().endAccessService().setEyes(enabled, enableAt);
        } else {
            plugin.runtime().endAccessService().setPortals(enabled, enableAt);
        }
    }

    private boolean handleReload(CommandSender sender) {
        requirePermission(sender, RELOAD_PERMISSION);
        plugin.reloadPlugin();
        sender.sendMessage("\u00A7aMaceGuard configuration reloaded.");
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        requirePermission(sender, RELOAD_PERMISSION);
        plugin.toggleDebug();
        sender.sendMessage("\u00A7eMaceGuard debug: " + (plugin.runtime().settings().debug() ? "\u00A7aON" : "\u00A7cOFF"));
        return true;
    }

    private boolean handleHere(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00A7cOnly players can use this.");
            return true;
        }
        Location location = player.getLocation();
        List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(location);
        sender.sendMessage("\u00A7eWorld: \u00A7f" + location.getWorld().getName());
        sender.sendMessage("\u00A7eBlock: \u00A7f" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
        sender.sendMessage("\u00A7eProtected region: " + (plugin.runtime().zoneRegistry().isProtected(location) ? "\u00A7aYES" : "\u00A7cNO"));
        sender.sendMessage("\u00A7eTop gameplay zones: \u00A7f" + zoneNames(zones));
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        requirePermission(sender, RELOAD_PERMISSION);
        String zoneName = args.length > TARGET_INDEX ? args[TARGET_INDEX] : null;
        if (zoneName != null && plugin.runtime().zoneRegistry().findZone(zoneName) == null) {
            sender.sendMessage("\u00A7cUnknown zone: \u00A7f" + zoneName);
            return true;
        }
        int cleared = plugin.runtime().zoneStateService().clearTracked(zoneName);
        sender.sendMessage("\u00A7aCleared \u00A7f" + cleared + "\u00A7a tracked blocks" + (zoneName == null ? "" : " in \u00A7f" + zoneName) + "\u00A7a.");
        return true;
    }

    private boolean handleSnapshot(CommandSender sender, String[] args) {
        requirePermission(sender, RELOAD_PERMISSION);
        GameplayZone zone = requireZoneArg(sender, args, "snapshot");
        if (zone == null) {
            return true;
        }
        plugin.runtime().snapshotService().capture(zone.name(), zone.region(), sender::sendMessage);
        sender.sendMessage("\u00A7aSnapshot capture started for zone \u00A7f" + zone.name() + "\u00A7a.");
        return true;
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        requirePermission(sender, RELOAD_PERMISSION);
        GameplayZone zone = requireZoneArg(sender, args, "reset");
        if (zone == null) {
            return true;
        }
        if (plugin.runtime().snapshotService().isSnapshotLoading(zone.name())) {
            sender.sendMessage("\u00A7eSnapshot for \u00A7f" + zone.name() + "\u00A7e is still loading. Try again shortly.");
            return true;
        }
        plugin.runtime().zoneStateService().resetZone(zone, sender::sendMessage);
        sender.sendMessage("\u00A7aReset requested for zone \u00A7f" + zone.name() + "\u00A7a.");
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        requirePermission(sender, RELOAD_PERMISSION);
        sender.sendMessage("\u00A7eMaceGuard stats: \u00A7f" + plugin.runtime().counters().summary());
        sender.sendMessage("\u00A7eSnapshot loading: \u00A7f" + loadingSnapshotText());
        sender.sendMessage(resetStatsLine());
        sender.sendMessage(drainStatsLine());
        sender.sendMessage(snapshotFailureLine());
        return true;
    }

    private boolean handleEndStatus(CommandSender sender) {
        requirePermission(sender, RELOAD_PERMISSION);
        sender.sendMessage(plugin.runtime().endAccessService().statusLine(true));
        sender.sendMessage(plugin.runtime().endAccessService().statusLine(false));
        return true;
    }

    private void sendEndToggleUsage(CommandSender sender, boolean eyes) {
        sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard " + endAccessCommand(eyes) + " <on|off|at yyyy-MM-dd HH:mm>");
    }

    private String endAccessCommand(boolean eyes) {
        return eyes ? END_EYES_COMMAND : END_PORTAL_COMMAND;
    }

    private String endAccessName(boolean eyes) {
        return eyes ? "Ender Eyes" : "End Portals";
    }

    private String usage() {
        return "\u00A7eUsage: \u00A7f/maceguard reload\u00A77, \u00A7f/maceguard debug\u00A77, \u00A7f/maceguard stats\u00A77, \u00A7f/maceguard here\u00A77, \u00A7f/maceguard clear [zone]\u00A77, \u00A7f/maceguard snapshot <zone>\u00A77, \u00A7f/maceguard reset <zone>\u00A77, \u00A7f/maceguard endeyes <on|off|at time>\u00A77, \u00A7f/maceguard endportal <on|off|at time>\u00A77, \u00A7f/maceguard endstatus";
    }

    private GameplayZone requireZoneArg(CommandSender sender, String[] args, String subcommand) {
        if (args.length <= TARGET_INDEX) {
            sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard " + subcommand + " <zone>");
            return null;
        }
        GameplayZone zone = plugin.runtime().zoneRegistry().findZone(args[TARGET_INDEX]);
        if (zone == null) {
            sender.sendMessage("\u00A7cUnknown zone: \u00A7f" + args[TARGET_INDEX]);
        }
        return zone;
    }

    private String zoneNames(List<GameplayZone> zones) {
        return zones.isEmpty() ? "(none)" : String.join(", ", zones.stream().map(GameplayZone::name).toList());
    }

    private String resetStatsLine() {
        return "\u00A7eReset queue size: \u00A7f" + plugin.runtime().zoneStateService().resetQueueSize()
                + "\u00A7e, active zone tasks: \u00A7f" + plugin.runtime().zoneStateService().activeZoneTaskCount()
                + "\u00A7e, active drain tasks: \u00A7f" + plugin.runtime().zoneStateService().activeDrainTaskCount();
    }

    private String drainStatsLine() {
        return "\u00A7eDrain queue size: \u00A7f" + plugin.runtime().zoneStateService().drainQueueSize()
                + "\u00A7e, temporary blocks: \u00A7f" + plugin.runtime().zoneStateService().temporaryBlockCount()
                + "\u00A7e, backstop repairs: \u00A7f" + plugin.runtime().counters().backstopRepairs();
    }

    private String snapshotFailureLine() {
        return "\u00A7eSnapshot failures: load=\u00A7f" + plugin.runtime().counters().snapshotLoadFailures()
                + "\u00A7e, save=\u00A7f" + plugin.runtime().counters().snapshotSaveFailures();
    }

    private String loadingSnapshotText() {
        List<String> loading = plugin.runtime().snapshotService().loadingZones().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return loading.isEmpty() ? "none" : String.join(", ", loading);
    }

    private void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new CommandPermissionException();
        }
    }

    private List<String> filter(String prefix, String... items) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String item : items) {
            if (item.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                matches.add(item);
            }
        }
        return matches;
    }

    private static final class CommandPermissionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
