package com.lincoln.maceguard.command;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class MaceGuardCommand implements CommandExecutor, TabCompleter {
    private final MaceGuardPlugin plugin;

    public MaceGuardCommand(MaceGuardPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command,
                                       String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("here")) {
            if (!sender.hasPermission("maceguard.admin")) return denied(sender);
            return here(sender);
        }
        if (sub.equals("temporary")) {
            if (!sender.hasPermission("maceguard.admin")) return denied(sender);
            var temporary = plugin.runtime().temporaryBlocks();
            var diagnostics = temporary.diagnostics(System.currentTimeMillis());
            sender.sendMessage("tracked=" + diagnostics.tracked()
                    + " expired=" + diagnostics.expired()
                    + " pending-clear=" + diagnostics.pendingClear()
                    + " persistence="
                    + (diagnostics.persistenceHealthy() ? "healthy" : "failed")
                    + " capacity=" + diagnostics.capacityCurrent() + "/"
                    + diagnostics.capacityMaximum());
            if (args.length > 1 && args[1].equalsIgnoreCase("debug"))
                sender.sendMessage("temporary-removal-reasons="
                        + temporary.terminalReasonCounts());
            return true;
        }
        if (sub.equals("reload")) {
            if (!sender.hasPermission("maceguard.reload")) return denied(sender);
            plugin.reloadPlugin(sender);
            return true;
        }
        if (sub.equals("recover")) {
            if (!sender.hasPermission("maceguard.reset")) return denied(sender);
            plugin.runtime().resets().recoveryStatus(sender::sendMessage);
            return true;
        }
        if (!sender.hasPermission("maceguard.reset")) return denied(sender);
        Player player = sender instanceof Player value ? value : null;
        if (player == null || args.length < 2) {
            sender.sendMessage("This command needs a player world and a WorldGuard region ID.");
            return true;
        }
        World world = player.getWorld();
        String region = args[1];
        switch (sub) {
            case "status" -> plugin.runtime().resets().status(world, region, sender::sendMessage);
            case "capture" -> plugin.runtime().resets().capture(world, region, sender::sendMessage);
            case "validate" -> plugin.runtime().resets().validate(world, region, sender::sendMessage);
            case "plan" -> plugin.runtime().resets().plan(world, region, sender::sendMessage);
            case "arm" -> plugin.runtime().resets().arm(world, region, sender::sendMessage);
            case "disarm" -> plugin.runtime().resets().disarm(world, region, sender::sendMessage);
            case "filler", "restore" -> {
                if (args.length < 3 || (!args[2].equalsIgnoreCase("on")
                        && !args[2].equalsIgnoreCase("off")))
                    sender.sendMessage("Usage: /maceguard " + sub
                            + " <region> <on|off>");
                else if (args[2].equalsIgnoreCase("on"))
                    plugin.runtime().resets().arm(world, region, sender::sendMessage);
                else plugin.runtime().resets().disarm(world, region, sender::sendMessage);
            }
            case "schedule" -> {
                if (args.length < 3 || (!args[2].equalsIgnoreCase("on")
                        && !args[2].equalsIgnoreCase("off")))
                    sender.sendMessage("Usage: /maceguard schedule <region> <on|off>");
                else plugin.runtime().resets().setSchedule(world, region,
                        args[2].equalsIgnoreCase("on"), sender::sendMessage);
            }
            case "reset" -> {
                if (args.length < 3)
                    sender.sendMessage("Usage: /maceguard reset <region> <current-plan-token>");
                else plugin.runtime().resets().reset(world, region, args[2],
                        sender::sendMessage);
            }
            default -> help(sender);
        }
        return true;
    }

    private boolean here(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        var query = plugin.runtime().worldGuard();
        var location = player.getLocation();
        sender.sendMessage("Mace durability behavior: "
                + (query.durabilityAllowed(location, player) ? "ALLOW" : "inactive"));
        sender.sendMessage("Cobweb custom behavior: "
                + (query.cobwebsAllowed(location, player)
                && query.buildAllowed(location, player)
                ? "ALLOW (WorldGuard build also allows)" : "inactive/denied"));
        sender.sendMessage("Explosives: "
                + (query.explosivesDenied(location, player)
                ? "DENY" : "not denied by MaceGuard"));

        var policy = plugin.runtime().blockPolicyResolver().resolve(location);
        sender.sendMessage("Effective block policy: "
                + (policy.referenced() ? policy.name() : "none"));
        sender.sendMessage("Block policy source: region="
                + (policy.scopeId().isBlank() ? "none" : policy.scopeId())
                + ", kind=" + policy.sourceKind()
                + ", global=" + policy.globalSource());
        sender.sendMessage("Named policy exists: " + policy.namedPolicyExists());
        sender.sendMessage("Schema permits enforcement: "
                + policy.schemaAllowsEnforcement()
                + " (valid-schema=" + plugin.runtime().settings().validSchema() + ")");
        sender.sendMessage("Final policy result: " + policy.finalResult());

        sender.sendMessage("Effective reset profile: "
                + String.valueOf(query.effectiveResetProfile(location)));
        String regions = query.applicableRegions(location).stream()
                .map(region -> region.getId() + "(priority="
                        + region.getPriority() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("none");
        sender.sendMessage("Applicable WorldGuard regions: " + regions);
        return true;
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage("You do not have permission.");
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("/maceguard here|status <region>|capture <region>|validate <region>|"
                + "plan <region>|arm <region>|disarm <region>|schedule <region> <on|off>|"
                + "reset <region> <token>|recover|temporary [debug]|reload");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command,
                                                 String alias, String[] args) {
        if (args.length == 1)
            return List.of("here", "status", "capture", "validate", "plan", "arm",
                    "disarm", "filler", "restore", "schedule", "reset", "recover",
                    "temporary", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("temporary")) return List.of("debug");
        if (args.length == 3 && (args[0].equalsIgnoreCase("filler")
                || args[0].equalsIgnoreCase("restore")
                || args[0].equalsIgnoreCase("schedule")))
            return List.of("on", "off");
        return List.of();
    }
}
