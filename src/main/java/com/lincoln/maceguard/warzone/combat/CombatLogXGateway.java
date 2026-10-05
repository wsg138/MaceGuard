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
    /** Renew an existing CombatLogX tag; enemy is null for Ender Pearl use. */
    default boolean retag(Player player, Player enemy, boolean attacker) { return false; }
    /** True only after the CombatLogX Boss Bar is absent for this player. */
    default boolean suppressBossBar(Player player) { return true; }
    /** Restore the player's prior CombatLogX Boss Bar preference after our bar is hidden. */
    default void restoreBossBar(Player player) { }
    void register(Lifecycle lifecycle);
    @Override void close();

    interface Lifecycle {
        void tagged(Player player, Location tagLocation);
        void untagged(Player player);
        default void integrationUnavailable() { }
        default void integrationAvailable() { }
    }
}
