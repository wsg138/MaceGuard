package com.lincoln.maceguard.integration;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class WarzoneDuelsHook {
    private final MaceGuardPlugin plugin;

    private Plugin duelPlugin;
    private Method duelServiceMethod;
    private Method hasActiveDuelMethod;
    private Method isInActiveDuelMethod;
    private boolean lookupAttempted;
    private boolean warnedLookupFailure;

    public WarzoneDuelsHook(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void refresh() {
        duelPlugin = null;
        duelServiceMethod = null;
        hasActiveDuelMethod = null;
        isInActiveDuelMethod = null;
        lookupAttempted = false;
        duelService();
    }

    public boolean hasActiveDuel() {
        Object duelService = duelService();
        if (duelService == null || hasActiveDuelMethod == null) {
            return false;
        }
        try {
            Object result = hasActiveDuelMethod.invoke(duelService);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException ex) {
            warn("Failed to query active duel state", ex);
            refresh();
            return false;
        }
    }

    public boolean isActiveParticipant(UUID playerId) {
        Object duelService = duelService();
        if (duelService == null || isInActiveDuelMethod == null || playerId == null) {
            return false;
        }
        try {
            Object result = isInActiveDuelMethod.invoke(duelService, playerId);
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
            duelPlugin = Bukkit.getPluginManager().getPlugin("WarzoneDuels");
            if (duelPlugin == null || !duelPlugin.isEnabled()) {
                return null;
            }
            try {
                duelServiceMethod = duelPlugin.getClass().getMethod("duelService");
                Class<?> duelServiceClass = duelServiceMethod.getReturnType();
                hasActiveDuelMethod = duelServiceClass.getMethod("hasActiveDuel");
                isInActiveDuelMethod = duelServiceClass.getMethod("isInActiveDuel", UUID.class);
            } catch (ReflectiveOperationException ex) {
                warn("Failed to wire WarzoneDuels hook", ex);
                return null;
            }
        }
        if (duelPlugin == null || duelServiceMethod == null) {
            return null;
        }
        try {
            return duelServiceMethod.invoke(duelPlugin);
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
}
