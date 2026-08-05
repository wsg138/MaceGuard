package com.lincoln.maceguard.warzone.combat;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads the direct CombatLogX boundary only after the soft dependency is verified as enabled. */
public final class CombatLogXGatewayFactory {
    private CombatLogXGatewayFactory() { }

    public static CombatLogXGateway discover(JavaPlugin owner) {
        PluginManager manager = owner.getServer().getPluginManager();
        Plugin candidate = manager.getPlugin("CombatLogX");
        if (candidate == null || !candidate.isEnabled()) {
            return unavailable("CombatLogX is not installed or enabled");
        }

        try {
            return DirectCombatLogXGateway.connect(owner, candidate);
        } catch (ClassCastException incompatible) {
            return unavailable("CombatLogX does not expose the expected 11.6 public API");
        } catch (IllegalStateException incompatible) {
            return unavailable(incompatible.getMessage());
        } catch (LinkageError incompatible) {
            return unavailable("CombatLogX 11.6 public API linkage failed: "
                    + incompatible.getClass().getSimpleName());
        }
    }

    private static CombatLogXGateway unavailable(String reason) {
        return new UnavailableCombatLogXGateway(reason);
    }
}
