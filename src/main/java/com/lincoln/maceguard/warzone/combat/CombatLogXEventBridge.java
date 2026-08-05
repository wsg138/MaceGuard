package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Dynamically registers only CombatLogX public lifecycle events when the soft dependency exists. */
public final class CombatLogXEventBridge implements Listener {
    private final JavaPlugin plugin;
    private final CombatIntegrationListener delegate;

    public CombatLogXEventBridge(JavaPlugin plugin, CombatIntegrationListener delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    public boolean register() {
        Plugin combatLogX = plugin.getServer().getPluginManager().getPlugin("CombatLogX");
        if (combatLogX == null || !combatLogX.isEnabled()) return false;
        ClassLoader loader = combatLogX.getClass().getClassLoader();
        try {
            register(loader, "com.github.sirblobman.combatlogx.api.event.PlayerTagEvent", delegate::onCombatTag);
            register(loader, "com.github.sirblobman.combatlogx.api.event.PlayerReTagEvent", delegate::onCombatTag);
            register(loader, "com.github.sirblobman.combatlogx.api.event.PlayerUntagEvent", delegate::onCombatUntag);
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("CombatLogX lifecycle events are unavailable: " + ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void register(ClassLoader loader, String eventClassName, Consumer<Player> consumer)
            throws ReflectiveOperationException {
        Class<?> raw = Class.forName(eventClassName, false, loader);
        if (!Event.class.isAssignableFrom(raw))
            throw new ClassNotFoundException(eventClassName + " is not a Bukkit event");
        Class<? extends Event> eventType = (Class<? extends Event>) raw;
        Method getPlayer = raw.getMethod("getPlayer");
        EventExecutor executor = (ignored, event) -> {
            try {
                Object player = getPlayer.invoke(event);
                if (player instanceof Player value) consumer.accept(value);
            } catch (ReflectiveOperationException ex) {
                plugin.getLogger().warning("Could not read CombatLogX lifecycle event: " + ex.getMessage());
            }
        };
        plugin.getServer().getPluginManager().registerEvent(eventType, this,
                EventPriority.MONITOR, executor, plugin, false);
    }
}
