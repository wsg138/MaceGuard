package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.core.model.BlockKey;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.ResetMode;
import com.lincoln.maceguard.core.model.ResetScope;
import com.lincoln.maceguard.adapter.storage.SparseBaselineRepository;
import com.lincoln.maceguard.integration.WarzoneRotatorHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class ZoneStateService {
    private static final long RESET_DUE_SECONDS = 0L;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final SnapshotService snapshotService;
    private final SparseBaselineRepository sparseBaseline;
    private final WarzoneRotatorHook warzoneRotatorHook;
    private final int changedBatchSize;
    private final int fullRestoreBatchSize;
    private final int liquidDrainBatchSize;
    private final PerformanceCounters counters;
    private final Map<String, Set<BlockKey>> changedBlocksByZone = new ConcurrentHashMap<>();
    private final Map<BlockKey, BukkitTask> ttlTasks = new ConcurrentHashMap<>();
    private final Map<BlockKey, Long> ttlExpiresAt = new ConcurrentHashMap<>();
    private final Map<BlockKey, Set<String>> ttlZonesByBlock = new ConcurrentHashMap<>();
    private final Set<BlockKey> temporaryBlocks = ConcurrentHashMap.newKeySet();
    private final Set<BlockKey> placedBlocks = ConcurrentHashMap.newKeySet();
    private final Map<String, ZoneTaskHandle> activeZoneTasks = new ConcurrentHashMap<>();
    private final Map<String, Set<BukkitTask>> activeDrainTasksByZone = new ConcurrentHashMap<>();
    private final Map<String, Integer> drainQueueSizesByZone = new ConcurrentHashMap<>();
    private final Map<String, Long> lastResetAt = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> firedWarningsByZone = new ConcurrentHashMap<>();
    private final Map<String, Map<BlockKey, String>> sparseOriginalsByZone = new ConcurrentHashMap<>();

    public ZoneStateService(Plugin plugin, ZoneRegistry zoneRegistry, SnapshotService snapshotService, SparseBaselineRepository sparseBaseline, int changedBatchSize, int fullRestoreBatchSize, int liquidDrainBatchSize, PerformanceCounters counters, WarzoneRotatorHook warzoneRotatorHook) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.snapshotService = snapshotService;
        this.sparseBaseline = sparseBaseline;
        this.warzoneRotatorHook = warzoneRotatorHook;
        this.changedBatchSize = changedBatchSize;
        this.fullRestoreBatchSize = fullRestoreBatchSize;
        this.liquidDrainBatchSize = liquidDrainBatchSize;
        this.counters = counters;
        initializeResetState();
        loadSparseBaselines();
    }

    private void loadSparseBaselines() {
        for (GameplayZone zone : zoneRegistry.allGameplayZones()) {
            if (zone.resetMode() != ResetMode.SPARSE_SNAPSHOT) {
                continue;
            }
            try {
                sparseOriginalsByZone.put(zone.name(), new ConcurrentHashMap<>(sparseBaseline.load(zone.name())));
            } catch (java.io.IOException ex) {
                plugin.getLogger().warning("Cannot load sparse baseline for " + zone.name() + ": " + ex.getMessage());
                sparseOriginalsByZone.put(zone.name(), new ConcurrentHashMap<>());
            }
        }
    }

    private void initializeResetState() {
        long now = System.currentTimeMillis();
        for (GameplayZone zone : zoneRegistry.allGameplayZones()) {
            if (zone.externallyManaged()) {
                continue;
            }
            if (zone.fullResetMinutes() > 0) {
                lastResetAt.put(zone.name(), now);
                firedWarningsByZone.put(zone.name(), ConcurrentHashMap.newKeySet());
            }
            if (zone.weeklyReset().enabled()) {
                lastResetAt.put(zone.name(), sparseBaseline.loadLastReset(zone.name()));
            }
        }
    }

    public void onReloadCleanup() {
        cancelAllTasks();
    }

    public void onDisableCleanup() {
        cancelAllTasks();
        clearTemporaryBlocksImmediately();
        changedBlocksByZone.clear();
        lastResetAt.clear();
        firedWarningsByZone.clear();
    }

    public void clearTemporaryBlocksForReload() {
        clearTemporaryBlocksImmediately();
    }

    public void markChanged(String zoneName, Block block) {
        changedBlocksByZone.computeIfAbsent(zoneName, ignored -> ConcurrentHashMap.newKeySet()).add(BlockKey.of(block));
    }

    /** Records a block before it changes. Callers cancel the mutation when the durable write fails. */
    public boolean captureSparseOriginals(Collection<GameplayZone> zones, Block block) {
        return captureSparseOriginals(zones, BlockKey.of(block), block.getBlockData().getAsString(true));
    }

    public boolean captureSparseOriginals(Collection<GameplayZone> zones, BlockKey key, String original) {
        for (GameplayZone zone : zones) {
            if (zone.resetMode() != ResetMode.SPARSE_SNAPSHOT) {
                continue;
            }
            Map<BlockKey, String> entries = sparseOriginalsByZone.computeIfAbsent(zone.name(), ignored -> new ConcurrentHashMap<>());
            if (entries.containsKey(key)) {
                continue;
            }
            if (!sparseBaseline.appendOriginal(zone.name(), key, original)) {
                plugin.getLogger().warning("Sparse baseline write failed for " + zone.name() + " at " + key + "; mutation denied.");
                return false;
            }
            entries.putIfAbsent(key, original);
        }
        return true;
    }

    public boolean preservePermanentEdit(Collection<GameplayZone> zones, Block block) {
        for (GameplayZone zone : zones) {
            if (zone.resetMode() != ResetMode.SPARSE_SNAPSHOT) {
                continue;
            }
            BlockKey key = BlockKey.of(block);
            Map<BlockKey, String> entries = sparseOriginalsByZone.getOrDefault(zone.name(), Map.of());
            if (entries.containsKey(key)) {
                if (!sparseBaseline.appendDelete(zone.name(), key)) {
                    plugin.getLogger().warning("Sparse baseline delete failed for staff edit at " + key + "; mutation denied.");
                    return false;
                }
                entries.remove(key);
            }
        }
        return true;
    }

    public void markPlaced(Block block) {
        placedBlocks.add(BlockKey.of(block));
    }

    public boolean isPlaced(Block block) {
        return placedBlocks.contains(BlockKey.of(block));
    }

    public void forgetPlacedAfterDrops(Block block) {
        BlockKey key = BlockKey.of(block);
        Bukkit.getScheduler().runTask(plugin, () -> placedBlocks.remove(key));
    }

    public void scheduleTemporaryClear(Block block, int ttlSeconds, Collection<GameplayZone> applicableZones) {
        BlockKey key = BlockKey.of(block);
        BukkitTask previous = ttlTasks.remove(key);
        if (previous != null) {
            previous.cancel();
        }
        temporaryBlocks.add(key);
        ttlExpiresAt.put(key, System.currentTimeMillis() + ttlSeconds * 1000L);
        ttlZonesByBlock.put(key, zoneNames(applicableZones));

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ttlTasks.remove(key);
            ttlExpiresAt.remove(key);
            ttlZonesByBlock.remove(key);
            if (block.getType() == Material.WATER || block.getType() == Material.LAVA) {
                for (GameplayZone zone : applicableZones) {
                    if (zone.ttlSeconds() > 0) {
                        drainLiquidInZone(block, zone);
                    }
                }
            } else if (block.getType() != Material.AIR) {
                block.setType(Material.AIR, false);
            }
            temporaryBlocks.remove(key);
        }, ttlSeconds * 20L);
        ttlTasks.put(key, task);
    }

    public void markTemporary(Block block) {
        temporaryBlocks.add(BlockKey.of(block));
    }

    public int clearTracked(String zoneName) {
        if (zoneName != null) {
            return clearTrackedZone(zoneName);
        }
        int total = 0;
        for (String current : new ArrayList<>(changedBlocksByZone.keySet())) {
            total += clearTrackedZone(current);
        }
        return total;
    }

    public void resetZone(GameplayZone zone, Consumer<String> feedback) {
        ZoneTaskHandle existingTask = activeZoneTasks.get(zone.name());
        if (existingTask != null && existingTask.type() == ZoneTaskType.RESTORE) {
            feedback.accept("\u00A7eReset already in progress for \u00A7f" + zone.name() + "\u00A7e.");
            return;
        }

        if (zone.resetMode() == ResetMode.SNAPSHOT && snapshotService.isSnapshotLoading(zone.name())) {
            feedback.accept("\u00A7eSkipping reset for \u00A7f" + zone.name() + "\u00A7e because its snapshot is still loading.");
            return;
        }

        if (zone.resetMode() == ResetMode.SNAPSHOT && !snapshotService.hasUsableSnapshot(zone.name())) {
            feedback.accept("\u00A7eSkipping reset for \u00A7f" + zone.name() + "\u00A7e because no usable snapshot exists.");
            return;
        }

        cancelZoneMutations(zone);
        teleportPlayersOutOfSolid(zone);

        if (zone.resetMode() == ResetMode.AIR) {
            clearTrackedZone(zone.name());
            return;
        }

        if (zone.resetMode() == ResetMode.SPARSE_SNAPSHOT) {
            Map<BlockKey, String> originals = sparseOriginalsByZone.getOrDefault(zone.name(), Map.of());
            if (originals.isEmpty()) {
                persistWeeklyReset(zone);
                return;
            }
            List<Map.Entry<BlockKey, String>> work = new ArrayList<>(originals.entrySet());
            startZoneTask(zone.name(), ZoneTaskType.RESTORE, runSparseBatch(zone.name(), work, () -> {
                if (sparseBaseline.clear(zone.name())) {
                    sparseOriginalsByZone.remove(zone.name());
                    persistWeeklyReset(zone);
                    if (warzoneRotatorHook != null) {
                        warzoneRotatorHook.resetCompleted();
                    }
                } else {
                    feedback.accept("§cSparse baseline was restored but could not be compacted; it will be retried.");
                }
            }));
            return;
        }

        if (zone.resetScope() == ResetScope.CHANGED) {
            Set<BlockKey> changed = changedBlocksByZone.get(zone.name());
            if (changed == null || changed.isEmpty()) {
                return;
            }
            List<BlockKey> work = new ArrayList<>(changed);
            startZoneTask(zone.name(), ZoneTaskType.RESTORE, runBatch(zone.name(), work, changedBatchSize, key -> {
                World world = Bukkit.getWorld(key.worldName());
                if (world != null) {
                    snapshotService.applyAt(zone.name(), world.getBlockAt(key.x(), key.y(), key.z()));
                }
            }, () -> changedBlocksByZone.remove(zone.name())));
            return;
        }

        World world = Bukkit.getWorld(zone.region().worldName());
        if (world == null) {
            feedback.accept("\u00A7cCannot reset \u00A7f" + zone.name() + "\u00A7c because world \u00A7f" + zone.region().worldName() + "\u00A7c is not loaded.");
            return;
        }

        startZoneTask(zone.name(), ZoneTaskType.RESTORE, new BukkitRunnable() {
            private int x = zone.region().minX();
            private int y = zone.region().minY();
            private int z = zone.region().minZ();
            private boolean deferredPass;

            @Override
            public void run() {
                int processed = 0;
                while (x <= zone.region().maxX()) {
                    while (z <= zone.region().maxZ()) {
                        while (y <= zone.region().maxY()) {
                            Block block = world.getBlockAt(x, y, z);
                            if (deferredPass) {
                                snapshotService.applyDeferredAt(zone.name(), block);
                            } else {
                                snapshotService.applyStableAt(zone.name(), block);
                            }
                            y++;
                            processed++;
                            if (processed >= fullRestoreBatchSize) {
                                return;
                            }
                        }
                        y = zone.region().minY();
                        z++;
                    }
                    z = zone.region().minZ();
                    x++;
                }
                if (!deferredPass) {
                    deferredPass = true;
                    x = zone.region().minX();
                    y = zone.region().minY();
                    z = zone.region().minZ();
                    return;
                }
                changedBlocksByZone.remove(zone.name());
                clearZoneTask(zone.name(), this);
                cancel();
            }
        });
    }

    public void tickResets() {
        long now = System.currentTimeMillis();
        for (GameplayZone zone : zoneRegistry.allGameplayZones()) {
            if (zone.externallyManaged()) {
                continue;
            }
            if (zone.weeklyReset().enabled()) {
                tickWeeklyReset(zone, now);
                continue;
            }
            if (zone.fullResetMinutes() <= 0) {
                continue;
            }
            long last = lastResetAt.getOrDefault(zone.name(), now);
            long periodMillis = zone.fullResetMinutes() * 60_000L;
            long remainingSeconds = Math.max(0L, (last + periodMillis - now) / 1000L);

            Set<Integer> fired = firedWarningsByZone.computeIfAbsent(zone.name(), ignored -> ConcurrentHashMap.newKeySet());
            for (int warningSeconds : zone.warnBeforeSeconds()) {
                if (remainingSeconds == warningSeconds && fired.add(warningSeconds)) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (zone.region().contains(player.getLocation())) {
                            player.sendMessage("\u00A7e[" + zone.name() + "] Reset in \u00A7c" + warningSeconds + "\u00A7es.");
                        }
                    }
                }
            }

            if (remainingSeconds == RESET_DUE_SECONDS) {
                if (isRestoreRunning(zone.name())) {
                    continue;
                }
                lastResetAt.put(zone.name(), now);
                fired.clear();
                resetZone(zone, message -> {});
            }
        }
    }

    private void tickWeeklyReset(GameplayZone zone, long now) {
        ZonedDateTime current = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone.weeklyReset().zoneId());
        ZonedDateTime scheduled = current.with(TemporalAdjusters.previousOrSame(zone.weeklyReset().day())).with(zone.weeklyReset().time());
        long dueAt = scheduled.toInstant().toEpochMilli();
        long last = lastResetAt.getOrDefault(zone.name(), 0L);
        ZonedDateTime upcoming = current.with(TemporalAdjusters.nextOrSame(zone.weeklyReset().day())).with(zone.weeklyReset().time());
        if (!upcoming.isAfter(current)) {
            upcoming = upcoming.plusWeeks(1);
        }
        long remainingSeconds = Math.max(0L, (upcoming.toInstant().toEpochMilli() - now) / 1000L);
        Set<Integer> fired = firedWarningsByZone.computeIfAbsent(zone.name(), ignored -> ConcurrentHashMap.newKeySet());
        for (int warningSeconds : zone.warnBeforeSeconds()) {
            if (remainingSeconds == warningSeconds && fired.add(warningSeconds)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (zone.region().contains(player.getLocation())) {
                        player.sendMessage("§e[" + zone.name() + "] Reset in §c" + warningSeconds + "§es.");
                    }
                }
            }
        }
        if (now < dueAt || last >= dueAt || isRestoreRunning(zone.name())) {
            return;
        }
        lastResetAt.put(zone.name(), now);
        fired.clear();
        resetZone(zone, message -> plugin.getLogger().info(message));
    }

    private void persistWeeklyReset(GameplayZone zone) {
        if (zone.weeklyReset().enabled() && !sparseBaseline.saveLastReset(zone.name(), lastResetAt.getOrDefault(zone.name(), System.currentTimeMillis()))) {
            plugin.getLogger().warning("Could not persist weekly reset timestamp for " + zone.name() + "; recovery may run again after restart.");
        }
    }

    private int clearTrackedZone(String zoneName) {
        GameplayZone zone = zoneRegistry.findZone(zoneName);
        if (zone != null) {
            cancelZoneMutations(zone);
        }
        Set<BlockKey> changed = changedBlocksByZone.get(zoneName);
        if (changed == null || changed.isEmpty()) {
            return 0;
        }
        List<BlockKey> work = new ArrayList<>(changed);
        startZoneTask(zoneName, ZoneTaskType.CLEAR, runBatch(zoneName, work, changedBatchSize, key -> {
            World world = Bukkit.getWorld(key.worldName());
            if (world == null) {
                return;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.AIR) {
                block.setType(Material.AIR, false);
            }
        }, () -> changedBlocksByZone.remove(zoneName)));
        return work.size();
    }

    private void teleportPlayersOutOfSolid(GameplayZone zone) {
        World world = Bukkit.getWorld(zone.region().worldName());
        if (world == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            if (!zone.region().contains(location)) {
                continue;
            }
            int highestY = snapshotService.highestSnapshotY(zone.name(), world.getName(), location.getBlockX(), zone.region().minY(), zone.region().maxY(), location.getBlockZ());
            teleportToSnapshotSurface(player, world, location, highestY);
        }
    }

    private void teleportToSnapshotSurface(Player player, World world, Location location, int highestY) {
        Location target = new Location(
                world,
                location.getBlockX() + 0.5D,
                highestY + 1.1D,
                location.getBlockZ() + 0.5D,
                location.getYaw(),
                location.getPitch()
        );
        player.teleport(target);
    }

    private void drainLiquidInZone(Block start, GameplayZone zone) {
        if (start.getType() != Material.WATER && start.getType() != Material.LAVA) {
            return;
        }
        Set<BlockKey> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(BlockKey.of(start));

        startZoneTask(zone.name(), ZoneTaskType.DRAIN, new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                counters.drainQueueSize(queue.size());
                while (!queue.isEmpty() && processed < liquidDrainBatchSize) {
                    Block block = queue.poll();
                    processed++;
                    if (block == null) {
                        continue;
                    }
                    if (block.getType() == Material.WATER || block.getType() == Material.LAVA) {
                        block.setType(Material.AIR, false);
                        temporaryBlocks.remove(BlockKey.of(block));
                        markChanged(zone.name(), block);
                    }
                    enqueueLiquidNeighbor(block.getRelative(1, 0, 0), zone, visited, queue);
                    enqueueLiquidNeighbor(block.getRelative(-1, 0, 0), zone, visited, queue);
                    enqueueLiquidNeighbor(block.getRelative(0, 1, 0), zone, visited, queue);
                    enqueueLiquidNeighbor(block.getRelative(0, -1, 0), zone, visited, queue);
                    enqueueLiquidNeighbor(block.getRelative(0, 0, 1), zone, visited, queue);
                    enqueueLiquidNeighbor(block.getRelative(0, 0, -1), zone, visited, queue);
                }
                drainQueueSizesByZone.put(zone.name(), queue.size());
                if (queue.isEmpty()) {
                    clearDrainTask(zone.name(), this);
                    cancel();
                }
            }
        });
    }

    private BukkitRunnable runSparseBatch(String zoneName, List<Map.Entry<BlockKey, String>> work, Runnable complete) {
        return new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                int end = Math.min(index + changedBatchSize, work.size());
                for (int current = index; current < end; current++) {
                    Map.Entry<BlockKey, String> entry = work.get(current);
                    BlockKey key = entry.getKey();
                    World world = Bukkit.getWorld(key.worldName());
                    if (world != null) {
                        try {
                            world.getBlockAt(key.x(), key.y(), key.z()).setBlockData(Bukkit.createBlockData(entry.getValue()), false);
                        } catch (IllegalArgumentException ex) {
                            plugin.getLogger().warning("Skipping invalid sparse block data at " + key + ": " + ex.getMessage());
                        }
                    }
                }
                counters.resetBlocksProcessed(end - index);
                index = end;
                if (index >= work.size()) {
                    complete.run();
                    clearZoneTask(zoneName, this);
                    cancel();
                }
            }
        };
    }

    private BukkitRunnable runBatch(String zoneName, List<BlockKey> work, int batchSize, java.util.function.Consumer<BlockKey> operation, Runnable onComplete) {
        return new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                int end = Math.min(index + batchSize, work.size());
                int processed = end - index;
                for (int current = index; current < end; current++) {
                    operation.accept(work.get(current));
                }
                index = end;
                counters.resetBlocksProcessed(processed);
                if (index >= work.size()) {
                    onComplete.run();
                    clearZoneTask(zoneName, this);
                    cancel();
                }
            }
        };
    }

    private void enqueueLiquidNeighbor(Block next, GameplayZone zone, Set<BlockKey> visited, ArrayDeque<Block> queue) {
        if (!zone.region().contains(next.getLocation())) {
            return;
        }
        BlockKey nextKey = BlockKey.of(next);
        if (!visited.add(nextKey)) {
            return;
        }
        if (next.getType() == Material.WATER || next.getType() == Material.LAVA) {
            queue.add(next);
        }
    }

    private void cancelZoneMutations(GameplayZone zone) {
        cancelZoneTask(zone.name());
        cancelDrainTasks(zone.name());

        for (Map.Entry<BlockKey, BukkitTask> entry : new ArrayList<>(ttlTasks.entrySet())) {
            BlockKey key = entry.getKey();
            if (zone.region().contains(key.worldName(), key.x(), key.y(), key.z())) {
                entry.getValue().cancel();
                ttlTasks.remove(key);
                ttlExpiresAt.remove(key);
                ttlZonesByBlock.remove(key);
                temporaryBlocks.remove(key);
            }
        }

        for (BlockKey key : new ArrayList<>(temporaryBlocks)) {
            if (!zone.region().contains(key.worldName(), key.x(), key.y(), key.z())) {
                continue;
            }
            World world = Bukkit.getWorld(key.worldName());
            if (world == null) {
                temporaryBlocks.remove(key);
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.AIR) {
                block.setType(Material.AIR, false);
            }
            temporaryBlocks.remove(key);
        }

        placedBlocks.removeIf(key -> zone.region().contains(key.worldName(), key.x(), key.y(), key.z()));
    }

    private boolean isRestoreRunning(String zoneName) {
        ZoneTaskHandle handle = activeZoneTasks.get(zoneName);
        return handle != null && handle.type() == ZoneTaskType.RESTORE;
    }

    private void startZoneTask(String zoneName, ZoneTaskType type, BukkitRunnable runnable) {
        if (type != ZoneTaskType.DRAIN) {
            cancelZoneTask(zoneName);
            BukkitTask task = runnable.runTaskTimer(plugin, 1L, 1L);
            activeZoneTasks.put(zoneName, new ZoneTaskHandle(type, task));
            return;
        }

        ZoneTaskHandle existing = activeZoneTasks.get(zoneName);
        if (existing != null && existing.type() == ZoneTaskType.RESTORE) {
            return;
        }
        BukkitTask task = runnable.runTaskTimer(plugin, 1L, 1L);
        activeDrainTasksByZone.computeIfAbsent(zoneName, ignored -> ConcurrentHashMap.newKeySet()).add(task);
    }

    private void cancelZoneTask(String zoneName) {
        ZoneTaskHandle existing = activeZoneTasks.remove(zoneName);
        if (existing != null) {
            existing.task().cancel();
        }
    }

    private void clearZoneTask(String zoneName, BukkitRunnable runnable) {
        ZoneTaskHandle current = activeZoneTasks.get(zoneName);
        if (current != null && current.task().getTaskId() == runnable.getTaskId()) {
            activeZoneTasks.remove(zoneName);
        }
    }

    private void clearDrainTask(String zoneName, BukkitRunnable runnable) {
        Set<BukkitTask> tasks = activeDrainTasksByZone.get(zoneName);
        if (tasks == null) {
            return;
        }
        tasks.removeIf(task -> task.getTaskId() == runnable.getTaskId());
        if (tasks.isEmpty()) {
            activeDrainTasksByZone.remove(zoneName);
            drainQueueSizesByZone.remove(zoneName);
        }
    }

    private void cancelDrainTasks(String zoneName) {
        Set<BukkitTask> tasks = activeDrainTasksByZone.remove(zoneName);
        drainQueueSizesByZone.remove(zoneName);
        if (tasks == null) {
            return;
        }
        for (BukkitTask task : tasks) {
            task.cancel();
        }
    }

    private void clearTemporaryBlocksImmediately() {
        for (BlockKey key : new ArrayList<>(temporaryBlocks)) {
            World world = Bukkit.getWorld(key.worldName());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != Material.AIR) {
                block.setType(Material.AIR, false);
            }
        }
        temporaryBlocks.clear();
    }

    private void cancelAllTasks() {
        for (BukkitTask task : ttlTasks.values()) {
            task.cancel();
        }
        ttlTasks.clear();
        for (ZoneTaskHandle handle : activeZoneTasks.values()) {
            handle.task().cancel();
        }
        activeZoneTasks.clear();
        for (Set<BukkitTask> tasks : activeDrainTasksByZone.values()) {
            for (BukkitTask task : tasks) {
                task.cancel();
            }
        }
        activeDrainTasksByZone.clear();
        drainQueueSizesByZone.clear();
    }

    public ZoneStateSnapshot snapshotState() {
        Map<String, Set<BlockKey>> changed = new ConcurrentHashMap<>();
        for (Map.Entry<String, Set<BlockKey>> entry : changedBlocksByZone.entrySet()) {
            changed.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        Map<String, Set<Integer>> warnings = new ConcurrentHashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : firedWarningsByZone.entrySet()) {
            warnings.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return new ZoneStateSnapshot(changed, new HashSet<>(temporaryBlocks), new HashSet<>(placedBlocks), new ConcurrentHashMap<>(ttlExpiresAt), new ConcurrentHashMap<>(ttlZonesByBlock), new ConcurrentHashMap<>(lastResetAt), warnings);
    }

    public void restoreState(ZoneStateSnapshot snapshot, boolean clearInvalidZoneState, boolean preserveTemporaryBlocks) {
        if (snapshot == null) {
            return;
        }
        restoreChangedBlocks(snapshot, clearInvalidZoneState);
        restoreResetWarnings(snapshot, clearInvalidZoneState);
        if (preserveTemporaryBlocks) {
            restoreTemporaryBlockState(snapshot);
        }
    }

    private void restoreChangedBlocks(ZoneStateSnapshot snapshot, boolean clearInvalidZoneState) {
        changedBlocksByZone.clear();
        for (Map.Entry<String, Set<BlockKey>> entry : snapshot.changedBlocksByZone().entrySet()) {
            if (clearInvalidZoneState && zoneRegistry.findZone(entry.getKey()) == null) {
                continue;
            }
            changedBlocksByZone.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
    }

    private void restoreResetWarnings(ZoneStateSnapshot snapshot, boolean clearInvalidZoneState) {
        lastResetAt.clear();
        for (Map.Entry<String, Long> entry : snapshot.lastResetAt().entrySet()) {
            if (!clearInvalidZoneState || zoneRegistry.findZone(entry.getKey()) != null) {
                lastResetAt.put(entry.getKey(), entry.getValue());
            }
        }
        firedWarningsByZone.clear();
        for (Map.Entry<String, Set<Integer>> entry : snapshot.firedWarningsByZone().entrySet()) {
            if (!clearInvalidZoneState || zoneRegistry.findZone(entry.getKey()) != null) {
                firedWarningsByZone.put(entry.getKey(), ConcurrentHashMap.newKeySet(entry.getValue().size()));
                firedWarningsByZone.get(entry.getKey()).addAll(entry.getValue());
            }
        }
    }

    private void restoreTemporaryBlockState(ZoneStateSnapshot snapshot) {
        temporaryBlocks.clear();
        temporaryBlocks.addAll(snapshot.temporaryBlocks());
        placedBlocks.clear();
        placedBlocks.addAll(snapshot.placedBlocks());
        ttlExpiresAt.clear();
        ttlExpiresAt.putAll(snapshot.ttlExpiresAt());
        ttlZonesByBlock.clear();
        ttlZonesByBlock.putAll(snapshot.ttlZonesByBlock());
        rescheduleTemporaryBlocks();
    }

    public void runBackstopPass(int maxZones, int maxBlocks, boolean repairMode, boolean reportOnly) {
        repairChangedBlockBackstop(maxZones, maxBlocks, repairMode, reportOnly);
        repairTemporaryBlockBackstop(repairMode, reportOnly);
    }

    private void repairChangedBlockBackstop(int maxZones, int maxBlocks, boolean repairMode, boolean reportOnly) {
        int processedZones = 0;
        for (String zoneName : new ArrayList<>(changedBlocksByZone.keySet())) {
            if (processedZones >= maxZones) {
                return;
            }
            processedZones++;
            if (zoneRegistry.findZone(zoneName) == null) {
                if (repairMode && !reportOnly) {
                    changedBlocksByZone.remove(zoneName);
                    counters.backstopRepair();
                }
                continue;
            }
            Set<BlockKey> changed = changedBlocksByZone.get(zoneName);
            if (changed == null) {
                continue;
            }
            int checked = 0;
            for (BlockKey key : new ArrayList<>(changed)) {
                if (checked >= maxBlocks) {
                    break;
                }
                checked++;
                if (Bukkit.getWorld(key.worldName()) == null && repairMode && !reportOnly) {
                    changed.remove(key);
                    counters.backstopRepair();
                }
            }
        }
    }

    private void repairTemporaryBlockBackstop(boolean repairMode, boolean reportOnly) {
        for (BlockKey key : new ArrayList<>(temporaryBlocks)) {
            Long expiresAt = ttlExpiresAt.get(key);
            if (expiresAt != null && expiresAt <= System.currentTimeMillis() && repairMode && !reportOnly) {
                World world = Bukkit.getWorld(key.worldName());
                if (world != null) {
                    Block block = world.getBlockAt(key.x(), key.y(), key.z());
                    if (block.getType() != Material.AIR) {
                        block.setType(Material.AIR, false);
                    }
                }
                temporaryBlocks.remove(key);
                ttlExpiresAt.remove(key);
                ttlZonesByBlock.remove(key);
                counters.backstopRepair();
            }
        }
    }

    public int resetQueueSize() {
        int total = 0;
        for (Set<BlockKey> blocks : changedBlocksByZone.values()) {
            total += blocks.size();
        }
        return total;
    }

    public int activeZoneTaskCount() {
        return activeZoneTasks.size();
    }

    public int activeDrainTaskCount() {
        int total = 0;
        for (Set<BukkitTask> tasks : activeDrainTasksByZone.values()) {
            total += tasks.size();
        }
        return total;
    }

    public int drainQueueSize() {
        int total = 0;
        for (Integer size : drainQueueSizesByZone.values()) {
            total += size;
        }
        return total;
    }

    public int temporaryBlockCount() {
        return temporaryBlocks.size();
    }

    private void rescheduleTemporaryBlocks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockKey, Long> entry : new ArrayList<>(ttlExpiresAt.entrySet())) {
            BlockKey key = entry.getKey();
            long delayTicks = Math.max(1L, (entry.getValue() - now) / 50L);
            World world = Bukkit.getWorld(key.worldName());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            Set<String> zoneNames = ttlZonesByBlock.getOrDefault(key, Set.of());
            List<GameplayZone> zones = new ArrayList<>();
            for (String zoneName : zoneNames) {
                GameplayZone zone = zoneRegistry.findZone(zoneName);
                if (zone != null) {
                    zones.add(zone);
                }
            }
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                ttlTasks.remove(key);
                ttlExpiresAt.remove(key);
                ttlZonesByBlock.remove(key);
                if (block.getType() == Material.WATER || block.getType() == Material.LAVA) {
                    for (GameplayZone zone : zones) {
                        drainLiquidInZone(block, zone);
                    }
                } else if (block.getType() != Material.AIR) {
                    block.setType(Material.AIR, false);
                }
                temporaryBlocks.remove(key);
            }, delayTicks);
            ttlTasks.put(key, task);
        }
    }

    private Set<String> zoneNames(Collection<GameplayZone> zones) {
        Set<String> names = new HashSet<>();
        for (GameplayZone zone : zones) {
            names.add(zone.name());
        }
        return names;
    }

    public record ZoneStateSnapshot(
            Map<String, Set<BlockKey>> changedBlocksByZone,
            Set<BlockKey> temporaryBlocks,
            Set<BlockKey> placedBlocks,
            Map<BlockKey, Long> ttlExpiresAt,
            Map<BlockKey, Set<String>> ttlZonesByBlock,
            Map<String, Long> lastResetAt,
            Map<String, Set<Integer>> firedWarningsByZone
    ) {
    }

    private enum ZoneTaskType {
        DRAIN,
        CLEAR,
        RESTORE
    }

    private record ZoneTaskHandle(ZoneTaskType type, BukkitTask task) {
    }
}
