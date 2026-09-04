package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Player-facing explanation and current status for Warzone stasis blocking. */
public final class StasisCommand implements CommandExecutor {
    private final MaceGuardPlugin plugin;

    private StasisCommand(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    static void bind(JavaPlugin plugin) {
        if (!(plugin instanceof MaceGuardPlugin maceGuard)) return;
        PluginCommand command = plugin.getCommand("stasis");
        if (command == null) return;
        if (!(command.getExecutor() instanceof StasisCommand))
            command.setExecutor(new StasisCommand(maceGuard));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is only useful for players.");
            return true;
        }

        WarzoneModule module = plugin.runtime() == null ? null : plugin.runtime().warzone();
        WarzoneRuntime runtime = module == null ? null : module.runtime();
        boolean blocked = blocked(runtime, player);

        send(module, player, "<dark_gray>──────── <gold><bold>Stasis Chambers</bold> <dark_gray>────────");
        if (blocked) {
            send(module, player, "<red><bold>BLOCKED</bold> <gray>• Your stasis chamber cannot pull you right now.");
        } else {
            send(module, player, "<green><bold>AVAILABLE</bold> <gray>• Your stasis chamber is not currently blocked.");
        }
        send(module, player, "<gray>If your combat starts in the Warzone while stasis blocking is active, your stasis chamber stays blocked until that combat ends.");
        send(module, player, "<gray>Leaving the Warzone does <white>not<gray> remove the block.");
        send(module, player, "<gray>When that combat ends, the Warzone stasis block ends too.");
        send(module, player, "<gray>Fresh Ender Pearls are not treated as stasis chambers.");
        return true;
    }

    static boolean blocked(WarzoneRuntime runtime, Player player) {
        return runtime != null
                && !player.hasPermission("warzonerotator.bypass")
                && runtime.combatScopes().stasisDenied(player);
    }

    private void send(WarzoneModule module, Player player, String message) {
        if (module != null) module.send(player, message);
        else player.sendMessage(MiniMessage.miniMessage().deserialize(message));
    }
}
