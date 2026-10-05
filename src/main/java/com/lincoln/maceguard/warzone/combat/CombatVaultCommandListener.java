package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Guild vault access is blocked for every active CombatLogX tag, not only Warzone latches. */
public final class CombatVaultCommandListener implements Listener {
    private final CombatLogXGateway combat;
    private final WarzoneMessageService messages;
    private final Server server;
    private final Consumer<String> warningSink;
    private boolean queryFailureReported;

    public CombatVaultCommandListener(CombatLogXGateway combat, WarzoneMessageService messages,
                                      Server server, Consumer<String> warningSink) {
        this.combat = combat;
        this.messages = messages;
        this.server = server;
        this.warningSink = warningSink;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled() || !isVaultCommand(event.getMessage()) || !combat.available()) return;
        boolean blocked;
        try {
            // A tagged player's guild/Warzone bypass permissions must not reopen their vault.
            blocked = combat.inCombat(event.getPlayer());
        } catch (IllegalStateException | LinkageError unavailable) {
            blocked = true;
            if (!queryFailureReported) {
                queryFailureReported = true;
                warningSink.accept("Guild vault combat status is unavailable; vault commands are "
                        + "temporarily blocked: " + unavailable.getClass().getSimpleName());
            }
        }
        if (!blocked) return;
        event.setCancelled(true);
        messages.guildVaultUnavailable(event.getPlayer());
    }

    boolean isVaultCommand(String message) {
        String[] words = message.strip().split("\\s+", 3);
        if (words.length < 2 || !words[0].startsWith("/")
                || !words[1].equalsIgnoreCase("vault")) return false;
        String label = words[0].substring(1).toLowerCase(Locale.ROOT);
        if (Set.of("g", "guild", "lumaguilds:g", "lumaguilds:guild").contains(label)) return true;
        PluginCommand command = server.getPluginCommand(label);
        return command != null && command.getName().equalsIgnoreCase("guild")
                && command.getPlugin().getName().equalsIgnoreCase("LumaGuilds");
    }
}
