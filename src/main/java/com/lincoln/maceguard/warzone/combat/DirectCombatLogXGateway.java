package com.lincoln.maceguard.warzone.combat;

import com.github.sirblobman.combatlogx.api.ICombatLogX;
import com.github.sirblobman.combatlogx.api.event.PlayerReTagEvent;
import com.github.sirblobman.combatlogx.api.event.PlayerTagEvent;
import com.github.sirblobman.combatlogx.api.event.PlayerUntagEvent;
import com.github.sirblobman.combatlogx.api.manager.ICombatManager;
import com.github.sirblobman.combatlogx.api.object.TagInformation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Direct adapter for CombatLogX 11.6's published public API. */
final class DirectCombatLogXGateway implements CombatLogXGateway, Listener {
    private final JavaPlugin owner;
    private Optional<ICombatManager> combatManager;
    private Optional<Lifecycle> lifecycle = Optional.empty();
    private boolean registered;

    private DirectCombatLogXGateway(JavaPlugin owner, ICombatLogX combatLogX) {
        this.owner = owner;
        ICombatManager resolved = combatLogX.getCombatManager();
        if (resolved == null) throw new IllegalStateException("CombatLogX returned no combat manager");
        this.combatManager = Optional.of(resolved);
    }

    static DirectCombatLogXGateway connect(JavaPlugin owner, Plugin candidate) {
        return new DirectCombatLogXGateway(owner, (ICombatLogX) candidate);
    }

    @Override public boolean available() { return combatManager.isPresent(); }
    @Override public String unavailableReason() {
        return combatManager.isEmpty() ? "CombatLogX adapter is closed" : null;
    }
    @Override public boolean inCombat(Player player) { return requireCombatManager().isInCombat(player); }
    @Override public boolean bypass(Player player) { return requireCombatManager().canBypass(player); }
    @Override public int maximumSeconds(Player player) {
        return requireCombatManager().getMaxTimerSeconds(player);
    }

    @Override
    public Duration remaining(Player player) {
        TagInformation information = requireCombatManager().getTagInformation(player);
        if (information == null) return Duration.ZERO;
        return Duration.ofMillis(Math.max(0L, information.getMillisLeftCombined()));
    }

    @Override
    public void register(Lifecycle lifecycle) {
        this.lifecycle = Optional.of(lifecycle);
        if (registered) return;
        owner.getServer().getPluginManager().registerEvents(this, owner);
        registered = true;
    }

    /*
     * CombatLogX 11.6 fires tag/re-tag before it inserts or updates TagInformation. Capture the
     * event-time position, then reconcile after the event call stack returns so both the public
     * combat manager and the location used for latch acquisition are authoritative for that tag.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTag(PlayerTagEvent event) {
        reconcileAfterCommit(event.getPlayer(), event.getPlayer().getLocation().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onReTag(PlayerReTagEvent event) {
        reconcileAfterCommit(event.getPlayer(), event.getPlayer().getLocation().clone());
    }

    /* CombatLogX removes TagInformation before firing PlayerUntagEvent. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(PlayerUntagEvent event) {
        lifecycle.filter(ignored -> registered)
                .ifPresent(current -> current.untagged(event.getPlayer()));
    }

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

    private ICombatManager requireCombatManager() {
        return combatManager.orElseThrow(
                () -> new IllegalStateException("CombatLogX adapter is closed"));
    }

    @Override
    public void close() {
        if (registered) HandlerList.unregisterAll(this);
        registered = false;
        lifecycle = Optional.empty();
        combatManager = Optional.empty();
    }
}
