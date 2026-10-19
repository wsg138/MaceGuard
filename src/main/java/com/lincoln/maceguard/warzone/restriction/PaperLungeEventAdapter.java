package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Optional native event bridge; keeps the pinned Paper 1.21.11 API loadable. */
final class PaperLungeEventAdapter {
    private PaperLungeEventAdapter() { }

    static boolean register(JavaPlugin plugin, Listener owner,
                            BiConsumer<Player, BooleanSupplier> handler) {
        try {
            // Class.forName uses this adapter's defining loader, which can see Paper's provided API.
            Class<?> type = Class.forName("io.papermc.paper.event.entity.EntityLungeEvent");
            register(plugin, owner, type.asSubclass(Event.class), handler);
            return true;
        } catch (ClassNotFoundException absentOnOlderPaper) {
            return false;
        }
    }

    static void register(JavaPlugin plugin, Listener owner, Class<? extends Event> type,
                         BiConsumer<Player, BooleanSupplier> handler) {
        if (!EntityEvent.class.isAssignableFrom(type) || !Cancellable.class.isAssignableFrom(type))
            throw new IllegalStateException("Unsupported Paper Lunge event contract");
        Method power = powerMethod(type);
        plugin.getServer().getPluginManager().registerEvent(type, owner, EventPriority.MONITOR,
                (ignored, event) -> {
                    if (((EntityEvent) event).getEntity() instanceof Player player)
                        handler.accept(player, () -> !((Cancellable) event).isCancelled()
                                && positivePower(power, event));
                }, plugin, true);
    }

    private static Method powerMethod(Class<?> type) {
        try {
            Method power = type.getMethod("getLungePower");
            if (power.getReturnType() != int.class)
                throw new IllegalStateException("Unsupported Paper Lunge power contract");
            return power;
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("Missing Paper Lunge power API", failure);
        }
    }

    private static boolean positivePower(Method power, Event event) {
        try {
            return (int) power.invoke(event) > 0;
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Cannot read Paper Lunge power", failure);
        }
    }
}
