package com.lincoln.maceguard.warzone.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps CombatLogX classloader-owned objects behind a replaceable delegate. Plugin lifecycle
 * events are handled without referencing CombatLogX types from this class.
 */
final class ManagedCombatLogXGateway implements CombatLogXGateway, Listener {
    private static final String DEPENDENCY = "CombatLogX";

    private final JavaPlugin owner;
    private CombatLogXGateway delegate;
    private Optional<Lifecycle> lifecycle = Optional.empty();
    private boolean listenerRegistered;
    private boolean closed;
    private long lifecycleGeneration;
    private long delegateBoundGeneration = Long.MIN_VALUE;
    private String lastReportedState;

    ManagedCombatLogXGateway(JavaPlugin owner) {
        this(owner, CombatLogXGatewayFactory.connectEnabled(owner));
    }

    ManagedCombatLogXGateway(JavaPlugin owner, CombatLogXGateway initialDelegate) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.delegate = Objects.requireNonNull(initialDelegate, "initialDelegate");
    }

    @Override public boolean available() { return delegate.available(); }
    @Override public String unavailableReason() { return delegate.unavailableReason(); }
    @Override public boolean inCombat(Player player) { return delegate.available() && delegate.inCombat(player); }
    @Override public boolean bypass(Player player) { return delegate.available() && delegate.bypass(player); }
    @Override public int maximumSeconds(Player player) {
        return delegate.available() ? delegate.maximumSeconds(player) : 0;
    }
    @Override public Duration remaining(Player player) {
        return delegate.available() ? delegate.remaining(player) : Duration.ZERO;
    }

    @Override
    public void register(Lifecycle lifecycle) {
        this.lifecycle = Optional.of(Objects.requireNonNull(lifecycle, "lifecycle"));
        if (!listenerRegistered) {
            owner.getServer().getPluginManager().registerEvents(this, owner);
            listenerRegistered = true;
        }
        bindDelegate();
        reportState();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!DEPENDENCY.equals(event.getPlugin().getName()) || closed) return;
        retire("CombatLogX was disabled");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin plugin = event.getPlugin();
        if (!DEPENDENCY.equals(plugin.getName()) || closed) return;
        replaceDelegate();
    }

    private void replaceDelegate() {
        installDelegate(CombatLogXGatewayFactory.connectEnabled(owner));
    }

    private void installDelegate(CombatLogXGateway next) {
        lifecycleGeneration++;
        delegate.close();
        delegate = Objects.requireNonNull(next, "next");
        bindDelegate();
        Lifecycle current = lifecycle.orElse(null);
        if (delegate.available() && current != null) {
            current.integrationAvailable();
            for (Player player : owner.getServer().getOnlinePlayers()) {
                if (delegate.inCombat(player) && !delegate.bypass(player))
                    current.tagged(player, player.getLocation().clone());
            }
        } else if (current != null) current.integrationUnavailable();
        reportState();
    }

    void replaceDelegateForTest(CombatLogXGateway next) {
        installDelegate(next);
    }

    private void retire(String reason) {
        lifecycleGeneration++;
        delegate.close();
        delegate = new UnavailableCombatLogXGateway(reason);
        delegateBoundGeneration = Long.MIN_VALUE;
        Lifecycle current = lifecycle.orElse(null);
        if (current != null) current.integrationUnavailable();
        reportState();
    }

    private void bindDelegate() {
        if (!delegate.available() || lifecycle.isEmpty() || delegateBoundGeneration == lifecycleGeneration) return;
        long boundGeneration = lifecycleGeneration;
        delegateBoundGeneration = boundGeneration;
        delegate.register(new Lifecycle() {
            @Override public void tagged(Player player, Location tagLocation) {
                Lifecycle current = lifecycle.orElse(null);
                if (!closed && lifecycleGeneration == boundGeneration && current != null)
                    current.tagged(player, tagLocation);
            }
            @Override public void untagged(Player player) {
                Lifecycle current = lifecycle.orElse(null);
                if (!closed && lifecycleGeneration == boundGeneration && current != null)
                    current.untagged(player);
            }
        });
    }

    private void reportState() {
        String state = delegate.available() ? "available" : "unavailable: " + delegate.unavailableReason();
        if (state.equals(lastReportedState)) return;
        lastReportedState = state;
        if (delegate.available()) owner.getLogger().info("CombatLogX integration is available.");
        else owner.getLogger().warning("CombatLogX integration is " + state
                + ". Unrelated MaceGuard features remain enabled.");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        lifecycleGeneration++;
        delegate.close();
        delegate = new UnavailableCombatLogXGateway("MaceGuard runtime is closed");
        delegateBoundGeneration = Long.MIN_VALUE;
        lifecycle = Optional.empty();
        if (listenerRegistered) HandlerList.unregisterAll(this);
        listenerRegistered = false;
    }

    long generation() { return lifecycleGeneration; }
    boolean lifecycleListenerRegistered() { return listenerRegistered; }
}
