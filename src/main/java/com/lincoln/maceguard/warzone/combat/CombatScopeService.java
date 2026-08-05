package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Transient per-player Warzone combat latch. Nothing in this service is persisted. */
public final class CombatScopeService {
    private final CombatLogXGateway combat;
    private final WorldGuardQueryService worldGuard;
    private final Consumer<String> warningSink;
    private final Map<UUID, Latch> latches = new HashMap<>();
    private boolean combatQueryFailureReported;
    private boolean stasisQueryFailureReported;

    public CombatScopeService(CombatLogXGateway combat, WorldGuardQueryService worldGuard) {
        this(combat, worldGuard, message ->
                Logger.getLogger(CombatScopeService.class.getName()).warning(message));
    }

    CombatScopeService(CombatLogXGateway combat, WorldGuardQueryService worldGuard,
                       Consumer<String> warningSink) {
        this.combat = combat;
        this.worldGuard = worldGuard;
        this.warningSink = warningSink;
    }

    public boolean acquireIfEligible(Player player) {
        return acquireIfEligible(player, player.getLocation());
    }

    public boolean acquireIfEligible(Player player, Location location) {
        if (!combatBound(player) || !combatZoneAllowed(location, player)) return false;
        boolean stasisDenied = stasisDenied(location, player);
        latches.merge(player.getUniqueId(), new Latch(stasisDenied),
                (previous, current) -> new Latch(previous.stasisDenied() || current.stasisDenied()));
        return true;
    }

    public boolean combatBound(Player player) {
        if (!combat.available() || !combat.inCombat(player) || combat.bypass(player)) {
            latches.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean carryoverEligible(Player player) {
        return combatBound(player) && latches.containsKey(player.getUniqueId());
    }

    public boolean carryoverEligible(UUID playerId) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        return player != null && carryoverEligible(player);
    }

    public boolean insideCombatZone(Player player) {
        return combatZoneAllowed(player.getLocation(), player);
    }

    public boolean stasisDeniedAtLocation(Player player) {
        return stasisDenied(player.getLocation(), player);
    }

    public boolean stasisDenied(Player player) {
        Latch latch = latches.get(player.getUniqueId());
        return carryoverEligible(player) && latch != null && latch.stasisDenied();
    }

    private boolean combatZoneAllowed(Location location, Player player) {
        if (worldGuard == null) return false;
        try {
            return worldGuard.warzoneCombatZoneAllowed(location, player);
        } catch (IllegalArgumentException | IllegalStateException | LinkageError unavailable) {
            reportCombatQueryFailure(unavailable);
            return false;
        }
    }

    private boolean stasisDenied(Location location, Player player) {
        if (worldGuard == null) return false;
        try {
            return worldGuard.warzoneStasisDenied(location, player);
        } catch (IllegalArgumentException | IllegalStateException | LinkageError unavailable) {
            reportStasisQueryFailure(unavailable);
            return false;
        }
    }

    private void reportCombatQueryFailure(Throwable failure) {
        if (combatQueryFailureReported) return;
        combatQueryFailureReported = true;
        warningSink.accept("WorldGuard warzonerotator-combat-zone query failed closed; "
                + "this acquisition was rejected and later checks will retry: "
                + failureSummary(failure));
    }

    private void reportStasisQueryFailure(Throwable failure) {
        if (stasisQueryFailureReported) return;
        stasisQueryFailureReported = true;
        warningSink.accept("WorldGuard warzonerotator-stasis query failed closed; "
                + "the failed query will not mark stasis as denied: " + failureSummary(failure));
    }

    private String failureSummary(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public Optional<Latch> latch(UUID playerId) { return Optional.ofNullable(latches.get(playerId)); }
    public void clear(UUID playerId) { latches.remove(playerId); }
    public void clear() { latches.clear(); }
    public int size() { return latches.size(); }
    public CombatLogXGateway combat() { return combat; }

    public record Latch(boolean stasisDenied) { }
}
