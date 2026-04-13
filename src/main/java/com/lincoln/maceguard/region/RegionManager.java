package com.lincoln.maceguard.region;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

public final class RegionManager {
    private final MaceGuardPlugin plugin;
    private final List<Cuboid> zones = new ArrayList<>();
    private final Set<String> allowedWorlds = new HashSet<>(); // empty => all worlds

    public RegionManager(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadFromConfig() {
        zones.clear();
        allowedWorlds.clear();

        List<String> worlds = plugin.getConfig().getStringList("allowed_worlds");
        if (worlds != null) {
            for (String s : worlds) if (s != null && !s.isBlank()) allowedWorlds.add(s.trim());
        }

        List<Map<?, ?>> zoneMaps = plugin.getConfig().getMapList("zones");
        for (Map<?, ?> m : zoneMaps) {
            String name  = getString(m, "name", "unnamed");
            String world = getString(m, "world", "world");

            Map<String, Object> min = asSection(m.get("min"));
            Map<String, Object> max = asSection(m.get("max"));
            if (min == null || max == null) continue;

            int minX = toInt(min.get("x"));
            int minY = toInt(min.get("y"));
            int minZ = toInt(min.get("z"));
            int maxX = toInt(max.get("x"));
            int maxY = toInt(max.get("y"));
            int maxZ = toInt(max.get("z"));

            zones.add(new Cuboid(name, world, minX, minY, minZ, maxX, maxY, maxZ));
        }

        if (plugin.isDebug()) {
            plugin.getLogger().info("Loaded " + zones.size() + " zones. AllowedWorlds=" + (allowedWorlds.isEmpty() ? "ALL" : allowedWorlds));
            zones.forEach(z -> plugin.getLogger().info(" - " + z));
        }
    }

    // ---- helpers ----

    /** Safely coerce a map value to String with a default (avoids getOrDefault wildcard issues). */
    private String getString(Map<?, ?> map, String key, String def) {
        Object o = map.get(key);
        return (o == null) ? def : String.valueOf(o);
    }

    /** Convert nested object to a String->Object map (returns null if not a map). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asSection(Object o) {
        if (!(o instanceof Map<?, ?> mm)) return null;
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : mm.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    public boolean isWorldAllowed(World world) {
        if (world == null) return false;
        if (allowedWorlds.isEmpty()) return true;
        return allowedWorlds.contains(world.getName());
    }

    public boolean isInAnyZone(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!isWorldAllowed(loc.getWorld())) return false;
        for (Cuboid c : zones) if (c.contains(loc)) return true;
        return false;
    }

    public List<Cuboid> getZones() { return zones; }
}
