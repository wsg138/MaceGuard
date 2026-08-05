package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Duration;
import java.util.UUID;

public final class CombatIntegrationListener implements Listener {
    private final CombatScopeService scopes;
    private final StasisPearlTracker pearls;
    private final WarzoneMessageService messages;
    private final int minimumAgeTicks;

    public CombatIntegrationListener(CombatScopeService scopes, StasisPearlTracker pearls,
                                     WarzoneMessageService messages, Duration minimumAge) {
        this.scopes = scopes;
        this.pearls = pearls;
        this.messages = messages;
        long ticks = Math.max(1L, (minimumAge.toMillis() + 49L) / 50L);
        this.minimumAgeTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    public void onCombatTag(Player player) { scopes.acquireIfEligible(player); }
    public void onCombatUntag(Player player) { clear(player.getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || sameBlock(event.getFrom(), to)) return;
        if (scopes.combatBound(event.getPlayer())) scopes.acquireIfEligible(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportComplete(PlayerTeleportEvent event) {
        if (scopes.combatBound(event.getPlayer()))
            scopes.acquireIfEligible(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)
                || !(pearl.getShooter() instanceof Player player)) return;
        if (event.isCancelled()) {
            pearls.removePearl(pearl.getUniqueId());
            return;
        }
        pearls.launched(pearl.getUniqueId(), player.getUniqueId(), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)
                || !(pearl.getShooter() instanceof Player)) return;
        Location location = pearl.getLocation();
        pearls.landed(pearl.getUniqueId(), pearl.getTicksLived(), minimumAgeTicks,
                pearl.getServer().getCurrentTick(), position(location), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        var impact = pearls.correlate(event.getPlayer().getUniqueId(),
                event.getPlayer().getServer().getCurrentTick(), position(event.getTo()), System.nanoTime());
        if (impact.isEmpty()) return;
        Player player = event.getPlayer();
        boolean combatBound = scopes.combatBound(player);
        boolean latched = scopes.latch(player.getUniqueId()).isPresent();
        boolean stasisDenied = scopes.latch(player.getUniqueId())
                .map(CombatScopeService.Latch::stasisDenied).orElse(false);
        if (!StasisPolicy.shouldBlock(impact.orElseThrow().aged(), combatBound, false,
                player.hasPermission("warzonerotator.bypass"), latched, stasisDenied)) return;
        event.setCancelled(true);
        messages.stasisBlocked(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPearlTeleportFinalized(PlayerTeleportEvent event) {
        if (!event.isCancelled()
                || event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        pearls.correlate(event.getPlayer().getUniqueId(),
                event.getPlayer().getServer().getCurrentTick(), position(event.getTo()), System.nanoTime());
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) { clear(event.getEntity().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer().getUniqueId()); }

    public void reconcile(Iterable<? extends Player> players) {
        for (Player player : players) scopes.acquireIfEligible(player);
    }

    public void cleanup() { pearls.cleanup(System.nanoTime()); }
    public void clear() { scopes.clear(); pearls.clear(); }

    private void clear(UUID playerId) { scopes.clear(playerId); pearls.clearOwner(playerId); }

    private StasisPearlTracker.Position position(Location location) {
        return new StasisPearlTracker.Position(location.getX(), location.getY(), location.getZ());
    }

    private boolean sameBlock(Location from, Location to) {
        return from.getWorld() != null && to.getWorld() != null
                && from.getWorld().getUID().equals(to.getWorld().getUID())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
