package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runtime-validated adapter for CombatLogX's optional public API. */
final class DirectCombatLogXGateway implements CombatLogXGateway, Listener {
    private static final String TAG_EVENT = "com.github.sirblobman.combatlogx.api.event.PlayerTagEvent";
    private static final String RETAG_EVENT = "com.github.sirblobman.combatlogx.api.event.PlayerReTagEvent";
    private static final String UNTAG_EVENT = "com.github.sirblobman.combatlogx.api.event.PlayerUntagEvent";
    private static final String BOSS_BAR_UPDATER = "combatlogx.expansion.boss.bar.BossBarUpdater";

    private final JavaPlugin owner;
    private Optional<Plugin> combatLogX;
    private final Map<UUID, BossBarPreference> suppressedBossBars = new HashMap<>();
    private boolean bossBarWarningReported;
    private final Method isInCombatMethod;
    private final Method canBypassMethod;
    private final Method maximumSecondsMethod;
    private final Method tagInformationMethod;
    private final Method millisLeftMethod;
    private final Method retagMethod;
    private final Object playerTagType;
    private final Object attackerReason;
    private final Object attackedReason;
    private final Object unknownReason;
    private final Class<? extends Event> tagEventClass;
    private final Class<? extends Event> reTagEventClass;
    private final Class<? extends Event> untagEventClass;
    private final Method tagPlayerMethod;
    private final Method reTagPlayerMethod;
    private final Method untagPlayerMethod;
    private Optional<Object> combatManager;
    private Optional<Lifecycle> lifecycle = Optional.empty();
    private boolean registered;

