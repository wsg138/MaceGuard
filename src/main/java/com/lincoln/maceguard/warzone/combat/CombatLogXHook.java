package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Optional adapter over CombatLogX's documented public API. Reflection is limited to public API
 * types so MaceGuard can remain loadable when the soft dependency is absent.
 */
public final class CombatLogXHook {
    private static final String API_TYPE = "com.github.sirblobman.combatlogx.api.ICombatLogX";
    private final Object manager;
    private final Method inCombat;
    private final Method bypass;
    private final Method maximumSeconds;
    private final Method tagInformation;
    private final Method millisLeft;
    private final String unavailableReason;

    private CombatLogXHook(Object manager, Method inCombat, Method bypass,
                           Method maximumSeconds, Method tagInformation, Method millisLeft,
                           String unavailableReason) {
        this.manager = manager;
        this.inCombat = inCombat;
        this.bypass = bypass;
        this.maximumSeconds = maximumSeconds;
        this.tagInformation = tagInformation;
        this.millisLeft = millisLeft;
        this.unavailableReason = unavailableReason;
    }

    public static CombatLogXHook discover(JavaPlugin plugin) {
        Plugin candidate = plugin.getServer().getPluginManager().getPlugin("CombatLogX");
        if (candidate == null || !candidate.isEnabled()) return unavailable("CombatLogX is not installed or enabled");
        try {
            ClassLoader loader = candidate.getClass().getClassLoader();
            Class<?> apiType = Class.forName(API_TYPE, false, loader);
            if (!apiType.isInstance(candidate))
                return unavailable("CombatLogX does not expose the expected public API");
            Object manager = apiType.getMethod("getCombatManager").invoke(candidate);
            Class<?> managerType = Class.forName(
                    "com.github.sirblobman.combatlogx.api.manager.ICombatManager", false, loader);
            Method information = managerType.getMethod("getTagInformation", Player.class);
            Class<?> informationType = Class.forName(
                    "com.github.sirblobman.combatlogx.api.object.TagInformation", false, loader);
            return new CombatLogXHook(manager,
                    managerType.getMethod("isInCombat", Player.class),
                    managerType.getMethod("canBypass", Player.class),
                    managerType.getMethod("getMaxTimerSeconds", Player.class),
                    information, informationType.getMethod("getMillisLeftCombined"), null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return unavailable("CombatLogX public API is unavailable or incompatible: " + ex.getMessage());
        }
    }

    private static CombatLogXHook unavailable(String reason) {
        return new CombatLogXHook(null, null, null, null, null, null, reason);
    }

    public boolean available() { return manager != null; }
    public String unavailableReason() { return unavailableReason; }
    public boolean inCombat(Player player) { return invokeBoolean(inCombat, player); }
    public boolean bypass(Player player) { return invokeBoolean(bypass, player); }
    public int maximumSeconds(Player player) {
        Object value = invoke(maximumSeconds, player);
        return value instanceof Number number ? number.intValue() : 0;
    }
    public Duration remaining(Player player) {
        Object information = invoke(tagInformation, player);
        if (information == null) return Duration.ZERO;
        Object value = invokeOn(millisLeft, information);
        return value instanceof Number number ? Duration.ofMillis(number.longValue()) : Duration.ZERO;
    }

    private boolean invokeBoolean(Method method, Player player) {
        return Boolean.TRUE.equals(invoke(method, player));
    }

    private Object invoke(Method method, Player player) {
        return invokeOn(method, manager, player);
    }

    private Object invokeOn(Method method, Object target, Object... arguments) {
        if (method == null || target == null) return null;
        try { return method.invoke(target, arguments); }
        catch (IllegalAccessException | InvocationTargetException ex) { return null; }
    }
}
