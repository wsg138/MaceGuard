package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.Player;

import java.time.Duration;

final class UnavailableCombatLogXGateway implements CombatLogXGateway {
    private final String reason;

    UnavailableCombatLogXGateway(String reason) {
        this.reason = reason;
    }

    @Override public boolean available() { return false; }
    @Override public String unavailableReason() { return reason; }
    @Override public boolean inCombat(Player player) { return false; }
    @Override public boolean bypass(Player player) { return false; }
    @Override public int maximumSeconds(Player player) { return 0; }
    @Override public Duration remaining(Player player) { return Duration.ZERO; }
    @Override public void register(Lifecycle lifecycle) { }
    @Override public void close() { }
}
