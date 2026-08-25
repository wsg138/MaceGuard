package com.lincoln.maceguard.warzone.combat;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Creates a lifecycle-aware optional CombatLogX boundary. */
public final class CombatLogXGatewayFactory {
    private CombatLogXGatewayFactory() { }

    public static CombatLogXGateway discover(JavaPlugin owner) {
        return new ManagedCombatLogXGateway(owner);
    }

    static CombatLogXGateway connectEnabled(JavaPlugin owner) {
        PluginManager manager = owner.getServer().getPluginManager();
        Plugin candidate = manager.getPlugin("CombatLogX");
        if (candidate == null || !candidate.isEnabled())
            return unavailable("CombatLogX is not installed or enabled");
        try {
            return DirectCombatLogXGateway.connect(owner, candidate);
        } catch (IllegalStateException incompatible) {
            return unavailable(incompatible.getMessage());
        } catch (LinkageError incompatible) {
            return unavailable("CombatLogX public API linkage failed: "
                    + incompatible.getClass().getSimpleName());
        }
    }

    private static CombatLogXGateway unavailable(String reason) {
        return new UnavailableCombatLogXGateway(reason);
    }
}
