package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class TemporaryBlockService {
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
        if (!persistenceHealthy || tracked.size() >= maxTracked) return false;
        TemporaryBlock entry = new TemporaryBlock(block.getWorld().getUID().toString(), block.getX(), block.getY(), block.getZ(), block.getBlockData().getAsString(true), originalData, expiresAt);
        tracked.put(key(entry), entry);
        persist();
        return true;
    }

    public int count() { return tracked.size(); }
    public boolean persistenceHealthy() { return persistenceHealthy; }
    public void shutdown() { if (ticker != null) ticker.cancel(); }

    /** Restores every safely reachable tracked block and retains unloaded entries for startup recovery. */
    public int clearAll() {
        return clearMatching(ignored -> true);
    }

    public int clearMatching(Predicate<TemporaryBlock> selected) {
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!selected.test(entry)) continue;
            World world;
            try { world = Bukkit.getWorld(UUID.fromString(entry.worldUuid())); }
            catch (IllegalArgumentException ex) { iterator.remove(); removed++; continue; }
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (block.getBlockData().getAsString(true).equals(entry.expectedBlockData())) {
                try { block.setBlockData(Bukkit.createBlockData(entry.originalBlockData()), false); }
                catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Could not restore temporary block at " + block.getLocation() + ": " + ex.getMessage());
                    continue;
                }
            }
            iterator.remove();
            removed++;
        }
        if (removed > 0) persist();
        return removed;
    }

    /** Drops records whose blocks were changed by a reset or another authoritative system. */
    public int discardStale() {
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            World world;
            try { world = Bukkit.getWorld(UUID.fromString(entry.worldUuid())); }
            catch (IllegalArgumentException ex) { iterator.remove(); removed++; continue; }
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

    private void start() { if (ticker == null) ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L); }

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (entry.expiresAt() > now) continue;
            World world = Bukkit.getWorld(UUID.fromString(entry.worldUuid()));
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (block.getBlockData().getAsString(true).equals(entry.expectedBlockData()))
                block.setBlockData(Bukkit.createBlockData(entry.originalBlockData()), false);
            iterator.remove();
            changed = true;
        }
        if (changed) persist();
    }

    private void persist() {
        Map<String, TemporaryBlock> copy = Map.copyOf(tracked);
        io.execute(() -> { try { repository.save(copy); }
        catch (IOException ex) { persistenceHealthy = false; plugin.getLogger().severe("Temporary block state could not be committed; no further blocks will be tracked: " + ex.getMessage()); } });
    }

    private String key(TemporaryBlock entry) { return entry.worldUuid() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z(); }
}
