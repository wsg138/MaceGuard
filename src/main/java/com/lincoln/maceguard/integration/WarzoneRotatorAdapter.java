package com.lincoln.maceguard.integration;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/** Optional, narrow bridge. Failure disables only MaceGuard's custom cobweb lifecycle. */
public final class WarzoneRotatorAdapter {
    private final JavaPlugin owner;
    private Plugin target;
    private Method canPlace;
    private boolean warned;

    public WarzoneRotatorAdapter(JavaPlugin owner) { this.owner = owner; refresh(); }

    public void refresh() {
        target = owner.getServer().getPluginManager().getPlugin("WarzoneRotator");
        canPlace = null;
        warned = false;
        if (target == null || !target.isEnabled()) return;
        try { canPlace = target.getClass().getMethod("isCobwebPlacementAllowed", Player.class, Location.class); }
        catch (ReflectiveOperationException ex) { warn("WarzoneRotator API is incompatible; custom cobweb handling is disabled: " + ex.getMessage()); }
    }

    public boolean allows(Player player, Location location) {
        if (target == null || !target.isEnabled() || canPlace == null) return false;
        try { return Boolean.TRUE.equals(canPlace.invoke(target, player, location)); }
        catch (ReflectiveOperationException ex) { warn("WarzoneRotator query failed; custom cobweb handling is disabled: " + ex.getMessage()); return false; }
    }

    public boolean available() { return target != null && target.isEnabled() && canPlace != null; }
    private void warn(String message) { if (!warned) { owner.getLogger().warning(message); warned = true; } }
}
