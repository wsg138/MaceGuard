package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Runtime-validated adapter for CombatLogX's optional public API. */
final class DirectCombatLogXGateway implements CombatLogXGateway, Listener {
    private static final String TAG_EVENT = "com.github.sirblobman.combatlogx.api.event.PlayerTagEvent";
    private static final String RETAG_EVENT = "com.github.sirblobman.combatlogx.api.event.PlayerReTagEvent";
    private static final String UNTAG_EVENT = "com.github.sirblobman.combatlogx.api.event.PlayerUntagEvent";

    private final JavaPlugin owner;
    private final Method isInCombatMethod;
    private final Method canBypassMethod;
    private final Method maximumSecondsMethod;
    private final Method tagInformationMethod;
    private final Method millisLeftMethod;
    private final Class<? extends Event> tagEventClass;
    private final Class<? extends Event> reTagEventClass;
    private final Class<? extends Event> untagEventClass;
    private final Method tagPlayerMethod;
    private final Method reTagPlayerMethod;
    private final Method untagPlayerMethod;
    private Optional<Object> combatManager;
    private Optional<Lifecycle> lifecycle = Optional.empty();
    private boolean registered;

    private DirectCombatLogXGateway(JavaPlugin owner, Object combatManager,
                                    Method isInCombatMethod, Method canBypassMethod,
                                    Method maximumSecondsMethod, Method tagInformationMethod,
                                    Method millisLeftMethod,
                                    Class<? extends Event> tagEventClass,
                                    Class<? extends Event> reTagEventClass,
                                    Class<? extends Event> untagEventClass,
                                    Method tagPlayerMethod, Method reTagPlayerMethod,
                                    Method untagPlayerMethod) {
        this.owner = owner;
        this.combatManager = Optional.of(combatManager);
        this.isInCombatMethod = isInCombatMethod;
        this.canBypassMethod = canBypassMethod;
        this.maximumSecondsMethod = maximumSecondsMethod;
        this.tagInformationMethod = tagInformationMethod;
        this.millisLeftMethod = millisLeftMethod;
        this.tagEventClass = tagEventClass;
        this.reTagEventClass = reTagEventClass;
        this.untagEventClass = untagEventClass;
        this.tagPlayerMethod = tagPlayerMethod;
        this.reTagPlayerMethod = reTagPlayerMethod;
        this.untagPlayerMethod = untagPlayerMethod;
    }

    static DirectCombatLogXGateway connect(JavaPlugin owner, Plugin candidate) {
        return connect(owner, candidate, TAG_EVENT, RETAG_EVENT, UNTAG_EVENT);
    }

    static DirectCombatLogXGateway connect(JavaPlugin owner, Plugin candidate,
                                           String tagEventName, String reTagEventName,
                                           String untagEventName) {
        try {
            Method getCombatManager = candidate.getClass().getMethod("getCombatManager");
            Object manager = invoke(getCombatManager, candidate);
            if (manager == null) throw new IllegalStateException("CombatLogX returned no combat manager");

            Class<?> managerType = manager.getClass();
            Method isInCombat = managerType.getMethod("isInCombat", Player.class);
            Method canBypass = managerType.getMethod("canBypass", Player.class);
            Method maximumSeconds = managerType.getMethod("getMaxTimerSeconds", Player.class);
            Method tagInformation = managerType.getMethod("getTagInformation", Player.class);
            Method millisLeft = tagInformation.getReturnType().getMethod("getMillisLeftCombined");

            ClassLoader loader = candidate.getClass().getClassLoader();
            Class<? extends Event> tagClass = eventClass(loader, tagEventName);
            Class<? extends Event> reTagClass = eventClass(loader, reTagEventName);
            Class<? extends Event> untagClass = eventClass(loader, untagEventName);
            Method tagPlayer = playerMethod(tagClass);
            Method reTagPlayer = playerMethod(reTagClass);
            Method untagPlayer = playerMethod(untagClass);

            return new DirectCombatLogXGateway(owner, manager, isInCombat, canBypass,
                    maximumSeconds, tagInformation, millisLeft, tagClass, reTagClass,
                    untagClass, tagPlayer, reTagPlayer, untagPlayer);
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("CombatLogX public API is incompatible: "
                    + incompatible.getClass().getSimpleName() + ": " + incompatible.getMessage(), incompatible);
        }
    }

