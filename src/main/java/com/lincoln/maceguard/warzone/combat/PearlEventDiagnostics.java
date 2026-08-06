package com.lincoln.maceguard.warzone.combat;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Disabled-by-default, bounded staging trace for pearl event ordering. */
public final class PearlEventDiagnostics {
    private static final int MAX_SESSIONS = 10;
    private static final int MAX_LINES = 128;
    private static final long SESSION_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final Object INSTANCES_LOCK = new Object();
    // Weak keys are intentional so diagnostics cannot retain a disabled plugin instance.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static final Map<JavaPlugin, PearlEventDiagnostics> INSTANCES =
            new java.util.WeakHashMap<>();

    private final JavaPlugin plugin;
    private final Object lock = new Object();
    // Guarded by lock; HashMap keeps the bounded session state private and deterministic.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, TraceSession> sessions = new HashMap<>();

    private PearlEventDiagnostics(JavaPlugin plugin) { this.plugin = plugin; }

    public static PearlEventDiagnostics forPlugin(JavaPlugin plugin) {
        synchronized (INSTANCES_LOCK) {
            return INSTANCES.computeIfAbsent(plugin, PearlEventDiagnostics::new);
        }
    }

    public boolean enable(Player target, CommandSender observer) {
        synchronized (lock) {
            cleanupLocked();
            if (!sessions.containsKey(target.getUniqueId()) && sessions.size() >= MAX_SESSIONS)
                return false;
            sessions.put(target.getUniqueId(), new TraceSession(observerName(observer),
                    System.currentTimeMillis() + SESSION_MILLIS, new ArrayDeque<>()));
            return true;
        }
    }

    public boolean disable(UUID targetId) {
        synchronized (lock) {
            return sessions.remove(targetId) != null;
        }
    }

    public java.util.List<String> lines(UUID targetId) {
        synchronized (lock) {
            cleanupLocked();
            TraceSession session = sessions.get(targetId);
            return session == null ? java.util.List.of() : java.util.List.copyOf(session.lines());
        }
    }

    /** The detail supplier is evaluated only while a trace session is active. */
    public void record(UUID ownerId, String stage, Supplier<String> detail) {
        synchronized (lock) {
            cleanupLocked();
            TraceSession session = sessions.get(ownerId);
            if (session == null) return;
            String line = "[PearlTrace] " + stage + " " + detail.get();
            if (session.lines().size() >= MAX_LINES) session.lines().removeFirst();
            session.lines().addLast(line);
            CommandSender observer = observer(session.observerName());
            if (observer != null) observer.sendMessage(line);
        }
    }

    public void clear() {
        synchronized (lock) {
            sessions.clear();
        }
    }

    private void cleanupLocked() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private String observerName(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
    }

    private CommandSender observer(String name) {
        if ("CONSOLE".equals(name)) return plugin.getServer().getConsoleSender();
        try { return plugin.getServer().getPlayer(UUID.fromString(name)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private record TraceSession(String observerName, long expiresAtMillis,
                                ArrayDeque<String> lines) { }
}
