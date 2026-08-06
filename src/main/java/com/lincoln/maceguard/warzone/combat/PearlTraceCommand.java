package com.lincoln.maceguard.warzone.combat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Staff-only command for temporary staging traces. */
public final class PearlTraceCommand implements TabExecutor {
    private static final int SUBCOMMAND_INDEX = 0;
    private static final int PLAYER_INDEX = 1;
    private static final int REQUIRED_ARGUMENTS = 2;
    private static final int SUBCOMMAND_ARGUMENT_COUNT = 1;
    private static final int PLAYER_ARGUMENT_COUNT = 2;
    private static final List<String> SUBCOMMANDS = List.of("on", "off", "show");

    private final JavaPlugin plugin;
    private final PearlEventDiagnostics diagnostics;

    public PearlTraceCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        this.diagnostics = PearlEventDiagnostics.forPlugin(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("warzonerotator.command.debug")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        if (args.length < REQUIRED_ARGUMENTS) {
            sendUsage(sender, label);
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[PLAYER_INDEX]);
        if (target == null) {
            sender.sendMessage("That player must be online.");
            return true;
        }
        execute(sender, label, args[SUBCOMMAND_INDEX], target);
        return true;
    }

    private void execute(CommandSender sender, String label, String subcommand, Player target) {
        switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "on" -> sender.sendMessage(diagnostics.enable(target, sender)
                    ? "Pearl trace enabled for " + target.getName() + " for 10 minutes."
                    : "Pearl trace session limit reached.");
            case "off" -> sender.sendMessage(diagnostics.disable(target.getUniqueId())
                    ? "Pearl trace disabled for " + target.getName() + "."
                    : "No pearl trace was active for " + target.getName() + ".");
            case "show" -> show(sender, target);
            default -> sendUsage(sender, label);
        }
    }

    private void show(CommandSender sender, Player target) {
        List<String> lines = diagnostics.lines(target.getUniqueId());
        sender.sendMessage("Pearl trace entries for " + target.getName() + ": " + lines.size());
        lines.forEach(sender::sendMessage);
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("Usage: /" + label + " <on|off|show> <player>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("warzonerotator.command.debug")) return List.of();
        if (args.length == SUBCOMMAND_ARGUMENT_COUNT)
            return StringUtil.copyPartialMatches(args[SUBCOMMAND_INDEX], SUBCOMMANDS,
                    new ArrayList<>());
        if (args.length == PLAYER_ARGUMENT_COUNT)
            return StringUtil.copyPartialMatches(args[PLAYER_INDEX],
                    plugin.getServer().getOnlinePlayers().stream()
                            .map(Player::getName).sorted().toList(), new ArrayList<>());
        return List.of();
    }
}
