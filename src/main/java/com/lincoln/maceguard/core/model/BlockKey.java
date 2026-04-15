package com.lincoln.maceguard.core.model;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.Objects;

public record BlockKey(String worldName, int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockKey of(Location location) {
        Objects.requireNonNull(location.getWorld(), "location.world");
        return new BlockKey(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public long packed() {
        return pack(x, y, z);
    }

    public static long pack(int x, int y, int z) {
        long px = ((long) x & 0x3FFFFFFL) << 38;
        long pz = (long) z & 0x3FFFFFFL;
        long py = ((long) y & 0xFFFL) << 26;
        return px | py | pz;
    }
}
