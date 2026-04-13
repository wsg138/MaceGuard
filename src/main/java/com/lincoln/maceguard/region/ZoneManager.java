package com.lincoln.maceguard.region;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public final class ZoneManager {
    private final MaceGuardPlugin plugin;
    private final List<Zone> zones = new ArrayList<>();
    private final Map<String, Map<String,String>> messages = new HashMap<>();
    private boolean zoneMessagesEnabled = true;

    // reset bookkeeping
    private final Map<String, Long> lastResetMs = new HashMap<>();
    private final Map<String, Set<Integer>> firedWarnings = new HashMap<>();

    // player membership cache for messages
    private final Map<UUID, Set<String>> playerZones = new HashMap<>();

    public ZoneManager(MaceGuardPlugin plugin) {
        this.plugin = plugin;

        // Entry/exit notifier
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
                if (e.getFrom().getBlockX() == e.getTo().getBlockX() &&
                        e.getFrom().getBlockY() == e.getTo().getBlockY() &&
                        e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
                Player p = e.getPlayer();
                var now = namesAt(p.getLocation());
                var prev = playerZones.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
                for (String z : new HashSet<>(prev)) {
                    if (!now.contains(z)) {
                        prev.remove(z);
                        sendIf(z, "exit", p);
                    }
                }
                for (String z : now) {
                    if (!prev.contains(z)) {
                        prev.add(z);
                        sendIf(z, "enter", p);
                    }
                }
            }
        }, plugin);
    }

    public void loadFromConfig() {
        zones.clear();
        messages.clear();
        lastResetMs.clear();
        firedWarnings.clear();
        zoneMessagesEnabled = plugin.getConfig().getBoolean("zone_messages_enabled", true);

        var list = plugin.getConfig().getMapList("gameplay_zones");
        for (Map<?, ?> m : list) {
            String name  = s(m.get("name"), "unnamed");
            String world = s(m.get("world"), "world");
            var min = (Map<?, ?>) m.get("min");
            var max = (Map<?, ?>) m.get("max");
            if (min == null || max == null) continue;

            var cuboid = new Cuboid(name, world,
                    i(min.get("x")), i(min.get("y")), i(min.get("z")),
                    i(max.get("x")), i(max.get("y")), i(max.get("z")));

            boolean allowAllPlace = b(m.get("allow_all_place"), false);
            boolean allowAllBreak = b(m.get("allow_all_break"), false);
            Set<String> allowedPlace = toSet(m.get("allowed_place"));
            Set<String> denyPlace    = toSet(m.get("deny_place"));
            boolean confineLiquids       = b(m.get("confine_liquids"), false);
            boolean blockInfiniteSources = b(m.get("block_infinite_sources"), true);
            int ttlSeconds       = i(m.get("ttl_seconds"));
            int fullResetMinutes = i(m.get("full_reset_minutes"));

            Zone.ResetMode resetMode = "SNAPSHOT".equalsIgnoreCase(s(m.get("reset_mode"), "AIR"))
                    ? Zone.ResetMode.SNAPSHOT : Zone.ResetMode.AIR;

            Zone.ResetScope resetScope = "FULL".equalsIgnoreCase(s(m.get("reset_scope"), "CHANGED"))
                    ? Zone.ResetScope.FULL : Zone.ResetScope.CHANGED;

            List<Integer> warnList = new ArrayList<>();
            Object warn = m.get("warn_before_seconds");
            if (warn instanceof Collection<?>) {
                for (Object o : (Collection<?>) warn) warnList.add(i(o));
            }
            warnList.sort(Comparator.reverseOrder());

            int priority = i(orDefault(m.get("priority"), 0));

            zones.add(new Zone(name, cuboid, allowAllPlace, allowAllBreak, allowedPlace, denyPlace,
                    confineLiquids, blockInfiniteSources, ttlSeconds, fullResetMinutes,
                    resetMode, resetScope, warnList, priority));

            if (fullResetMinutes > 0) {
                lastResetMs.put(name, System.currentTimeMillis());
                firedWarnings.put(name, new HashSet<Integer>());
            }

            if (resetMode == Zone.ResetMode.SNAPSHOT) {
                plugin.snapshots().load(name);
            }
        }

        ConfigurationSection msgRoot = plugin.getConfig().getConfigurationSection("zone_messages");
        if (msgRoot != null) {
            for (String z : msgRoot.getKeys(false)) {
                Map<String,String> map = new HashMap<>();
                ConfigurationSection zs = msgRoot.getConfigurationSection(z);
                if (zs != null) {
                    if (zs.contains("enter")) map.put("enter", zs.getString("enter"));
                    if (zs.contains("exit"))  map.put("exit",  zs.getString("exit"));
                }
                messages.put(z, map);
            }
        }
    }

    public List<Zone> getZones() { return zones; }

    public List<String> namesAt(Location loc) {
        return zonesAt(loc).stream().map(z -> z.name).collect(Collectors.toList());
    }

    /** Returns only the highest-priority zones at a location. */
    public List<Zone> zonesAt(Location loc) {
        List<Zone> hits = new ArrayList<>();
        for (Zone z : zones) if (z.cuboid.contains(loc)) hits.add(z);
        if (hits.isEmpty()) return hits;
        int maxPri = Integer.MIN_VALUE;
        for (Zone z : hits) if (z.priority > maxPri) maxPri = z.priority;
        List<Zone> top = new ArrayList<>();
        for (Zone z : hits) if (z.priority == maxPri) top.add(z);
        return top;
    }

    private void sendIf(String zone, String kind, Player p) {
        if (!zoneMessagesEnabled) return;
        Map<String, String> z = messages.get(zone);
        if (z == null) return;
        String msg = z.get(kind);
        if (msg != null) p.sendMessage(msg.replace('&', '§'));
    }

    // Called every 5 seconds by the plugin to drive warnings/resets
    public void tick5s(com.lincoln.maceguard.listener.ZoneGuardListener guard) {
        long now = System.currentTimeMillis();
        for (Zone z : zones) {
            if (z.fullResetMinutes <= 0) continue;

            Long lastObj = lastResetMs.get(z.name);
            long last = (lastObj == null) ? now : lastObj.longValue();

            long periodMs = z.fullResetMinutes * 60_000L;
            long nextAt = last + periodMs;
            long remaining = Math.max(0L, (nextAt - now) / 1000L); // seconds

            // fire warnings
            Set<Integer> fired = firedWarnings.get(z.name);
            if (fired == null) {
                fired = new HashSet<>();
                firedWarnings.put(z.name, fired);
            }
            for (int s : z.warnBeforeSeconds) {
                if (remaining == s && !fired.contains(s)) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (z.cuboid.contains(p.getLocation())) {
                            p.sendMessage("§e[" + z.name + "] Reset in §c" + s + "§es.");
                        }
                    }
                    fired.add(s);
                }
            }

            // reset time
            if (remaining == 0L) {
                lastResetMs.put(z.name, now);
                fired.clear();
                guard.resetZone(z);
            }
        }
    }

    // helpers
    private static String s(Object o, String def) { return o == null ? def : String.valueOf(o); }
    private static int i(Object o) { return (o instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(o)); }
    private static boolean b(Object o, boolean def) { return (o instanceof Boolean bb) ? bb : (o == null ? def : Boolean.parseBoolean(String.valueOf(o))); }
    private static Object orDefault(Object o, Object def) { return (o == null) ? def : o; }

    @SuppressWarnings("unchecked")
    private static Set<String> toSet(Object o) {
        if (o instanceof Collection<?>) {
            Set<String> s = new HashSet<>();
            for (Object e : (Collection<?>) o) s.add(String.valueOf(e));
            return s;
        }
        return new HashSet<>();
    }
}
