package com.lincoln.maceguard.snapshot;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.region.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class SnapshotStore {
    private final MaceGuardPlugin plugin;

    // zoneName -> (BlockKey -> BlockDataString)
    private final Map<String, Map<BlockKey, String>> snapshots = new HashMap<>();

    public SnapshotStore(MaceGuardPlugin plugin) {
        this.plugin = plugin;
        new File(plugin.getDataFolder(), "snapshots").mkdirs();
    }

    public record BlockKey(String world, int x, int y, int z) {}

    public void put(String zoneName, Block b) {
        snapshots.computeIfAbsent(zoneName, k -> new HashMap<>())
                .put(new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()),
                        b.getBlockData().getAsString(true));
    }

    public String get(String zoneName, String world, int x, int y, int z) {
        Map<BlockKey,String> m = snapshots.get(zoneName);
        if (m == null) return null;
        return m.get(new BlockKey(world, x, y, z));
    }

    public void clearZone(String zoneName) {
        snapshots.remove(zoneName);
        File f = file(zoneName);
        if (f.exists()) f.delete();
    }

    public void load(String zoneName) {
        File f = file(zoneName);
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<BlockKey,String> map = new HashMap<>();
        for (String key : y.getKeys(false)) {
            String[] parts = key.split(",");
            if (parts.length != 4) continue;
            map.put(new BlockKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])),
                    y.getString(key));
        }
        snapshots.put(zoneName, map);
    }

    public void save(String zoneName) {
        Map<BlockKey,String> map = snapshots.get(zoneName);
        if (map == null) return;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : map.entrySet()) {
            BlockKey k = e.getKey();
            y.set(k.world + "," + k.x + "," + k.y + "," + k.z, e.getValue());
        }
        try { y.save(file(zoneName)); } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save snapshot " + zoneName + ": " + ex.getMessage());
        }
    }

    public void captureAsync(String zoneName, Cuboid c) {
        clearZone(zoneName);
        World w = Bukkit.getWorld(c.worldName());
        if (w == null) return;

        final int batchXY = 32;  // batch slices per tick
        final int minX = c.minX, maxX = c.maxX, minY = c.minY, maxY = c.maxY, minZ = c.minZ, maxZ = c.maxZ;

        new BukkitRunnable() {
            int x = minX, y = minY, z = minZ;
            @Override public void run() {
                int count = 0;
                while (x <= maxX) {
                    while (z <= maxZ) {
                        Block b;
                        for (; y <= maxY; y++) {
                            b = w.getBlockAt(x, y, z);
                            put(zoneName, b); // save all blocks including air
                        }
                        y = minY; z++; count++;
                        if (count >= batchXY) return;
                    }
                    z = minZ; x++;
                }
                save(zoneName);
                if (plugin.isDebug()) plugin.getLogger().info("[Snapshot] Captured " + zoneName);
                cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private File file(String zoneName) {
        return new File(new File(plugin.getDataFolder(), "snapshots"), zoneName + ".yml");
    }

    /** Find highest snapshot non-air block at (x,z). */
    public int highestSnapshotY(String zoneName, String worldName, int x, int minY, int maxY, int z) {
        Map<BlockKey,String> map = snapshots.get(zoneName);
        if (map == null) return maxY;
        String air = Material.AIR.createBlockData().getAsString(true);
        for (int y = maxY; y >= minY; y--) {
            String s = map.get(new BlockKey(worldName, x, y, z));
            if (s != null && !s.startsWith(air)) {
                return y;
            }
        }
        return maxY;
    }

    /** Apply snapshot state to this position (returns true if changed). */
    public boolean applyAt(String zoneName, Block b) {
        Map<BlockKey,String> map = snapshots.get(zoneName);
        if (map == null) return false;
        String s = map.get(new BlockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        if (s == null) s = Material.AIR.createBlockData().getAsString(true);
        BlockData target = Bukkit.createBlockData(s);
        if (!b.getBlockData().matches(target)) {
            b.setBlockData(target, false);
            return true;
        }
        return false;
    }
}