    private DirectCombatLogXGateway(JavaPlugin owner, Plugin combatLogX, Object combatManager,
                                    Method isInCombatMethod, Method canBypassMethod,
                                    Method maximumSecondsMethod, Method tagInformationMethod,
                                    Method millisLeftMethod, Method retagMethod,
                                    Object playerTagType, Object attackerReason, Object attackedReason,
                                    Object unknownReason,
                                    Class<? extends Event> tagEventClass,
                                    Class<? extends Event> reTagEventClass,
                                    Class<? extends Event> untagEventClass,
                                    Method tagPlayerMethod, Method reTagPlayerMethod,
                                    Method untagPlayerMethod) {
        this.owner = owner;
        this.combatLogX = Optional.of(combatLogX);
        this.combatManager = Optional.of(combatManager);
        this.isInCombatMethod = isInCombatMethod;
        this.canBypassMethod = canBypassMethod;
        this.maximumSecondsMethod = maximumSecondsMethod;
        this.tagInformationMethod = tagInformationMethod;
        this.millisLeftMethod = millisLeftMethod;
        this.retagMethod = retagMethod;
        this.playerTagType = playerTagType;
        this.attackerReason = attackerReason;
        this.attackedReason = attackedReason;
        this.unknownReason = unknownReason;
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
            Class<?> tagType = Class.forName("com.github.sirblobman.combatlogx.api.object.TagType", false, loader);
            Class<?> tagReason = Class.forName("com.github.sirblobman.combatlogx.api.object.TagReason", false, loader);
            Method retag = managerType.getMethod("tag", Player.class, Entity.class, tagType, tagReason);
            Object playerType = enumConstant(tagType, "PLAYER");
            Object attacker = enumConstant(tagReason, "ATTACKER");
            Object attacked = enumConstant(tagReason, "ATTACKED");
            Object unknown = enumConstant(tagReason, "UNKNOWN");
            Class<? extends Event> tagClass = eventClass(loader, tagEventName);
            Class<? extends Event> reTagClass = eventClass(loader, reTagEventName);
            Class<? extends Event> untagClass = eventClass(loader, untagEventName);
            Method tagPlayer = playerMethod(tagClass);
            Method reTagPlayer = playerMethod(reTagClass);
            Method untagPlayer = playerMethod(untagClass);

            return new DirectCombatLogXGateway(owner, candidate, manager, isInCombat, canBypass,
                    maximumSeconds, tagInformation, millisLeft, retag, playerType, attacker,
                    attacked, unknown, tagClass, reTagClass,
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

    private static Object enumConstant(Class<?> type, String name) throws ReflectiveOperationException {
        return type.getField(name).get(null);
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

    @Override public boolean retag(Player player, Player enemy, boolean attacker) {
        if (!inCombat(player) || bypass(player)) return false;
        return (boolean) invoke(retagMethod, requireCombatManager(), player, enemy,
                playerTagType, enemy == null ? unknownReason
                        : attacker ? attackerReason : attackedReason);
    }

    @Override
    public Duration remaining(Player player) {
        Object information = invoke(tagInformationMethod, requireCombatManager(), player);
        if (information == null) return Duration.ZERO;
        long millis = ((Number) invoke(millisLeftMethod, information)).longValue();
        return Duration.ofMillis(Math.max(0L, millis));
    }

    @Override
    public boolean suppressBossBar(Player player) {
        try {
            Plugin plugin = combatLogX.orElseThrow();
            Object updater = bossBarUpdater(plugin);
            if (updater == null) {
                if (bossBarExpansionEnabled(plugin))
                    throw new IllegalStateException("CombatLogX Boss Bar expansion has no compatible updater");
                return true;
            }
            YamlConfiguration data = playerData(plugin, player);
            UUID id = player.getUniqueId();
            BossBarPreference previous = suppressedBossBars.get(id);
            if (previous != null && Boolean.FALSE.equals(data.get("bossbar"))) return true;
            Object original = previous == null ? data.get("bossbar") : previous.originalValue();
            if (!Boolean.FALSE.equals(data.get("bossbar"))) data.set("bossbar", false);
            try {
                invoke(updater.getClass().getMethod("remove", Player.class), updater, player);
            } catch (RuntimeException | ReflectiveOperationException failure) {
                data.set("bossbar", original);
                throw failure;
            }
            suppressedBossBars.putIfAbsent(id, new BossBarPreference(player, original));
            return true;
        } catch (RuntimeException | ReflectiveOperationException | LinkageError failure) {
            warnBossBarFailure(failure);
            return false;
        }
    }

    @Override
    public void restoreBossBar(Player player) {
        BossBarPreference previous = suppressedBossBars.get(player.getUniqueId());
        if (previous == null) return;
        try {
            Plugin plugin = combatLogX.orElseThrow();
            Object manager = invoke(plugin.getClass().getMethod("getPlayerDataManager"), plugin);
            YamlConfiguration data = (YamlConfiguration) invoke(
                    manager.getClass().getMethod("get", OfflinePlayer.class), manager, player);
            data.set("bossbar", previous.originalValue());
            invoke(manager.getClass().getMethod("save", OfflinePlayer.class), manager, player);
            suppressedBossBars.remove(player.getUniqueId());
        } catch (RuntimeException | ReflectiveOperationException | LinkageError failure) {
            warnBossBarFailure(failure);
        }
    }

    private YamlConfiguration playerData(Plugin plugin, Player player)
            throws ReflectiveOperationException {
        Object manager = invoke(plugin.getClass().getMethod("getPlayerDataManager"), plugin);
        return (YamlConfiguration) invoke(
                manager.getClass().getMethod("get", OfflinePlayer.class), manager, player);
    }

    private Object bossBarUpdater(Plugin plugin) throws ReflectiveOperationException {
        Object manager = invoke(plugin.getClass().getMethod("getTimerManager"), plugin);
        Object updaters = invoke(manager.getClass().getMethod("getTimerUpdaters"), manager);
        if (!(updaters instanceof Iterable<?> iterable))
            throw new IllegalStateException("CombatLogX timer updaters are not iterable");
        for (Object updater : iterable)
            if (BOSS_BAR_UPDATER.equals(updater.getClass().getName())
                    || "BossBarUpdater".equals(updater.getClass().getSimpleName())) return updater;
        return null;
    }

    private boolean bossBarExpansionEnabled(Plugin plugin) throws ReflectiveOperationException {
        Object manager = invoke(plugin.getClass().getMethod("getExpansionManager"), plugin);
        Object expansions = invoke(manager.getClass().getMethod("getAllExpansions"), manager);
        if (!(expansions instanceof Iterable<?> iterable))
            throw new IllegalStateException("CombatLogX expansions are not iterable");
        for (Object expansion : iterable) {
            Object name = invoke(expansion.getClass().getMethod("getName"), expansion);
            if (!"bossbar".equals(String.valueOf(name).replace(" ", "")
                    .toLowerCase(java.util.Locale.ROOT))) continue;
            Object state = invoke(expansion.getClass().getMethod("getState"), expansion);
            return "ENABLED".equals(String.valueOf(state));
        }
        return false;
    }

    private void warnBossBarFailure(Throwable failure) {
        if (bossBarWarningReported) return;
        bossBarWarningReported = true;
        owner.getLogger().warning("CombatLogX Boss Bar handoff failed; Warzone boss bar will remain hidden: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    private record BossBarPreference(Player player, Object originalValue) { }

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
        for (BossBarPreference preference : java.util.List.copyOf(suppressedBossBars.values()))
            restoreBossBar(preference.player());
        if (registered) HandlerList.unregisterAll(this);
        registered = false;
        lifecycle = Optional.empty();
        combatManager = Optional.empty();
        combatLogX = Optional.empty();
    }
}
