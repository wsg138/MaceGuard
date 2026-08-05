package com.lincoln.maceguard.warzone.combat;

import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Transient per-player Warzone combat latch. Nothing in this service is persisted. */
public final class CombatScopeService {
    private final CombatLogXHook combat;
    private final WorldGuardQueryService worldGuard;
    private final Map<UUID, Latch> latches = new HashMap<>();

    public CombatScopeService(CombatLogXHook combat, WorldGuardQueryService worldGuard) {
        this.combat = combat;
        this.worldGuard = worldGuard;
    }

    public boolean acquireIfEligible(Player player) {
        return acquireIfEligible(player, player.getLocation());
    }

    public boolean acquireIfEligible(Player player, Location location) {
        if (!combat.available() || !combat.inCombat(player) || combat.bypass(player)) {
            latches.remove(player.getUniqueId());
            return false;
        }
        try {
            if (worldGuard == null || !worldGuard.warzoneCombatZoneAllowed(location, player)) return false;
            boolean stasisDenied = worldGuard.warzoneStasisDenied(location, player);
            latches.merge(player.getUniqueId(), new Latch(stasisDenied),
                    (oldValue, newValue) -> new Latch(oldValue.stasisDenied() || newValue.stasisDenied()));
            return true;
        } catch (RuntimeException queryFailure) {
            return false;
        }
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
        try {
            return worldGuard != null && worldGuard.warzoneCombatZoneAllowed(player.getLocation(), player);
        } catch (RuntimeException queryFailure) {
            return false;
        }
    }

    public boolean stasisDeniedAtLocation(Player player) {
        try {
            return worldGuard != null && worldGuard.warzoneStasisDenied(player.getLocation(), player);
        } catch (RuntimeException queryFailure) {
            return false;
        }
    }

    public boolean stasisDenied(Player player) {
        Latch latch = latches.get(player.getUniqueId());
        return carryoverEligible(player) && latch != null && latch.stasisDenied();
    }

    public Optional<Latch> latch(UUID playerId) { return Optional.ofNullable(latches.get(playerId)); }
    public void clear(UUID playerId) { latches.remove(playerId); }
    public void clear() { latches.clear(); }
    public int size() { return latches.size(); }
    public CombatLogXHook combat() { return combat; }

    public record Latch(boolean stasisDenied) { }
}
