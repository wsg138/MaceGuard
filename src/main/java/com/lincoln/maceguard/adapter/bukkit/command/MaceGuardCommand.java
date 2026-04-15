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

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                requirePermission(sender, "maceguard.reload");
                plugin.reloadPlugin();
                sender.sendMessage("\u00A7aMaceGuard configuration reloaded.");
                return true;
            }
            case "debug" -> {
                requirePermission(sender, "maceguard.reload");
                plugin.toggleDebug();
                sender.sendMessage("\u00A7eMaceGuard debug: " + (plugin.runtime().settings().debug() ? "\u00A7aON" : "\u00A7cOFF"));
                return true;
            }
            case "here" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("\u00A7cOnly players can use this.");
                    return true;
                }
                Location location = player.getLocation();
                List<GameplayZone> zones = plugin.runtime().zoneRegistry().highestPriorityZonesAt(location);
                sender.sendMessage("\u00A7eWorld: \u00A7f" + location.getWorld().getName());
                sender.sendMessage("\u00A7eBlock: \u00A7f" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
                sender.sendMessage("\u00A7eProtected region: " + (plugin.runtime().zoneRegistry().isProtected(location) ? "\u00A7aYES" : "\u00A7cNO"));
                sender.sendMessage("\u00A7eTop gameplay zones: \u00A7f" + (zones.isEmpty() ? "(none)" : String.join(", ", zones.stream().map(GameplayZone::name).toList())));
                return true;
            }
            case "clear" -> {
                requirePermission(sender, "maceguard.reload");
                String zoneName = args.length >= 2 ? args[1] : null;
                if (zoneName != null && plugin.runtime().zoneRegistry().findZone(zoneName) == null) {
                    sender.sendMessage("\u00A7cUnknown zone: \u00A7f" + zoneName);
                    return true;
                }
                int cleared = plugin.runtime().zoneStateService().clearTracked(zoneName);
                sender.sendMessage("\u00A7aCleared \u00A7f" + cleared + "\u00A7a tracked blocks" + (zoneName == null ? "" : " in \u00A7f" + zoneName) + "\u00A7a.");
                return true;
            }
            case "snapshot" -> {
                requirePermission(sender, "maceguard.reload");
                if (args.length < 2) {
                    sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard snapshot <zone>");
                    return true;
                }
                GameplayZone zone = plugin.runtime().zoneRegistry().findZone(args[1]);
                if (zone == null) {
                    sender.sendMessage("\u00A7cUnknown zone: \u00A7f" + args[1]);
                    return true;
                }
                plugin.runtime().snapshotService().capture(zone.name(), zone.region(), sender::sendMessage);
                sender.sendMessage("\u00A7aSnapshot capture started for zone \u00A7f" + zone.name() + "\u00A7a.");
                return true;
            }
            case "reset" -> {
                requirePermission(sender, "maceguard.reload");
                if (args.length < 2) {
                    sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard reset <zone>");
                    return true;
                }
                GameplayZone zone = plugin.runtime().zoneRegistry().findZone(args[1]);
                if (zone == null) {
                    sender.sendMessage("\u00A7cUnknown zone: \u00A7f" + args[1]);
                    return true;
                }
                plugin.runtime().zoneStateService().resetZone(zone, sender::sendMessage);
                sender.sendMessage("\u00A7aReset requested for zone \u00A7f" + zone.name() + "\u00A7a.");
                return true;
            }
            case "endeyes" -> {
                requirePermission(sender, "maceguard.reload");
                return handleEndToggle(sender, true, args);
            }
            case "endportal" -> {
                requirePermission(sender, "maceguard.reload");
                return handleEndToggle(sender, false, args);
            }
            case "endstatus" -> {
                requirePermission(sender, "maceguard.reload");
                sender.sendMessage(plugin.runtime().endAccessService().statusLine(true));
                sender.sendMessage(plugin.runtime().endAccessService().statusLine(false));
                return true;
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
            return filter(args[0], "reload", "debug", "here", "clear", "snapshot", "reset", "endeyes", "endportal", "endstatus");
        }
        if (args.length == 2 && Stream.of("clear", "snapshot", "reset").anyMatch(sub -> sub.equalsIgnoreCase(args[0]))) {
            return filter(args[1], zoneNames.toArray(String[]::new));
        }
        if (args.length == 2 && Stream.of("endeyes", "endportal").anyMatch(sub -> sub.equalsIgnoreCase(args[0]))) {
            return filter(args[1], "on", "off", "at");
        }
        return List.of();
    }

    private boolean handleEndToggle(CommandSender sender, boolean eyes, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard " + (eyes ? "endeyes" : "endportal") + " <on|off|at yyyy-MM-dd HH:mm>");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "on" -> {
                if (eyes) {
                    plugin.runtime().endAccessService().setEyes(true, null);
                } else {
                    plugin.runtime().endAccessService().setPortals(true, null);
                }
                plugin.saveConfig();
                sender.sendMessage("\u00A7a" + (eyes ? "Ender Eyes" : "End Portals") + " enabled now.");
                return true;
            }
            case "off" -> {
                if (eyes) {
                    plugin.runtime().endAccessService().setEyes(false, null);
                } else {
                    plugin.runtime().endAccessService().setPortals(false, null);
                }
                plugin.saveConfig();
                sender.sendMessage("\u00A7c" + (eyes ? "Ender Eyes" : "End Portals") + " disabled.");
                return true;
            }
            case "at" -> {
                if (args.length < 4) {
                    sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard " + (eyes ? "endeyes" : "endportal") + " at yyyy-MM-dd HH:mm");
                    return true;
                }
                String timestamp = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                Instant instant = plugin.runtime().endAccessService().parseEst(timestamp);
                if (instant == null) {
                    sender.sendMessage("\u00A7cInvalid time. Use yyyy-MM-dd HH:mm in EST.");
                    return true;
                }
                if (eyes) {
                    plugin.runtime().endAccessService().setEyes(false, instant);
                } else {
                    plugin.runtime().endAccessService().setPortals(false, instant);
                }
                plugin.saveConfig();
                sender.sendMessage("\u00A7e" + (eyes ? "Ender Eyes" : "End Portals") + " scheduled for \u00A7f" + plugin.runtime().endAccessService().formatEst(instant) + " EST\u00A7e.");
                return true;
            }
            default -> {
                sender.sendMessage("\u00A7eUsage: \u00A7f/maceguard " + (eyes ? "endeyes" : "endportal") + " <on|off|at yyyy-MM-dd HH:mm>");
                return true;
            }
        }
    }

    private String usage() {
        return "\u00A7eUsage: \u00A7f/maceguard reload\u00A77, \u00A7f/maceguard debug\u00A77, \u00A7f/maceguard here\u00A77, \u00A7f/maceguard clear [zone]\u00A77, \u00A7f/maceguard snapshot <zone>\u00A77, \u00A7f/maceguard reset <zone>\u00A77, \u00A7f/maceguard endeyes <on|off|at time>\u00A77, \u00A7f/maceguard endportal <on|off|at time>\u00A77, \u00A7f/maceguard endstatus";
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
    }
}
