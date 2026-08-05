package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Duration;
import java.util.Optional;

/** Tracks and blocks only the teleport produced by the matching aged Ender Pearl. */
public final class StasisPearlListener implements Listener {
    private final CombatScopeService scopes;
    private final StasisPearlTracker pearls;
    private final WarzoneMessageService messages;
    private final int minimumAgeTicks;

    public StasisPearlListener(CombatScopeService scopes, StasisPearlTracker pearls,
                               WarzoneMessageService messages, Duration minimumAge) {
        this.scopes = scopes;
        this.pearls = pearls;
        this.messages = messages;
        long ticks = Math.max(1L, (minimumAge.toMillis() + 49L) / 50L);
        this.minimumAgeTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;
        if (event.isCancelled()) {
            pearls.removePearl(pearl.getUniqueId());
            return;
        }
        pearls.launched(pearl.getUniqueId(), player.getUniqueId(), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player)) return;
        Location location = pearl.getLocation();
        pearls.landed(pearl.getUniqueId(), pearl.getTicksLived(), minimumAgeTicks,
                pearl.getServer().getCurrentTick(), position(location), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!isPearlTeleport(event)) return;
        Optional<StasisPearlTracker.Impact> impact = correlate(event);
        if (impact.isEmpty() || !shouldBlock(event.getPlayer(), impact.orElseThrow())) return;
        event.setCancelled(true);
        messages.stasisBlocked(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelledPearlTeleport(PlayerTeleportEvent event) {
        if (!event.isCancelled() || !isPearlTeleport(event)) return;
        pearls.discardCorrelated(event.getPlayer().getUniqueId(), currentTick(event),
                position(event.getTo()), System.nanoTime());
    }

    private Optional<StasisPearlTracker.Impact> correlate(PlayerTeleportEvent event) {
        return pearls.correlate(event.getPlayer().getUniqueId(), currentTick(event),
                position(event.getTo()), System.nanoTime());
    }

    private boolean shouldBlock(Player player, StasisPearlTracker.Impact impact) {
        boolean combatBound = scopes.combatBound(player);
        Optional<CombatScopeService.Latch> latch = scopes.latch(player.getUniqueId());
        return StasisPolicy.shouldBlock(impact.aged(), combatBound, false,
                player.hasPermission("warzonerotator.bypass"), latch.isPresent(),
                latch.map(CombatScopeService.Latch::stasisDenied).orElse(false));
    }

    private boolean isPearlTeleport(PlayerTeleportEvent event) {
        return event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
    }

    private long currentTick(PlayerTeleportEvent event) {
        return event.getPlayer().getServer().getCurrentTick();
    }

    private StasisPearlTracker.Position position(Location location) {
        return new StasisPearlTracker.Position(location.getX(), location.getY(), location.getZ());
    }
}
