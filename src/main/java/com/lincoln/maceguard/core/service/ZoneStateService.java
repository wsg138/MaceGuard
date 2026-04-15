package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.core.model.BlockKey;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.ResetMode;
import com.lincoln.maceguard.core.model.ResetScope;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ZoneStateService {
    private static final int CHANGED_BATCH_SIZE = 1500;
    private static final int FULL_RESTORE_BATCH_SIZE = 1024;
    private static final int LIQUID_DRAIN_BATCH_SIZE = 1000;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final SnapshotService snapshotService;
    private final Map<String, Set<BlockKey>> changedBlocksByZone = new HashMap<>();
    private final Map<BlockKey, BukkitTask> ttlTasks = new HashMap<>();
    private final Set<BlockKey> temporaryBlocks = new HashSet<>();
    private final Map<String, ZoneTaskHandle> activeZoneTasks = new HashMap<>();
    private final Map<String, Set<BukkitTask>> activeDrainTasksByZone = new HashMap<>();
    private final Map<String, Long> lastResetAt = new HashMap<>();
    private final Map<String, Set<Integer>> firedWarningsByZone = new HashMap<>();

    public ZoneStateService(Plugin plugin, ZoneRegistry zoneRegistry, SnapshotService snapshotService) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.snapshotService = snapshotService;
        initializeResetState();
    }

    private void initializeResetState() {
        long now = System.currentTimeMillis();
        for (GameplayZone zone : zoneRegistry.allGameplayZones()) {
            if (zone.fullResetMinutes() > 0) {
                lastResetAt.put(zone.name(), now);
                firedWarningsByZone.put(zone.name(), new HashSet<>());
            }
        }
    }

    public void onReloadCleanup() {
        cancelAllTasks();
        clearTemporaryBlocksImmediately();
        changedBlocksByZone.clear();
        lastResetAt.clear();
        firedWarningsByZone.clear();
        initializeResetState();
    }

    public void onDisableCleanup() {
        cancelAllTasks();
        clearTemporaryBlocksImmediately();
        changedBlocksByZone.clear();
        lastResetAt.clear();
        firedWarningsByZone.clear();
    }

    public void markChanged(String zoneName, Block block) {
        changedBlocksByZone.computeIfAbsent(zoneName, ignored -> new LinkedHashSet<>()).add(BlockKey.of(block));
    }

    public void scheduleTemporaryClear(Block block, int ttlSeconds, Collection<GameplayZone> applicableZones) {
        BlockKey key = BlockKey.of(block);
        BukkitTask previous = ttlTasks.remove(key);
        if (previous != null) {
            previous.cancel();
        }
        temporaryBlocks.add(key);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ttlTasks.remove(key);
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

        if (zone.resetScope() == ResetScope.CHANGED) {
            Set<BlockKey> changed = changedBlocksByZone.get(zone.name());
            if (changed == null || changed.isEmpty()) {
                return;
            }
            List<BlockKey> work = new ArrayList<>(changed);
            startZoneTask(zone.name(), ZoneTaskType.RESTORE, runBatch(zone.name(), work, CHANGED_BATCH_SIZE, key -> {
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

            @Override
            public void run() {
                int processed = 0;
                while (x <= zone.region().maxX()) {
                    while (z <= zone.region().maxZ()) {
                        while (y <= zone.region().maxY()) {
                            snapshotService.applyAt(zone.name(), world.getBlockAt(x, y, z));
                            y++;
                            processed++;
                            if (processed >= FULL_RESTORE_BATCH_SIZE) {
                                return;
                            }
                        }
                        y = zone.region().minY();
                        z++;
                    }
                    z = zone.region().minZ();
                    x++;
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
            if (zone.fullResetMinutes() <= 0) {
                continue;
            }
            long last = lastResetAt.getOrDefault(zone.name(), now);
            long periodMillis = zone.fullResetMinutes() * 60_000L;
            long remainingSeconds = Math.max(0L, (last + periodMillis - now) / 1000L);

            Set<Integer> fired = firedWarningsByZone.computeIfAbsent(zone.name(), ignored -> new HashSet<>());
            for (int warningSeconds : zone.warnBeforeSeconds()) {
                if (remainingSeconds == warningSeconds && fired.add(warningSeconds)) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (zone.region().contains(player.getLocation())) {
                            player.sendMessage("\u00A7e[" + zone.name() + "] Reset in \u00A7c" + warningSeconds + "\u00A7es.");
                        }
                    }
                }
            }

            if (remainingSeconds == 0L) {
                if (isRestoreRunning(zone.name())) {
                    continue;
                }
                lastResetAt.put(zone.name(), now);
                fired.clear();
                resetZone(zone, message -> {});
            }
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
        startZoneTask(zoneName, ZoneTaskType.CLEAR, runBatch(zoneName, work, CHANGED_BATCH_SIZE, key -> {
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
            player.teleport(new Location(world, location.getBlockX() + 0.5D, highestY + 1.1D, location.getBlockZ() + 0.5D, location.getYaw(), location.getPitch()));
        }
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
                while (!queue.isEmpty() && processed < LIQUID_DRAIN_BATCH_SIZE) {
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
                    for (Block next : List.of(
                            block.getRelative(1, 0, 0),
                            block.getRelative(-1, 0, 0),
                            block.getRelative(0, 1, 0),
                            block.getRelative(0, -1, 0),
                            block.getRelative(0, 0, 1),
                            block.getRelative(0, 0, -1))) {
                        if (!zone.region().contains(next.getLocation())) {
                            continue;
                        }
                        BlockKey nextKey = BlockKey.of(next);
                        if (!visited.add(nextKey)) {
                            continue;
                        }
                        if (next.getType() == Material.WATER || next.getType() == Material.LAVA) {
                            queue.add(next);
                        }
                    }
                }
                if (queue.isEmpty()) {
                    clearDrainTask(zone.name(), this);
                    cancel();
                }
            }
        });
    }

    private BukkitRunnable runBatch(String zoneName, List<BlockKey> work, int batchSize, java.util.function.Consumer<BlockKey> operation, Runnable onComplete) {
        return new BukkitRunnable() {
            private int index = 0;

            @Override
            public void run() {
                int end = Math.min(index + batchSize, work.size());
                for (int current = index; current < end; current++) {
                    operation.accept(work.get(current));
                }
                index = end;
                if (index >= work.size()) {
                    onComplete.run();
                    clearZoneTask(zoneName, this);
                    cancel();
                }
            }
        };
    }

    private void cancelZoneMutations(GameplayZone zone) {
        cancelZoneTask(zone.name());
        cancelDrainTasks(zone.name());

        for (Map.Entry<BlockKey, BukkitTask> entry : new ArrayList<>(ttlTasks.entrySet())) {
            BlockKey key = entry.getKey();
            if (zone.region().contains(key.worldName(), key.x(), key.y(), key.z())) {
                entry.getValue().cancel();
                ttlTasks.remove(key);
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
        activeDrainTasksByZone.computeIfAbsent(zoneName, ignored -> new HashSet<>()).add(task);
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
        }
    }

    private void cancelDrainTasks(String zoneName) {
        Set<BukkitTask> tasks = activeDrainTasksByZone.remove(zoneName);
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
    }

    private enum ZoneTaskType {
        DRAIN,
        CLEAR,
        RESTORE
    }

    private record ZoneTaskHandle(ZoneTaskType type, BukkitTask task) {
    }
}
