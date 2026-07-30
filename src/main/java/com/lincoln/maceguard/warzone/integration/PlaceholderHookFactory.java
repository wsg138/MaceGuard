package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Loaded only when PlaceholderAPI is enabled, keeping its classes outside the
 * normal MaceGuard startup linkage path.
 */
public final class PlaceholderHookFactory {
    private PlaceholderHookFactory() { }

    public static WarzonePlaceholderHook register(Plugin plugin, Supplier<WarzoneRuntime> runtime) {
        WarzonePlaceholderExpansion expansion = new WarzonePlaceholderExpansion(plugin, runtime);
        return expansion.register() ? expansion : null;
    }
}
