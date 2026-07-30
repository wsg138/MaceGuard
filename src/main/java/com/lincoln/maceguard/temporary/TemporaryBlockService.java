package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class TemporaryBlockService implements Listener {
    private final JavaPlugin plugin;
    private final TemporaryBlockRepository repository;
    private final Executor io;
    private final int maxTracked;
    private final Map<String, TemporaryBlock> tracked = new LinkedHashMap<>();
    private BukkitTask ticker;
    private volatile boolean persistenceHealthy = true;

    public TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository, Executor io, int maxTracked) {
        this.plugin = plugin; this.repository = repository; this.io = io; this.maxTracked = maxTracked;
        try { tracked.putAll(repository.load()); }
        catch (IOException ex) {
            persistenceHealthy = false;
            plugin.getLogger().severe("Temporary block persistence disabled: " + ex.getMessage());
        }
        start();
    }

    public boolean track(Block block, String originalData, long expiresAt) {
        return track(block, originalData, expiresAt, false);
    }

    public boolean track(Block block, String originalData, long expiresAt, boolean warzoneOwned) {
        if (!persistenceHealthy || tracked.size() >= maxTracked) return false;
        TemporaryBlock entry = new TemporaryBlock(block.getWorld().getUID().toString(), block.getX(), block.getY(),
                block.getZ(), block.getBlockData().getAsString(true), originalData, expiresAt, false, warzoneOwned);
        tracked.put(key(entry), entry);
        persist();
        return true;
    }

    public int count() { return tracked.size(); }
    public long pendingClearCount() { return tracked.values().stream().filter(TemporaryBlock::pendingClear).count(); }
    public boolean persistenceHealthy() { return persistenceHealthy; }
    public void shutdown() { if (ticker != null) ticker.cancel(); }

    public int clearAll() { return clearMatching(ignored -> true); }

    /**
     * Restores loaded matches immediately. Unloaded matches are persisted as pending and restored on chunk load.
     * The return value is the number of selected records either restored, discarded as stale, or marked pending.
     */
    public int clearMatching(Predicate<TemporaryBlock> selected) {
        int affected = 0;
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TemporaryBlock> trackedEntry = iterator.next();
            TemporaryBlock entry = trackedEntry.getValue();
            if (!selected.test(entry)) continue;
            affected++;
            World world = world(entry);
            if (world == null) {
                if (!validWorldUuid(entry)) {
                    iterator.remove();
                    changed = true;
                } else if (!entry.pendingClear()) {
                    trackedEntry.setValue(entry.withPendingClear());
                    changed = true;
                }
                continue;
            }
            if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
                if (!entry.pendingClear()) {
                    trackedEntry.setValue(entry.withPendingClear());
                    changed = true;
                }
                continue;
            }
            RestoreOutcome outcome = restoreLoaded(world, entry);
            if (outcome != RestoreOutcome.RETRY) {
                iterator.remove();
                changed = true;
            } else if (!entry.pendingClear()) {
                trackedEntry.setValue(entry.withPendingClear());
                changed = true;
            }
        }
        if (changed) persist();
        return affected;
    }

    /** Drops records whose blocks were changed by a reset or another authoritative system. */
    public int discardStale() {
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (block.getBlockData().getAsString(true).equals(entry.expectedBlockData())) continue;
            iterator.remove();
            removed++;
        }
        if (removed > 0) persist();
        return removed;
    }

    public int discardMatching(Predicate<TemporaryBlock> selected) {
        int before = tracked.size();
        tracked.values().removeIf(selected);
        int removed = before - tracked.size();
        if (removed > 0) persist();
        return removed;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        processAvailable(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), System.currentTimeMillis());
    }

    private void start() {
        if (ticker == null) ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!entry.pendingClear() && entry.expiresAt() > now) continue;
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            RestoreOutcome outcome = restoreLoaded(world, entry);
            if (outcome == RestoreOutcome.RETRY) continue;
            iterator.remove();
            changed = true;
        }
        if (changed) persist();
    }

    private void processAvailable(World world, int chunkX, int chunkZ, long now) {
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!entry.worldUuid().equals(world.getUID().toString()) || (entry.x() >> 4) != chunkX
                    || (entry.z() >> 4) != chunkZ || (!entry.pendingClear() && entry.expiresAt() > now)) continue;
            RestoreOutcome outcome = restoreLoaded(world, entry);
            if (outcome == RestoreOutcome.RETRY) continue;
            iterator.remove();
            changed = true;
        }
        if (changed) persist();
    }

    private RestoreOutcome restoreLoaded(World world, TemporaryBlock entry) {
        Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
        if (!block.getBlockData().getAsString(true).equals(entry.expectedBlockData())) return RestoreOutcome.STALE;
        try {
            block.setBlockData(Bukkit.createBlockData(entry.originalBlockData()), false);
            return RestoreOutcome.RESTORED;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Could not restore temporary block at " + block.getLocation() + ": " + ex.getMessage());
            return RestoreOutcome.RETRY;
        }
    }

    private World world(TemporaryBlock entry) {
        try { return Bukkit.getWorld(UUID.fromString(entry.worldUuid())); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private boolean validWorldUuid(TemporaryBlock entry) {
        try { UUID.fromString(entry.worldUuid()); return true; }
        catch (IllegalArgumentException ex) { return false; }
    }

    private void persist() {
        Map<String, TemporaryBlock> copy = Map.copyOf(tracked);
        io.execute(() -> {
            try { repository.save(copy); }
            catch (IOException ex) {
                persistenceHealthy = false;
                plugin.getLogger().severe("Temporary block state could not be committed; no further blocks will be tracked: "
                        + ex.getMessage());
            }
        });
    }

    private String key(TemporaryBlock entry) {
        return entry.worldUuid() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z();
    }

    private enum RestoreOutcome { RESTORED, STALE, RETRY }
}
