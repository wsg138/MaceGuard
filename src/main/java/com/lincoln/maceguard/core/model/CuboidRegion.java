package com.lincoln.maceguard.core.model;

import org.bukkit.Location;

public record CuboidRegion(
        String name,
        String worldName,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {

    public CuboidRegion {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName cannot be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
    }

    public static CuboidRegion of(String name, String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new CuboidRegion(
                name,
                worldName,
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.max(y1, y2),
                Math.max(z1, z2)
        );
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        return contains(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean contains(String world, int x, int y, int z) {
        return worldName.equals(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public int minChunkX() {
        return floorChunk(minX);
    }

    public int maxChunkX() {
        return floorChunk(maxX);
    }

    public int minChunkZ() {
        return floorChunk(minZ);
    }

    public int maxChunkZ() {
        return floorChunk(maxZ);
    }

    private static int floorChunk(int block) {
        return Math.floorDiv(block, 16);
    }
}
