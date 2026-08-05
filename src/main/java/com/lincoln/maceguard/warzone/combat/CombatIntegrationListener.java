package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Coordinates CombatLogX lifecycle callbacks with transient combat and pearl state. */
public final class CombatIntegrationListener implements CombatLogXGateway.Lifecycle {
    private final CombatScopeService scopes;
    private final StasisPearlTracker pearls;

    public CombatIntegrationListener(CombatScopeService scopes, StasisPearlTracker pearls) {
        this.scopes = scopes;
        this.pearls = pearls;
    }

    @Override public void tagged(Player player) { scopes.acquireIfEligible(player); }
    @Override public void untagged(Player player) { clear(player.getUniqueId()); }

    public void reconcile(Iterable<? extends Player> players) {
        for (Player player : players) scopes.acquireIfEligible(player);
    }

    public void cleanup() { pearls.cleanup(System.nanoTime()); }
    public void clear() { scopes.clear(); pearls.clear(); }
    public void clear(UUID playerId) { scopes.clear(playerId); pearls.clearOwner(playerId); }
}
