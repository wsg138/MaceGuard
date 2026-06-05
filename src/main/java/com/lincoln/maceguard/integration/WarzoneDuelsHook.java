package com.lincoln.maceguard.integration;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class WarzoneDuelsHook {
    private final MaceGuardPlugin plugin;

    private HookLookup lookup = HookLookup.empty();
    private boolean lookupAttempted;
    private boolean warnedLookupFailure;

    public WarzoneDuelsHook(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void refresh() {
        lookup = HookLookup.empty();
        lookupAttempted = false;
        duelService();
    }

    public boolean hasActiveDuel() {
        Object duelService = duelService();
        if (duelService == null || lookup.hasActiveDuelMethod() == null) {
            return false;
        }
        try {
            Object result = lookup.hasActiveDuelMethod().invoke(duelService);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException ex) {
            warn("Failed to query active duel state", ex);
            refresh();
            return false;
        }
    }

    public boolean isActiveParticipant(UUID playerId) {
        Object duelService = duelService();
        if (duelService == null || lookup.isInActiveDuelMethod() == null || playerId == null) {
            return false;
        }
        try {
            Object result = lookup.isInActiveDuelMethod().invoke(duelService, playerId);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException ex) {
            warn("Failed to query duel participant state", ex);
            refresh();
            return false;
        }
    }

    private Object duelService() {
        if (!lookupAttempted) {
            lookupAttempted = true;
            Plugin pluginInstance = Bukkit.getPluginManager().getPlugin("WarzoneDuels");
            if (pluginInstance == null || !pluginInstance.isEnabled()) {
                return null;
            }
            try {
                Method serviceMethod = pluginInstance.getClass().getMethod("duelService");
                Class<?> duelServiceClass = serviceMethod.getReturnType();
                lookup = new HookLookup(
                        pluginInstance,
                        serviceMethod,
                        duelServiceClass.getMethod("hasActiveDuel"),
                        duelServiceClass.getMethod("isInActiveDuel", UUID.class)
                );
            } catch (ReflectiveOperationException ex) {
                warn("Failed to wire WarzoneDuels hook", ex);
                return null;
            }
        }
        if (lookup.duelPlugin() == null || lookup.duelServiceMethod() == null) {
            return null;
        }
        try {
            return lookup.duelServiceMethod().invoke(lookup.duelPlugin());
        } catch (ReflectiveOperationException ex) {
            warn("Failed to access WarzoneDuels duel service", ex);
            return null;
        }
    }

    private void warn(String message, Exception ex) {
        if (warnedLookupFailure) {
            return;
        }
        warnedLookupFailure = true;
        plugin.getLogger().warning(message + ": " + ex.getMessage());
    }

    private record HookLookup(
            Plugin duelPlugin,
            Method duelServiceMethod,
            Method hasActiveDuelMethod,
            Method isInActiveDuelMethod
    ) {
        private static HookLookup empty() {
            return new HookLookup(null, null, null, null);
        }
    }
}
