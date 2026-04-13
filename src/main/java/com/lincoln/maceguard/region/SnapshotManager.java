package com.lincoln.maceguard.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Saves/loads zone snapshots of non-air blocks. */
public final class SnapshotManager {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static class Entry {
        public int x, y, z;
        public String type;
        public String data; // BlockData#getAsString()
    }

    public static void save(Zone z, File file) throws IOException {
        World w = Bukkit.getWorld(z.cuboid.worldName());
        if (w == null) throw new IOException("World not loaded: " + z.cuboid.worldName());
        List<Entry> out = new ArrayList<>();
        for (int x = z.cuboid.minX; x <= z.cuboid.maxX; x++) {
            for (int y = z.cuboid.minY; y <= z.cuboid.maxY; y++) {
                for (int zz = z.cuboid.minZ; zz <= z.cuboid.maxZ; zz++) {
                    Block b = w.getBlockAt(x, y, zz);
                    if (b.getType() == Material.AIR) continue;
                    Entry e = new Entry();
                    e.x = x; e.y = y; e.z = zz;
                    e.type = b.getType().name();
                    e.data = b.getBlockData().getAsString();
                    out.add(e);
                }
            }
        }
        file.getParentFile().mkdirs();
        try (var fos = new FileOutputStream(file);
             var gz = new GZIPOutputStream(fos);
             var wtr = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
            GSON.toJson(out, wtr);
        }
    }

    public static Map<Long, Entry> load(File file) throws IOException {
        Map<Long, Entry> map = new HashMap<>();
        if (!file.exists()) return map;
        try (var fis = new FileInputStream(file);
             var gz = new GZIPInputStream(fis);
             var rdr = new InputStreamReader(gz, StandardCharsets.UTF_8)) {
            Entry[] arr = GSON.fromJson(rdr, Entry[].class);
            if (arr != null) {
                for (Entry e : arr) {
                    map.put(key(e.x, e.y, e.z), e);
                }
            }
        }
        return map;
    }

    public static long key(int x, int y, int z) {
        return (((long) (x & 0x3FFFFF)) << 42) | (((long) (y & 0x3FF)) << 32) | (long) (z & 0x3FFFFF);
    }
}