    private static Class<? extends Event> eventClass(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return Class.forName(name, false, loader).asSubclass(Event.class);
    }

    private static Method playerMethod(Class<? extends Event> eventClass) throws NoSuchMethodException {
        Method method = eventClass.getMethod("getPlayer");
        if (!Player.class.isAssignableFrom(method.getReturnType()))
            throw new NoSuchMethodException(eventClass.getName() + ".getPlayer() does not return Player");
        return method;
    }

    @Override public boolean available() { return combatManager.isPresent(); }
    @Override public String unavailableReason() {
        return combatManager.isEmpty() ? "CombatLogX adapter is closed" : null;
    }
    @Override public boolean inCombat(Player player) {
        return (boolean) invoke(isInCombatMethod, requireCombatManager(), player);
    }
    @Override public boolean bypass(Player player) {
        return (boolean) invoke(canBypassMethod, requireCombatManager(), player);
    }
    @Override public int maximumSeconds(Player player) {
        return ((Number) invoke(maximumSecondsMethod, requireCombatManager(), player)).intValue();
    }

    @Override
    public Duration remaining(Player player) {
        Object information = invoke(tagInformationMethod, requireCombatManager(), player);
        if (information == null) return Duration.ZERO;
        long millis = ((Number) invoke(millisLeftMethod, information)).longValue();
        return Duration.ofMillis(Math.max(0L, millis));
    }

    @Override
    public void register(Lifecycle lifecycle) {
        this.lifecycle = Optional.of(lifecycle);
        if (registered) return;
        PluginManager manager = owner.getServer().getPluginManager();
        try {
            manager.registerEvent(tagEventClass, this, EventPriority.MONITOR,
                    (listener, event) -> handleTag(event), owner, false);
            manager.registerEvent(reTagEventClass, this, EventPriority.MONITOR,
                    (listener, event) -> handleReTag(event), owner, true);
            manager.registerEvent(untagEventClass, this, EventPriority.MONITOR,
                    (listener, event) -> handleUntag(event), owner, false);
            registered = true;
        } catch (RuntimeException | LinkageError failure) {
            HandlerList.unregisterAll(this);
            this.lifecycle = Optional.empty();
            registered = false;
            throw failure;
        }
    }

    void handleTag(Event event) {
        Player player = eventPlayer(tagPlayerMethod, event);
        reconcileAfterCommit(player, player.getLocation().clone());
    }

    void handleReTag(Event event) {
        Player player = eventPlayer(reTagPlayerMethod, event);
        reconcileAfterCommit(player, player.getLocation().clone());
    }

    void handleUntag(Event event) {
        Player player = eventPlayer(untagPlayerMethod, event);
        lifecycle.filter(ignored -> registered).ifPresent(current -> current.untagged(player));
    }

    /*
     * CombatLogX fires tag/re-tag before it inserts or updates TagInformation. Capture the
     * event-time position, then reconcile after the event call stack returns so both the public
     * combat manager and the location used for latch acquisition are authoritative for that tag.
     */
    private void reconcileAfterCommit(Player player, Location tagLocation) {
        UUID playerId = player.getUniqueId();
        owner.getServer().getScheduler().runTask(owner, () -> {
            if (!registered || lifecycle.isEmpty()) return;
            Lifecycle current = lifecycle.orElseThrow();
            Player online = owner.getServer().getPlayer(playerId);
            if (!player.isOnline() || online != player) return;
            current.tagged(player, tagLocation);
        });
    }

    private static Player eventPlayer(Method method, Event event) {
        Object value = invoke(method, event);
        if (value instanceof Player player) return player;
        throw new IllegalStateException("CombatLogX event returned no Player");
    }

    private Object requireCombatManager() {
        return combatManager.orElseThrow(
                () -> new IllegalStateException("CombatLogX adapter is closed"));
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access CombatLogX API method " + method.getName(), failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("CombatLogX API method " + method.getName() + " failed", cause);
        }
    }

    @Override
    public void close() {
        if (registered) HandlerList.unregisterAll(this);
        registered = false;
        lifecycle = Optional.empty();
        combatManager = Optional.empty();
    }
}
