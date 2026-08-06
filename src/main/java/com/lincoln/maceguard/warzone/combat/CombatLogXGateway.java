package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;

/** Dependency-neutral boundary for the optional CombatLogX integration. */
public interface CombatLogXGateway extends AutoCloseable {
    boolean available();
    String unavailableReason();
    boolean inCombat(Player player);
    boolean bypass(Player player);
    int maximumSeconds(Player player);
    Duration remaining(Player player);
    void register(Lifecycle lifecycle);
    @Override void close();

    interface Lifecycle {
        void tagged(Player player, Location tagLocation);
        void untagged(Player player);
        default void integrationUnavailable() { }
        default void integrationAvailable() { }
    }
}
