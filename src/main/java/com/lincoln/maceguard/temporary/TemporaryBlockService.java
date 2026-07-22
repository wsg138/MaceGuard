package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
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

public final class TemporaryBlockService {
    private final JavaPlugin plugin;
    private final TemporaryBlockRepository repository;
    private final WorldGuardQueryService worldGuard;
    private final Executor io;
    private final int maxTracked;
    private final Map<String, TemporaryBlock> tracked = new LinkedHashMap<>();
    private BukkitTask ticker;
    private volatile boolean persistenceHealthy = true;

    public TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository, WorldGuardQueryService worldGuard, Executor io, int maxTracked) {
        this.plugin = plugin; this.repository = repository; this.worldGuard = worldGuard; this.io = io; this.maxTracked = maxTracked;
        io.execute(() -> { try { Map<String, TemporaryBlock> loaded = repository.load(); main(() -> { tracked.putAll(loaded); start(); }); }
        catch (IOException ex) { persistenceHealthy = false; plugin.getLogger().severe("Temporary block persistence disabled: " + ex.getMessage()); main(this::start); } });
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
            if (block.getBlockData().getAsString(true).equals(entry.expectedBlockData()) && worldGuard.cobwebsAllowed(block.getLocation(), null))
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
    private void main(Runnable runnable) { plugin.getServer().getScheduler().runTask(plugin, runnable); }
}
