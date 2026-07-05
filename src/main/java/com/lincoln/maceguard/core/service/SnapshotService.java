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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class SnapshotService {
    private static final int CAPTURE_BLOCKS_PER_TICK = 4096;
    private static final Set<String> DEFERRED_SUFFIXES = Set.of(
            "_BUTTON",
            "_SIGN",
            "_HANGING_SIGN",
            "_BANNER",
            "_TORCH",
            "_WALL_TORCH",
            "_CORAL",
            "_CORAL_FAN",
            "_CORAL_WALL_FAN",
            "_SAPLING",
            "_DOOR",
            "_TRAPDOOR",
            "_PRESSURE_PLATE",
            "_CARPET",
            "_RAIL",
            "_BED",
            "_CANDLE",
            "_CANDLE_CAKE",
            "_HEAD",
            "_SKULL",
            "_POT",
            "_PLANT",
            "_ROOTS",
            "_VINES",
            "_STEM",
            "_FUNGUS",
            "_MUSHROOM",
            "_FLOWER",
            "_PETALS"
    );
    private static final Set<String> DEFERRED_EXACT_NAMES = Set.of(
            "VINE",
            "LILY_PAD",
            "SEAGRASS",
            "TALL_SEAGRASS",
            "GRASS",
            "SHORT_GRASS",
            "TALL_GRASS",
            "FERN",
            "LARGE_FERN",
            "DEAD_BUSH",
            "SUGAR_CANE",
            "BAMBOO",
            "CACTUS",
            "KELP",
            "KELP_PLANT",
            "WHEAT",
            "CARROTS",
            "POTATOES",
            "BEETROOTS",
            "NETHER_WART",
            "COCOA",
            "LADDER",
            "LEVER",
            "TRIPWIRE",
            "TRIPWIRE_HOOK",
            "REDSTONE_WIRE",
            "REPEATER",
            "COMPARATOR",
            "LANTERN",
            "SOUL_LANTERN",
            "END_ROD",
            "BELL",
            "AMETHYST_CLUSTER",
            "LARGE_AMETHYST_BUD",
            "MEDIUM_AMETHYST_BUD",
            "SMALL_AMETHYST_BUD"
    );

    private final Plugin plugin;
    private final Logger logger;
    private final FileSnapshotRepository repository;
    private final ExecutorService ioExecutor;
    private final Map<String, SnapshotData> snapshotsByZone = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> activeCaptures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> deferredBlockDataCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loadingByZone = new ConcurrentHashMap<>();
    private final PerformanceCounters counters;

    public SnapshotService(Plugin plugin, Logger logger, FileSnapshotRepository repository, ExecutorService ioExecutor, PerformanceCounters counters) {
        this.plugin = plugin;
        this.logger = logger;
        this.repository = repository;
        this.ioExecutor = ioExecutor;
        this.counters = counters;
    }

    public void loadAll(Iterable<GameplayZone> zones) {
        for (GameplayZone zone : zones) {
            if (zone.resetMode() != com.lincoln.maceguard.core.model.ResetMode.SNAPSHOT) {
                continue;
            }
            loadingByZone.put(zone.name(), true);
            ioExecutor.execute(() -> loadOne(zone.name()));
        }
    }

    private void loadOne(String zoneName) {
        long started = System.currentTimeMillis();
        boolean success = false;
        try {
            Optional<SnapshotData> snapshot = repository.load(zoneName);
            snapshot.ifPresent(data -> snapshotsByZone.put(zoneName, data));
            success = true;
            if (logger.isLoggable(Level.INFO)) {
                logger.info("Loaded snapshot for zone " + zoneName + " in " + (System.currentTimeMillis() - started) + "ms.");
            }
        } catch (IOException ex) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.warning("Failed to load snapshot for zone " + zoneName + ": " + ex.getMessage());
            }
        } finally {
            loadingByZone.remove(zoneName);
            counters.snapshotLoad(System.currentTimeMillis() - started, success);
        }
    }

    public boolean hasUsableSnapshot(String zoneName) {
        SnapshotData data = snapshotsByZone.get(zoneName);
        return data != null && data.isUsable();
    }

    public boolean isSnapshotLoading(String zoneName) {
        return loadingByZone.containsKey(zoneName);
    }

    public Set<String> loadingZones() {
        return Set.copyOf(loadingByZone.keySet());
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
                            if (processed >= CAPTURE_BLOCKS_PER_TICK) {
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
                    long started = System.currentTimeMillis();
                    boolean success = false;
                    Map<Long, String> blocks = new ConcurrentHashMap<>(entries.size());
                    for (SnapshotBlock entry : entries) {
                        blocks.put(BlockKey.pack(entry.x(), entry.y(), entry.z()), entry.blockData());
                    }
                    SnapshotData data = new SnapshotData(zoneName, region.worldName(), region, blocks, entries);

                    try {
                        repository.save(data);
                        snapshotsByZone.put(zoneName, data);
                        success = true;
                        long duration = System.currentTimeMillis() - started;
                        if (logger.isLoggable(Level.INFO)) {
                            logger.info("Saved snapshot for zone " + zoneName + " in " + duration + "ms.");
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> feedback.accept("\u00A7aSnapshot saved for zone \u00A7f" + zoneName + "\u00A7a."));
                    } catch (IOException ex) {
                        if (logger.isLoggable(Level.WARNING)) {
                            logger.warning("Failed to save snapshot for zone " + zoneName + ": " + ex.getMessage());
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> feedback.accept("\u00A7cFailed to save snapshot for \u00A7f" + zoneName + "\u00A7c. Check console."));
                    } finally {
                        counters.snapshotSave(System.currentTimeMillis() - started, success);
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
        return name.startsWith("POTTED_") || DEFERRED_EXACT_NAMES.contains(name) || hasDeferredSuffix(name);
    }

    private boolean hasDeferredSuffix(String materialName) {
        for (String suffix : DEFERRED_SUFFIXES) {
            if (materialName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
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

    public void shutdownExecutorGracefully() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.warning("Timed out waiting for snapshot IO to finish; requesting shutdown.");
                }
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }
}
