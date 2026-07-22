package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.Executor;

public final class SnapshotCaptureService {
    private final JavaPlugin plugin;
    private final int batchSize;
    private final Executor io;
    private final BlockStateCodec codec = new BlockStateCodec();

    public SnapshotCaptureService(JavaPlugin plugin, int batchSize, Executor io) { this.plugin = plugin; this.batchSize = batchSize; this.io = io; }

    public void capture(World world, RegionDescriptor region, String profile, Consumer<CaptureResult> callback) {
        if (region.volume() > Integer.MAX_VALUE) { callback.accept(CaptureResult.failure("region is too large")); return; }
        long started = System.currentTimeMillis();
        String pluginVersion = plugin.getPluginMeta().getVersion();
        List<SnapshotBlock> blocks = new ArrayList<>((int) region.volume());
        new BukkitRunnable() {
            int x = region.minX(), y = region.minY(), z = region.minZ();
            @Override public void run() {
                try {
                    int processed = 0;
                    while (x <= region.maxX() && processed++ < batchSize) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) { cancel(); callback.accept(CaptureResult.failure("capture refused: chunk " + (x >> 4) + "," + (z >> 4) + " is not loaded")); return; }
                        blocks.add(codec.capture(world.getBlockAt(x, y, z)));
                        if (++y > region.maxY()) { y = region.minY(); if (++z > region.maxZ()) { z = region.minZ(); x++; } }
                    }
                    if (x > region.maxX()) {
                        cancel();
                        List<SnapshotBlock> completeBlocks = List.copyOf(blocks);
                        long completed = System.currentTimeMillis();
                        io.execute(() -> {
                            String checksum = SnapshotChecksum.calculate(completeBlocks);
                            Snapshot snapshot = new Snapshot(Snapshot.FORMAT_VERSION, pluginVersion, region.id(), region.worldName(),
                                    region.worldUuid().toString(), region.type(), region, region.geometryHash(), profile, started, completed,
                                    true, completeBlocks.size(), region.volume(), checksum, completeBlocks);
                            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(CaptureResult.success(snapshot)));
                        });
                    }
                } catch (RuntimeException ex) { cancel(); callback.accept(CaptureResult.failure(ex.getMessage())); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public record CaptureResult(Snapshot snapshot, String error) {
        public static CaptureResult success(Snapshot value) { return new CaptureResult(value, null); }
        public static CaptureResult failure(String error) { return new CaptureResult(null, error); }
        public boolean successful() { return snapshot != null; }
    }
}
