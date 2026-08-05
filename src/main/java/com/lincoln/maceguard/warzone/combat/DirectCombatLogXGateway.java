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
import java.util.Objects;

/** Direct adapter for CombatLogX 11.6's published public API. */
final class DirectCombatLogXGateway implements CombatLogXGateway, Listener {
    private final JavaPlugin owner;
    private final ICombatManager manager;
    private Lifecycle lifecycle;
    private boolean registered;

    private DirectCombatLogXGateway(JavaPlugin owner, ICombatLogX combatLogX) {
        this.owner = owner;
        this.manager = Objects.requireNonNull(combatLogX.getCombatManager(),
                "CombatLogX returned no combat manager");
    }

    static DirectCombatLogXGateway connect(JavaPlugin owner, Plugin candidate) {
        return new DirectCombatLogXGateway(owner, (ICombatLogX) candidate);
    }

    @Override public boolean available() { return true; }
    @Override public String unavailableReason() { return null; }
    @Override public boolean inCombat(Player player) { return manager.isInCombat(player); }
    @Override public boolean bypass(Player player) { return manager.canBypass(player); }
    @Override public int maximumSeconds(Player player) { return manager.getMaxTimerSeconds(player); }

    @Override
    public Duration remaining(Player player) {
        TagInformation information = manager.getTagInformation(player);
        if (information == null) return Duration.ZERO;
        return Duration.ofMillis(Math.max(0L, information.getMillisLeftCombined()));
    }

    @Override
    public void register(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
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
        Lifecycle current = lifecycle;
        if (registered && current != null) current.untagged(event.getPlayer());
    }

    private void reconcileAfterCommit(Player player, Location tagLocation) {
        owner.getServer().getScheduler().runTask(owner, () -> {
            Lifecycle current = lifecycle;
            if (registered && current != null) current.tagged(player, tagLocation);
        });
    }

    @Override
    public void close() {
        if (registered) HandlerList.unregisterAll(this);
        registered = false;
        lifecycle = null;
    }
}
