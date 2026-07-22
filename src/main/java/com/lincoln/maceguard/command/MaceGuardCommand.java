package com.lincoln.maceguard.command;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class MaceGuardCommand implements CommandExecutor, TabCompleter {
    private final MaceGuardPlugin plugin;
    public MaceGuardCommand(MaceGuardPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("here")) { if (!sender.hasPermission("maceguard.admin")) return denied(sender); return here(sender); }
        if (sub.equals("temporary")) { if (!sender.hasPermission("maceguard.admin")) return denied(sender); sender.sendMessage("Temporary blocks: " + plugin.runtime().temporaryBlocks().count() + ", persistence=" + plugin.runtime().temporaryBlocks().persistenceHealthy()); return true; }
        if (sub.equals("reload")) { if (!sender.hasPermission("maceguard.reload")) return denied(sender); plugin.reloadPlugin(sender); return true; }
        if (sub.equals("endstatus")) { if (!sender.hasPermission("maceguard.admin")) return denied(sender); sender.sendMessage(plugin.runtime().endAccessService().statusLine(true)); sender.sendMessage(plugin.runtime().endAccessService().statusLine(false)); return true; }
        if (sub.equals("endeyes") || sub.equals("endportal")) return endAccess(sender, sub.equals("endeyes"), args);
        if (!sender.hasPermission("maceguard.reset")) return denied(sender);
        Player player = sender instanceof Player value ? value : null;
        if (player == null || args.length < 2) { sender.sendMessage("This command needs a player world and a WorldGuard region ID."); return true; }
        World world = player.getWorld();
        String region = args[1];
        switch (sub) {
            case "status" -> plugin.runtime().resets().status(world, region, sender::sendMessage);
            case "capture" -> plugin.runtime().resets().capture(world, region, sender::sendMessage);
            case "validate" -> plugin.runtime().resets().validate(world, region, sender::sendMessage);
            case "plan" -> plugin.runtime().resets().plan(world, region, sender::sendMessage);
            case "arm" -> plugin.runtime().resets().arm(world, region, sender::sendMessage);
            case "disarm" -> plugin.runtime().resets().disarm(world, region, sender::sendMessage);
            case "reset" -> { if (args.length < 3) sender.sendMessage("Usage: /maceguard reset <region> <current-plan-token>"); else plugin.runtime().resets().reset(world, region, args[2], sender::sendMessage); }
            case "recover" -> plugin.runtime().resets().recoveryStatus(sender::sendMessage);
            default -> help(sender);
        }
        return true;
    }

    private boolean here(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        var query = plugin.runtime().worldGuard();
        var location = player.getLocation();
        sender.sendMessage("Mace durability behavior: " + (query.durabilityAllowed(location, player) ? "ALLOW" : "inactive"));
        sender.sendMessage("Cobweb custom behavior: " + (query.cobwebsAllowed(location, player) && query.buildAllowed(location, player) ? "ALLOW (WorldGuard build also allows)" : "inactive/denied"));
        sender.sendMessage("Effective reset profile: " + String.valueOf(query.effectiveResetProfile(location)));
        String regions = query.applicableRegions(location).stream().map(region -> region.getId() + "(priority=" + region.getPriority() + ")").reduce((a, b) -> a + ", " + b).orElse("none");
        sender.sendMessage("Applicable WorldGuard regions: " + regions);
        return true;
    }

    private boolean endAccess(CommandSender sender, boolean eyes, String[] args) {
        if (!sender.hasPermission("maceguard.admin") || args.length < 2) { sender.sendMessage("Usage: /maceguard " + (eyes ? "endeyes" : "endportal") + " <on|off|at yyyy-MM-dd HH:mm>"); return true; }
        var service = plugin.runtime().endAccessService();
        Instant at = null;
        boolean enabled;
        if (args[1].equalsIgnoreCase("on")) enabled = true;
        else if (args[1].equalsIgnoreCase("off")) enabled = false;
        else if (args[1].equalsIgnoreCase("at") && args.length >= 4) { enabled = false; at = service.parseEst(args[2] + " " + args[3]); if (at == null) { sender.sendMessage("Invalid time; expected yyyy-MM-dd HH:mm EST."); return true; } }
        else { sender.sendMessage("Invalid End access argument."); return true; }
        if (eyes) service.setEyes(enabled, at); else service.setPortals(enabled, at);
        plugin.persistConfigAsync();
        sender.sendMessage(service.statusLine(eyes));
        return true;
    }

    private boolean denied(CommandSender sender) { sender.sendMessage("You do not have permission."); return true; }
    private void help(CommandSender sender) { sender.sendMessage("/maceguard here|status <region>|capture <region>|validate <region>|plan <region>|arm <region>|disarm <region>|reset <region> <token>|recover <region>|temporary|reload|endstatus"); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("here", "status", "capture", "validate", "plan", "arm", "disarm", "reset", "recover", "temporary", "reload", "endstatus", "endeyes", "endportal") : List.of();
    }
}
