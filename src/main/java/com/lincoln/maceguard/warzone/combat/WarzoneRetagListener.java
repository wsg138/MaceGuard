package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.function.Predicate;

/** Refreshes existing CombatLogX timers for accepted combat actions. */
public final class WarzoneRetagListener implements Listener {
    private static final long ALREADY_REFRESHED_MARGIN_MILLIS = 150L;

    private final JavaPlugin plugin;
    private final CombatScopeService scopes;
    private final Predicate<Player> insideWarzone;
    private final Predicate<Player> windChargeEnabled;
    private boolean closed;

    public WarzoneRetagListener(JavaPlugin plugin, CombatScopeService scopes) {
        this(plugin, scopes, player -> false, player -> true);
    }

    public WarzoneRetagListener(JavaPlugin plugin, CombatScopeService scopes,
                               Predicate<Player> insideWarzone,
                               Predicate<Player> windChargeEnabled) {
        this.plugin = plugin;
        this.scopes = scopes;
        this.insideWarzone = insideWarzone;
        this.windChargeEnabled = windChargeEnabled;
    }

    /** Called only after the Lunge Jab eligibility and restriction checks pass. */
    public void onAcceptedLunge(Player player, boolean insideWarzone) {
        scheduleRefresh(player, insideWarzone);
    }

    private void scheduleRefresh(Player player, boolean warzoneOnly) {
        if (closed || !eligible(player, warzoneOnly)) return;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> refresh(player, null, false, warzoneOnly));
    }

    public void close() { closed = true; }

    private boolean eligible(Player player, boolean warzoneOnly) {
        return player.isOnline() && !player.hasPermission("warzonerotator.bypass")
                && (warzoneOnly ? scopes.warzoneTagged(player) : scopes.combatBound(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) return;
        Player attacker = attacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        schedulePvpRefresh(attacker, victim);
    }

    private void schedulePvpRefresh(Player attacker, Player victim) {
        boolean attackerTagged = scopes.warzoneTagged(attacker);
        boolean victimTagged = scopes.warzoneTagged(victim);
        if (!attackerTagged && !victimTagged) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (attackerTagged) refresh(attacker, victim, true);
            if (victimTagged) refresh(victim, attacker, false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntity().getType() == EntityType.WIND_CHARGE) {
            onWindChargeLaunch(event.getEntity());
            return;
        }
        if (!(event.getEntity() instanceof EnderPearl pearl)
                || !(pearl.getShooter() instanceof Player player)
                || !scopes.warzoneTagged(player)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> refresh(player, null, false));
    }

    private void onWindChargeLaunch(Projectile projectile) {
        if (!(projectile.getShooter() instanceof Player player)) return;
        boolean warzoneOnly = insideWarzone.test(player);
        if (warzoneOnly && !windChargeEnabled.test(player)) return;
        scheduleRefresh(player, warzoneOnly);
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void refresh(Player player, Player enemy, boolean attacker) {
        refresh(player, enemy, attacker, true);
    }

    private void refresh(Player player, Player enemy, boolean attacker, boolean warzoneOnly) {
        if (closed || !eligible(player, warzoneOnly)) return;
        CombatLogXGateway combat = scopes.combat();
        Duration remaining = combat.remaining(player);
        long maximumMillis = Math.max(0L, combat.maximumSeconds(player)) * 1000L;
        if (remaining != null && remaining.toMillis() >= maximumMillis - ALREADY_REFRESHED_MARGIN_MILLIS)
            return;
        combat.retag(player, enemy, attacker);
    }
}
