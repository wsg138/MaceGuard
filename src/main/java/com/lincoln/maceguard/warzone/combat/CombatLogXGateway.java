package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Dependency-neutral boundary for the optional CombatLogX integration.
 *
 * <p>No CombatLogX type is exposed here, which keeps the rest of MaceGuard loadable when the
 * soft dependency is absent.</p>
 */
public interface CombatLogXGateway extends AutoCloseable {
    boolean available();

    String unavailableReason();

    boolean inCombat(Player player);

    boolean bypass(Player player);

    int maximumSeconds(Player player);

    Duration remaining(Player player);

    void register(Lifecycle lifecycle);

    @Override
    void close();

    interface Lifecycle {
        void tagged(Player player, Location tagLocation);

        void untagged(Player player);
    }
}
