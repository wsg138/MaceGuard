package com.lincoln.maceguard.integration;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/** Reflection keeps MaceGuard independently loadable while failing closed for delegated cobwebs. */
public final class WarzoneRotatorHook {
    private final JavaPlugin owner;
    private final Logger logger;
    private Plugin plugin;
    private Method canPlace;
    private Method isTracked;
    private Method resetComplete;
    private boolean warned;

    public WarzoneRotatorHook(JavaPlugin owner) {
        this.owner = owner;
        this.logger = owner.getLogger();
    }

    public void refresh() {
        plugin = owner.getServer().getPluginManager().getPlugin("WarzoneRotator");
        canPlace = null;
        isTracked = null;
        resetComplete = null;
        warned = false;
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            Class<?> type = plugin.getClass();
            canPlace = type.getMethod("isCobwebPlacementAllowed", Player.class, Location.class);
            isTracked = type.getMethod("isTrackedTemporaryCobweb", Block.class);
            resetComplete = type.getMethod("onMaceGuardWarzoneReset");
        } catch (ReflectiveOperationException ex) {
            warn("WarzoneRotator bridge is incompatible; delegated cobwebs will stay disabled: " + ex.getMessage());
        }
    }

    public boolean canPlace(Player player, Location location) {
        return invokeBoolean(canPlace, player, location);
    }

    public boolean isTracked(Block block) {
        return invokeBoolean(isTracked, block);
    }

    public void resetCompleted() {
        if (resetComplete == null || plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            resetComplete.invoke(plugin);
        } catch (ReflectiveOperationException ex) {
            warn("Could not notify WarzoneRotator after reset: " + ex.getMessage());
        }
    }

    private boolean invokeBoolean(Method method, Object... args) {
        if (method == null || plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(plugin, args));
        } catch (ReflectiveOperationException ex) {
            warn("WarzoneRotator bridge call failed; delegated cobwebs were denied: " + ex.getMessage());
            return false;
        }
    }

    private void warn(String message) {
        if (!warned) {
            logger.warning(message);
            warned = true;
        }
    }
}
