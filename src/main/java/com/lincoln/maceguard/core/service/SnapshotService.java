package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.adapter.storage.FileSnapshotRepository;
import com.lincoln.maceguard.core.model.BlockKey;
import com.lincoln.maceguard.core.model.CuboidRegion;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.SnapshotBlock;
import com.lincoln.maceguard.core.model.SnapshotData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class SnapshotService {
    private final Plugin plugin;
    private final Logger logger;
    private final FileSnapshotRepository repository;
    private final ExecutorService ioExecutor;
    private final Map<String, SnapshotData> snapshotsByZone = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> activeCaptures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> deferredBlockDataCache = new ConcurrentHashMap<>();

    public SnapshotService(Plugin plugin, Logger logger, FileSnapshotRepository repository, ExecutorService ioExecutor) {
        this.plugin = plugin;
        this.logger = logger;
        this.repository = repository;
        this.ioExecutor = ioExecutor;
    }

    public void loadAll(Iterable<GameplayZone> zones) {
        for (GameplayZone zone : zones) {
            if (zone.resetMode() != com.lincoln.maceguard.core.model.ResetMode.SNAPSHOT) {
                continue;
            }
            try {
                Optional<SnapshotData> snapshot = repository.load(zone.name());
                snapshot.ifPresent(data -> snapshotsByZone.put(zone.name(), data));
            } catch (IOException ex) {
                logger.warning("Failed to load snapshot for zone " + zone.name() + ": " + ex.getMessage());
            }
        }
    }

    public boolean hasUsableSnapshot(String zoneName) {
        SnapshotData data = snapshotsByZone.get(zoneName);
        return data != null && data.isUsable();
    }

    public void capture(String zoneName, CuboidRegion region, Consumer<String> feedback) {
        BukkitTask previous = activeCaptures.remove(zoneName);
        if (previous != null) {
            previous.cancel();
        }

        World world = Bukkit.getWorld(region.worldName());
        if (world == null) {
            feedback.accept("\u00A7cWorld is not loaded: \u00A7f" + region.worldName());
            return;
        }

        List<SnapshotBlock> entries = new ArrayList<>();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int x = region.minX();
            private int y = region.minY();
            private int z = region.minZ();

            @Override
            public void run() {
                int processed = 0;
                while (x <= region.maxX()) {
                    while (z <= region.maxZ()) {
                        while (y <= region.maxY()) {
                            Block block = world.getBlockAt(x, y, z);
                            if (block.getType() != Material.AIR) {
                                entries.add(new SnapshotBlock(x, y, z, block.getBlockData().getAsString(true)));
                            }
                            y++;
                            processed++;
                            if (processed >= 4096) {
                                return;
                            }
                        }
                        y = region.minY();
                        z++;
                    }
                    z = region.minZ();
                    x++;
                }

                BukkitTask finishedTask = activeCaptures.remove(zoneName);
                if (finishedTask != null) {
                    finishedTask.cancel();
                }

                if (entries.isEmpty()) {
                    feedback.accept("\u00A7eSnapshot skipped for \u00A7f" + zoneName + "\u00A7e because the region is completely empty.");
                    return;
                }

                ioExecutor.execute(() -> {
                    Map<Long, String> blocks = new HashMap<>(entries.size());
                    for (SnapshotBlock entry : entries) {
                        blocks.put(BlockKey.pack(entry.x(), entry.y(), entry.z()), entry.blockData());
                    }
                    SnapshotData data = new SnapshotData(zoneName, region.worldName(), region, blocks, entries);
                    snapshotsByZone.put(zoneName, data);

                    try {
                        repository.save(data);
                        Bukkit.getScheduler().runTask(plugin, () -> feedback.accept("\u00A7aSnapshot saved for zone \u00A7f" + zoneName + "\u00A7a."));
                    } catch (IOException ex) {
                        logger.warning("Failed to save snapshot for zone " + zoneName + ": " + ex.getMessage());
                        Bukkit.getScheduler().runTask(plugin, () -> feedback.accept("\u00A7cFailed to save snapshot for \u00A7f" + zoneName + "\u00A7c. Check console."));
                    }
                });
            }
        }, 1L, 1L);

        activeCaptures.put(zoneName, task);
    }

    public int highestSnapshotY(String zoneName, String worldName, int x, int minY, int maxY, int z) {
        SnapshotData data = snapshotsByZone.get(zoneName);
        if (data == null || !data.worldName().equals(worldName) || !data.isUsable()) {
            return maxY;
        }
        for (int y = maxY; y >= minY; y--) {
            if (data.blocks().containsKey(BlockKey.pack(x, y, z))) {
                return y;
            }
        }
        return maxY;
    }

    public boolean applyAt(String zoneName, Block block) {
        return applyAt(zoneName, block, RestorePass.ALL);
    }

    public boolean applyStableAt(String zoneName, Block block) {
        return applyAt(zoneName, block, RestorePass.STABLE);
    }

    public boolean applyDeferredAt(String zoneName, Block block) {
        return applyAt(zoneName, block, RestorePass.DEFERRED);
    }

    private boolean applyAt(String zoneName, Block block, RestorePass pass) {
        SnapshotData data = snapshotsByZone.get(zoneName);
        if (data == null || !data.isUsable()) {
            return false;
        }
        String targetData = data.blocks().get(BlockKey.pack(block.getX(), block.getY(), block.getZ()));
        if (pass == RestorePass.STABLE && targetData != null && isDeferredBlockData(targetData)) {
            return false;
        }
        if (pass == RestorePass.DEFERRED && (targetData == null || !isDeferredBlockData(targetData))) {
            return false;
        }
        BlockData blockData = Bukkit.createBlockData(targetData == null ? Material.AIR.createBlockData().getAsString(true) : targetData);
        if (block.getBlockData().matches(blockData)) {
            return false;
        }
        block.setBlockData(blockData, false);
        return true;
    }

    private boolean isDeferredBlockData(String blockData) {
        return deferredBlockDataCache.computeIfAbsent(blockData, this::computeDeferredBlockData);
    }

    private boolean computeDeferredBlockData(String blockData) {
        Material material = materialFromBlockData(blockData);
        if (material == null) {
            return false;
        }
        if (material.hasGravity()) {
            return true;
        }
        String name = material.name();
        return name.endsWith("_BUTTON")
                || name.endsWith("_SIGN")
                || name.endsWith("_HANGING_SIGN")
                || name.endsWith("_BANNER")
                || name.endsWith("_TORCH")
                || name.endsWith("_WALL_TORCH")
                || name.endsWith("_CORAL")
                || name.endsWith("_CORAL_FAN")
                || name.endsWith("_CORAL_WALL_FAN")
                || name.endsWith("_SAPLING")
                || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_CARPET")
                || name.endsWith("_RAIL")
                || name.endsWith("_BED")
                || name.endsWith("_CANDLE")
                || name.endsWith("_CANDLE_CAKE")
                || name.endsWith("_HEAD")
                || name.endsWith("_SKULL")
                || name.endsWith("_POT")
                || name.endsWith("_PLANT")
                || name.endsWith("_ROOTS")
                || name.endsWith("_VINES")
                || name.endsWith("_STEM")
                || name.endsWith("_FUNGUS")
                || name.endsWith("_MUSHROOM")
                || name.endsWith("_FLOWER")
                || name.endsWith("_PETALS")
                || name.startsWith("POTTED_")
                || name.equals("VINE")
                || name.equals("LILY_PAD")
                || name.equals("SEAGRASS")
                || name.equals("TALL_SEAGRASS")
                || name.equals("GRASS")
                || name.equals("SHORT_GRASS")
                || name.equals("TALL_GRASS")
                || name.equals("FERN")
                || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH")
                || name.equals("SUGAR_CANE")
                || name.equals("BAMBOO")
                || name.equals("CACTUS")
                || name.equals("KELP")
                || name.equals("KELP_PLANT")
                || name.equals("WHEAT")
                || name.equals("CARROTS")
                || name.equals("POTATOES")
                || name.equals("BEETROOTS")
                || name.equals("NETHER_WART")
                || name.equals("COCOA")
                || name.equals("LADDER")
                || name.equals("LEVER")
                || name.equals("TRIPWIRE")
                || name.equals("TRIPWIRE_HOOK")
                || name.equals("REDSTONE_WIRE")
                || name.equals("REPEATER")
                || name.equals("COMPARATOR")
                || name.equals("LANTERN")
                || name.equals("SOUL_LANTERN")
                || name.equals("END_ROD")
                || name.equals("BELL")
                || name.equals("AMETHYST_CLUSTER")
                || name.equals("LARGE_AMETHYST_BUD")
                || name.equals("MEDIUM_AMETHYST_BUD")
                || name.equals("SMALL_AMETHYST_BUD");
    }

    private Material materialFromBlockData(String blockData) {
        int start = blockData.indexOf(':');
        int end = blockData.indexOf('[');
        if (end < 0) {
            end = blockData.length();
        }
        String key = blockData.substring(start >= 0 ? start + 1 : 0, end)
                .replace('.', '_')
                .toUpperCase(java.util.Locale.ROOT);
        return Material.matchMaterial(key);
    }

    private enum RestorePass {
        ALL,
        STABLE,
        DEFERRED
    }

    public void cancelAll() {
        for (BukkitTask task : activeCaptures.values()) {
            task.cancel();
        }
        activeCaptures.clear();
    }
}
