package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Coordinates CombatLogX lifecycle callbacks with transient combat and pearl state. */
public final class CombatIntegrationListener implements CombatLogXGateway.Lifecycle {
    private final CombatScopeService scopes;
    private final StasisPearlTracker pearls;
    private final WarzoneCombatBar bar;
    private boolean barActive;

    public CombatIntegrationListener(CombatScopeService scopes, StasisPearlTracker pearls) {
        this(scopes, pearls, null);
    }

    public CombatIntegrationListener(CombatScopeService scopes, StasisPearlTracker pearls,
                                     WarzoneCombatBar bar) {
        this.scopes = scopes;
        this.pearls = pearls;
        this.bar = bar;
    }

    @Override public void tagged(Player player, Location tagLocation) {
        scopes.acquireIfEligible(player, tagLocation);
        if (bar != null && barActive) bar.reconcilePlayer(player);
    }
    @Override public void untagged(Player player) { clear(player); }
    @Override public void integrationUnavailable() { clear(); }
    @Override public void integrationAvailable() { if (bar != null) bar.clear(); scopes.clear(); }

    public void reconcile(Iterable<? extends Player> players) {
        for (Player player : players) {
            scopes.acquireIfEligible(player);
            if (bar != null && barActive) bar.reconcilePlayer(player);
        }
    }

    public void positionChanged(Player player, Location destination) {
        if (!scopes.combatBound(player)) return;
        scopes.acquireIfEligible(player, destination);
        if (bar != null && barActive) bar.reconcilePlayer(player);
    }

    public void setBarActive(boolean active) { barActive = active; }

    public void cleanup() { pearls.cleanup(System.nanoTime()); }
    public void clear() { if (bar != null) bar.clear(); scopes.clear(); pearls.clear(); }
    public void clear(Player player) {
        if (bar != null) bar.hide(player);
        clear(player.getUniqueId());
    }
    public void clear(UUID playerId) { scopes.clear(playerId); pearls.clearOwner(playerId); }
}
