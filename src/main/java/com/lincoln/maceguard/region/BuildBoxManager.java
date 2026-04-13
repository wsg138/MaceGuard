package com.lincoln.maceguard.region;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BuildBoxManager {
    private final MaceGuardPlugin plugin;
    private final List<Cuboid> boxes = new ArrayList<>();
    private boolean enabled;

    public BuildBoxManager(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadFromConfig() {
        boxes.clear();
        enabled = plugin.getConfig().getBoolean("build_boxes.enabled", true);

        List<Map<?, ?>> list = plugin.getConfig().getMapList("build_boxes.cuboids");
        for (Map<?, ?> m : list) {
            String name = str(m.get("name"), "unnamed");
            String world = str(m.get("world"), "world");

            Map<?, ?> min = (Map<?, ?>) m.get("min");
            Map<?, ?> max = (Map<?, ?>) m.get("max");
            if (min == null || max == null) continue;

            int minX = asInt(min.get("x"));
            int minY = asInt(min.get("y"));
            int minZ = asInt(min.get("z"));
            int maxX = asInt(max.get("x"));
            int maxY = asInt(max.get("y"));
            int maxZ = asInt(max.get("z"));

            boxes.add(new Cuboid(name, world, minX, minY, minZ, maxX, maxY, maxZ));
        }

        if (plugin.isDebug()) {
            plugin.getLogger().info("[Debug] Loaded " + boxes.size() + " build boxes.");
            for (Cuboid c : boxes) plugin.getLogger().info(" - " + c);
        }
    }

    private String str(Object o, String def) { return (o == null) ? def : String.valueOf(o); }
    private int asInt(Object o) { return (o instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(o)); }

    public boolean isEnabled() { return enabled; }

    public boolean isInAny(Location loc) {
        if (!enabled || loc == null || loc.getWorld() == null) return false;
        for (Cuboid c : boxes) {
            if (c.contains(loc)) return true;
        }
        return false;
    }

    public List<Cuboid> getBoxes() { return boxes; }
}
