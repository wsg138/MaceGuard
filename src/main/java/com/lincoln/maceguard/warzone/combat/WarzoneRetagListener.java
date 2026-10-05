package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/** Keeps a Warzone combat latch on CombatLogX's timer after PvP and pearl use. */
public final class WarzoneRetagListener implements Listener {
    private static final long ALREADY_REFRESHED_MARGIN_MILLIS = 150L;

    private final JavaPlugin plugin;
    private final CombatScopeService scopes;

    public WarzoneRetagListener(JavaPlugin plugin, CombatScopeService scopes) {
        this.plugin = plugin;
        this.scopes = scopes;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) return;
        Player attacker = attacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        boolean attackerTagged = scopes.warzoneTagged(attacker);
        boolean victimTagged = scopes.warzoneTagged(victim);
        if (!attackerTagged && !victimTagged) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (attackerTagged) refresh(attacker, victim, true);
            if (victimTagged) refresh(victim, attacker, false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)
                || !(pearl.getShooter() instanceof Player player)
                || !scopes.warzoneTagged(player)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> refresh(player, null, false));
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void refresh(Player player, Player enemy, boolean attacker) {
        if (!player.isOnline() || !scopes.warzoneTagged(player)) return;
        CombatLogXGateway combat = scopes.combat();
        Duration remaining = combat.remaining(player);
        long maximumMillis = Math.max(0L, combat.maximumSeconds(player)) * 1000L;
        if (remaining != null && remaining.toMillis() >= maximumMillis - ALREADY_REFRESHED_MARGIN_MILLIS)
            return;
        combat.retag(player, enemy, attacker);
    }
}
