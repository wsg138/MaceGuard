package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.config.ResetProfile;
import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class SnapshotCaptureService {
    private final JavaPlugin plugin;
    private final int batchSize;
    private final Executor io;
    private final BlockStateCodec codec = new BlockStateCodec();

    public SnapshotCaptureService(JavaPlugin plugin, int batchSize, Executor io) {
        this.plugin = plugin;
        this.batchSize = batchSize;
        this.io = io;
    }

    public void capture(World world, RegionDescriptor region, ResetProfile profile,
                        CoordinateExclusion excluded, Consumer<CaptureResult> callback) {
        if (region.volume() > profile.maxCoordinates()) {
            callback.accept(CaptureResult.failure(
                    "region scan volume exceeds profile safety limit"));
            return;
        }
        long started = System.currentTimeMillis();
        String pluginVersion = plugin.getPluginMeta().getVersion();
        List<SnapshotBlock> blocks = new ArrayList<>();
        new BukkitRunnable() {
            int x = region.minX(), y = region.minY(), z = region.minZ();
            long scanned;

            @Override public void run() {
                try {
                    int processed = 0;
                    while (x <= region.maxX() && processed++ < batchSize) {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            cancel();
                            callback.accept(CaptureResult.failure("capture refused: chunk "
                                    + (x >> 4) + "," + (z >> 4) + " is not loaded"));
                            return;
                        }
                        int currentX = x, currentY = y, currentZ = z;
                        if (!excluded.test(currentX, currentY, currentZ)) {
                            scanned++;
                            var block = world.getBlockAt(currentX, currentY, currentZ);
                            if (profile.mode() == ResetProfile.Mode.FULL_SNAPSHOT) {
                                blocks.add(codec.capture(block));
                            } else if (profile.mode() == ResetProfile.Mode.FILTERED_SNAPSHOT
                                    && profile.captureMaterials().contains(block.getType())) {
                                SnapshotBlock captured = codec.capture(block);
                                if (captured.blockEntity() != null) {
                                    cancel();
                                    callback.accept(CaptureResult.failure(
                                            "filtered capture refused block entity at "
                                                    + captured.x() + "," + captured.y() + ","
                                                    + captured.z()));
                                    return;
                                }
                                blocks.add(captured);
                                if (blocks.size() > profile.maxCapturedCoordinates()) {
                                    cancel();
                                    callback.accept(CaptureResult.failure(
                                            "filtered capture exceeded max-captured-coordinates"));
                                    return;
                                }
                            }
                        }
                        advance();
                    }
                    if (x > region.maxX()) {
                        cancel();
                        List<SnapshotBlock> completeBlocks = List.copyOf(blocks);
                        long completed = System.currentTimeMillis();
                        long scanCount = scanned;
                        io.execute(() -> finalizeCapture(region, profile, pluginVersion,
                                started, completed, scanCount, completeBlocks, callback));
                    }
                } catch (RuntimeException ex) {
                    cancel();
                    callback.accept(CaptureResult.failure(message(ex)));
                }
            }

            private void advance() {
                if (++y > region.maxY()) {
                    y = region.minY();
                    if (++z > region.maxZ()) {
                        z = region.minZ();
                        x++;
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finalizeCapture(RegionDescriptor region, ResetProfile profile,
                                 String pluginVersion, long started, long completed,
                                 long scanCount, List<SnapshotBlock> completeBlocks,
                                 Consumer<CaptureResult> callback) {
        CaptureResult result;
        try {
            String checksum = SnapshotChecksum.calculate(completeBlocks);
            result = CaptureResult.success(new Snapshot(Snapshot.FORMAT_VERSION,
                    pluginVersion, region.id(), region.worldName(),
                    region.worldUuid().toString(), region.type(), region,
                    region.geometryHash(), profile.name(), profile.mode().name(),
                    started, completed, true, scanCount, completeBlocks.size(),
                    completeBlocks.size(), checksum, completeBlocks));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Snapshot assembly failed for region " + region.id(), ex);
            result = CaptureResult.failure("snapshot assembly failed: " + message(ex));
        }
        deliver(callback, result);
    }

    private void deliver(Consumer<CaptureResult> callback, CaptureResult result) {
        try {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> callback.accept(result));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not schedule snapshot completion callback; releasing capture directly",
                    ex);
            callback.accept(CaptureResult.failure(
                    "snapshot completion dispatch failed: " + message(ex)));
        }
    }

    private String message(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    @FunctionalInterface
    public interface CoordinateExclusion {
        boolean test(int x, int y, int z);
    }

    public record CaptureResult(Snapshot snapshot, String error) {
        public static CaptureResult success(Snapshot value) {
            return new CaptureResult(value, null);
        }
        public static CaptureResult failure(String error) {
            return new CaptureResult(null, error);
        }
        public boolean successful() { return snapshot != null; }
    }
}
