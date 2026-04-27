package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DuelArenaFootprintService {
    private final MaceGuardPlugin plugin;

    private boolean enabled;
    private String worldName;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    private Set<Long> exactBlocks = Set.of();

    public DuelArenaFootprintService(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = false;
        worldName = null;
        exactBlocks = Set.of();

        if (!plugin.getConfig().getBoolean("duel_arena_footprint.enabled", true)) {
            return;
        }

        String configuredPath = plugin.getConfig().getString("duel_arena_footprint.source_file", "duel-arena-footprint.yml").trim();
        File file = resolveSourceFile(configuredPath);
        if (!file.isFile()) {
            plugin.getLogger().warning("Duel arena footprint file was not found: " + file.getAbsolutePath());
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String loadedWorld = yaml.getString("world", "").trim();
        if (loadedWorld.isBlank()) {
            plugin.getLogger().warning("Duel arena footprint file is missing a world name: " + file.getAbsolutePath());
            return;
        }

        List<String> absoluteBlocks = yaml.getStringList("absolute_blocks");
        if (absoluteBlocks.isEmpty()) {
            plugin.getLogger().warning("Duel arena footprint file has no absolute_blocks: " + file.getAbsolutePath());
            return;
        }

        this.worldName = loadedWorld;
        this.minX = yaml.getInt("bounds.min.x");
        this.minY = yaml.getInt("bounds.min.y");
        this.minZ = yaml.getInt("bounds.min.z");
        this.maxX = yaml.getInt("bounds.max.x");
        this.maxY = yaml.getInt("bounds.max.y");
        this.maxZ = yaml.getInt("bounds.max.z");

        Set<Long> loadedBlocks = new HashSet<>(Math.max(absoluteBlocks.size() * 2, 16));
        for (String raw : absoluteBlocks) {
            String[] parts = raw.split(",");
            if (parts.length != 3) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                loadedBlocks.add(pack(x, y, z));
            } catch (NumberFormatException ignored) {
            }
        }

        if (loadedBlocks.isEmpty()) {
            plugin.getLogger().warning("Duel arena footprint file parsed zero exact blocks: " + file.getAbsolutePath());
            return;
        }

        this.exactBlocks = Set.copyOf(loadedBlocks);
        this.enabled = true;
        plugin.getLogger().info("Loaded duel arena footprint for world " + worldName + " with " + exactBlocks.size() + " exact blocks.");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInArenaWorld(World world) {
        return enabled && world != null && world.getName().equalsIgnoreCase(worldName);
    }

    public boolean isWithinBounds(Location location) {
        if (!enabled || location == null || location.getWorld() == null || !location.getWorld().getName().equalsIgnoreCase(worldName)) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    public boolean contains(Block block) {
        return block != null && contains(block.getLocation());
    }

    public boolean contains(Location location) {
        if (!isWithinBounds(location)) {
            return false;
        }
        return exactBlocks.contains(pack(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public boolean contains(String world, int x, int y, int z) {
        if (!enabled || world == null || !world.equalsIgnoreCase(worldName)) {
            return false;
        }
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return false;
        }
        return exactBlocks.contains(pack(x, y, z));
    }

    public boolean maybeRelevant(Location location) {
        return isWithinBounds(location);
    }

    private File resolveSourceFile(String configuredPath) {
        File file = new File(configuredPath);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(plugin.getDataFolder(), configuredPath.replace("/", File.separator).replace("\\", File.separator));
    }

    private long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
            | ((long) (z & 0x3FFFFFF) << 12)
            | (long) (y & 0xFFF);
    }
}
