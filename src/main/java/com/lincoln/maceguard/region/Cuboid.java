package com.lincoln.maceguard.region;

import org.bukkit.Location;
import org.bukkit.World;

public final class Cuboid {
    private final String worldName;
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;
    private final String name;

    public Cuboid(String name, String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.worldName = worldName;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        if (!w.getName().equals(worldName)) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public String name() { return name; }
    public String worldName() { return worldName; }

    @Override
    public String toString() {
        return "Cuboid{name=" + name + ", world=" + worldName + ", [" +
                minX + "," + minY + "," + minZ + "] -> [" +
                maxX + "," + maxY + "," + maxZ + "]}";
    }
}
