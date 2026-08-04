package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

public final class TemporaryBlockService implements Listener {
    private final JavaPlugin plugin;
    private final TemporaryBlockRepository repository;
    private final Executor io;
    private final int maxTracked;
    private final Function<UUID, World> worldLookup;
    private final Function<String, BlockData> blockDataFactory;
    private final Map<String, TemporaryBlock> tracked = new LinkedHashMap<>();
    private final EnumMap<TerminalReason, Long> terminalReasons = new EnumMap<>(TerminalReason.class);
    private final Object persistenceLock = new Object();
    private final List<CompletableFuture<Void>> pendingWriteCompletions = new ArrayList<>();
    private BukkitTask ticker;
    private Map<String, TemporaryBlock> pendingSnapshot;
    private boolean writerScheduled;
    private volatile Map<String, TemporaryBlock> durableSnapshot = Map.of();
    private volatile boolean persistenceHealthy = true;
    private volatile boolean rollbackScheduled;
    private volatile boolean persistenceFailureLogged;

    public TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository,
                                 Executor io, int maxTracked) {
        this(plugin, repository, io, maxTracked, Bukkit::getWorld, Bukkit::createBlockData, true);
    }

    TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository,
                          Executor io, int maxTracked, Function<UUID, World> worldLookup,
                          Function<String, BlockData> blockDataFactory, boolean startTicker) {
        this.plugin = plugin;
        this.repository = repository;
        this.io = io;
        this.maxTracked = maxTracked;
        this.worldLookup = worldLookup;
        this.blockDataFactory = blockDataFactory;
        for (TerminalReason reason : TerminalReason.values()) terminalReasons.put(reason, 0L);
        try {
            tracked.putAll(repository.load());
            durableSnapshot = Map.copyOf(tracked);
        } catch (IOException ex) {
            persistenceHealthy = false;
            logPersistenceFailure("Temporary block persistence disabled", ex);
        }
        if (startTicker) start();
    }

    public boolean track(Block block, String originalData, long expiresAt) {
        return track(block, originalData, expiresAt, false);
    }

    public boolean track(Block block, String originalData, long expiresAt, boolean warzoneOwned) {
        if (!persistenceHealthy || tracked.size() >= maxTracked) return false;
        TemporaryBlock entry = new TemporaryBlock(block.getWorld().getUID().toString(), block.getX(),
                block.getY(), block.getZ(), block.getBlockData().getAsString(true), originalData,
                expiresAt, false, warzoneOwned);
        String key = key(entry);
        if (tracked.containsKey(key)) return false;
        tracked.put(key, entry);
        CompletableFuture<Void> persisted = persist();
        if (persisted.isCompletedExceptionally()) {
            rollbackUndurable();
            return false;
        }
        return true;
    }

    public int count() { return tracked.size(); }

    public int countMatching(Predicate<TemporaryBlock> selected) {
        int count = 0;
        for (TemporaryBlock entry : tracked.values()) if (selected.test(entry)) count++;
        return count;
    }

    public long pendingClearCount() {
        return tracked.values().stream().filter(TemporaryBlock::pendingClear).count();
    }

    public long expiredCount(long now) {
        return tracked.values().stream().filter(entry -> entry.expiresAt() <= now).count();
    }

    public boolean persistenceHealthy() { return persistenceHealthy; }

    public Diagnostics diagnostics(long now) {
        return new Diagnostics(count(), expiredCount(now), pendingClearCount(), persistenceHealthy,
                count(), maxTracked);
    }

    public Map<TerminalReason, Long> terminalReasonCounts() {
        return Map.copyOf(terminalReasons);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        if (persistenceHealthy) persist();
        else rollbackUndurable();
    }

    public int clearAll() { return clearMatching(ignored -> true); }

    /**
     * Restores loaded matches immediately. Unloaded matches are persisted as pending and restored
     * on chunk load. A selected record is removed only after a terminal outcome is recorded.
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
                    remove(iterator, TerminalReason.WORLD_MISSING);
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
            RestoreOutcome outcome = restoreLoaded(world, entry, TerminalReason.CLEAR_RESTORED);
            if (outcome.terminal()) {
                remove(iterator, outcome.reason());
                changed = true;
            } else if (!entry.pendingClear()) {
                trackedEntry.setValue(entry.withPendingClear());
                changed = true;
            }
        }
        if (changed) persist();
        return affected;
    }

    /** Queues a snapshot after all earlier requested states. */
    public CompletableFuture<Void> persistCurrentState() { return persist(); }

    /** Drops records only when the current block is genuinely no longer the managed material. */
    public int discardStale() {
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (matchesManagedBlock(block, entry)) continue;
            remove(iterator, TerminalReason.CURRENT_BLOCK_DIFFERENT);
            removed++;
        }
        if (removed > 0) persist();
        return removed;
    }

    /**
     * Used after an authoritative region reset. Records are discarded only when the loaded block
     * matches the recorded original data; a cobweb still present at the coordinate remains tracked.
     */
    public int discardResetRestored(Predicate<TemporaryBlock> selected) {
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!selected.test(entry)) continue;
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (!matchesRecordedData(block, entry.originalBlockData(), false)) continue;
            remove(iterator, TerminalReason.RESET_CONFIRMED);
            removed++;
        }
        if (removed > 0) persist();
        return removed;
    }

    /** Explicit discard remains reason-accounted for non-reset callers. */
    public int discardMatching(Predicate<TemporaryBlock> selected) {
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!selected.test(entry)) continue;
            remove(iterator, TerminalReason.EXPLICIT_DISCARD);
            removed++;
        }
        if (removed > 0) persist();
        return removed;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        processAvailable(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(),
                System.currentTimeMillis());
    }

    private void start() {
        if (ticker == null)
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire,
                    20L, 20L);
    }

    private void expire() { expireNow(System.currentTimeMillis()); }

    void expireNow(long now) {
        if (!persistenceHealthy) rollbackUndurable();
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!entry.pendingClear() && entry.expiresAt() > now) continue;
            World world = world(entry);
            if (world == null) {
                if (!validWorldUuid(entry)) {
                    remove(iterator, TerminalReason.WORLD_MISSING);
                    changed = true;
                }
                continue;
            }
            if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            TerminalReason restoredReason = entry.pendingClear()
                    ? TerminalReason.CLEAR_RESTORED : TerminalReason.EXPIRED_RESTORED;
            RestoreOutcome outcome = restoreLoaded(world, entry, restoredReason);
            if (!outcome.terminal()) continue;
            remove(iterator, outcome.reason());
            changed = true;
        }
        if (changed) persist();
    }

    void processAvailable(World world, int chunkX, int chunkZ, long now) {
        if (!persistenceHealthy) rollbackUndurable();
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            TemporaryBlock entry = iterator.next().getValue();
            if (!entry.worldUuid().equals(world.getUID().toString())
                    || (entry.x() >> 4) != chunkX || (entry.z() >> 4) != chunkZ
                    || (!entry.pendingClear() && entry.expiresAt() > now)) continue;
            TerminalReason restoredReason = entry.pendingClear()
                    ? TerminalReason.CLEAR_RESTORED : TerminalReason.EXPIRED_RESTORED;
            RestoreOutcome outcome = restoreLoaded(world, entry, restoredReason);
            if (!outcome.terminal()) continue;
            remove(iterator, outcome.reason());
            changed = true;
        }
        if (changed) persist();
    }

    private RestoreOutcome restoreLoaded(World world, TemporaryBlock entry,
                                         TerminalReason restoredReason) {
        Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
        if (!matchesManagedBlock(block, entry))
            return RestoreOutcome.terminal(TerminalReason.CURRENT_BLOCK_DIFFERENT);
        try {
            block.setBlockData(blockDataFactory.apply(entry.originalBlockData()), false);
            return RestoreOutcome.terminal(restoredReason);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not restore temporary block at "
                    + block.getLocation() + ": " + ex.getMessage());
            return RestoreOutcome.retry();
        }
    }

    private boolean matchesManagedBlock(Block block, TemporaryBlock entry) {
        return matchesRecordedData(block, entry.expectedBlockData(), true);
    }

    private boolean matchesRecordedData(Block block, String serialized,
                                        boolean materialMatchIsSufficient) {
        String current = block.getBlockData().getAsString(true);
        if (current.equals(serialized)) return true;
        if (!block.getType().name().equals(materialName(serialized))) return false;
        if (materialMatchIsSufficient) return true;
        try {
            BlockData expected = blockDataFactory.apply(serialized);
            BlockData actual = block.getBlockData();
            return expected.matches(actual) || actual.matches(expected);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static String materialName(String serialized) {
        if (serialized == null || serialized.isBlank()) return "";
        int properties = serialized.indexOf('[');
        String key = properties < 0 ? serialized : serialized.substring(0, properties);
        int namespace = key.lastIndexOf(':');
        String value = namespace < 0 ? key : key.substring(namespace + 1);
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private World world(TemporaryBlock entry) {
        try { return worldLookup.apply(UUID.fromString(entry.worldUuid())); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private boolean validWorldUuid(TemporaryBlock entry) {
        try {
            UUID.fromString(entry.worldUuid());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private CompletableFuture<Void> persist() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean scheduleWriter = false;
        synchronized (persistenceLock) {
            if (!persistenceHealthy)
                return CompletableFuture.failedFuture(
                        new IOException("temporary block persistence failed"));
            pendingSnapshot = Map.copyOf(tracked);
            pendingWriteCompletions.add(completion);
            if (!writerScheduled) {
                writerScheduled = true;
                scheduleWriter = true;
            }
        }
        if (scheduleWriter) {
            try {
                io.execute(this::drainPersistenceQueue);
            } catch (RuntimeException ex) {
                failPersistence("Temporary block persistence executor rejected a write", ex,
                        List.of());
            }
        }
        return completion;
    }

    private void drainPersistenceQueue() {
        while (true) {
            Map<String, TemporaryBlock> snapshot;
            List<CompletableFuture<Void>> completions;
            synchronized (persistenceLock) {
                if (pendingSnapshot == null) {
                    writerScheduled = false;
                    return;
                }
                snapshot = pendingSnapshot;
                pendingSnapshot = null;
                completions = List.copyOf(pendingWriteCompletions);
                pendingWriteCompletions.clear();
            }
            try {
                repository.save(snapshot);
                durableSnapshot = snapshot;
                completions.forEach(completion -> completion.complete(null));
            } catch (IOException ex) {
                failPersistence("Temporary block state could not be committed; "
                        + "no further blocks will be tracked", ex, completions);
                return;
            }
        }
    }

    private void failPersistence(String prefix, Exception ex,
                                 List<CompletableFuture<Void>> activeCompletions) {
        List<CompletableFuture<Void>> failed = new ArrayList<>(activeCompletions);
        synchronized (persistenceLock) {
            persistenceHealthy = false;
            writerScheduled = false;
            pendingSnapshot = null;
            failed.addAll(pendingWriteCompletions);
            pendingWriteCompletions.clear();
        }
        logPersistenceFailure(prefix, ex);
        failed.forEach(completion -> completion.completeExceptionally(ex));
        scheduleRollbackUndurable();
    }

    private void scheduleRollbackUndurable() {
        if (rollbackScheduled) return;
        rollbackScheduled = true;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                rollbackScheduled = false;
                rollbackUndurable();
            });
        } catch (RuntimeException ex) {
            rollbackScheduled = false;
            plugin.getLogger().severe("Could not schedule rollback of undurable temporary blocks: "
                    + ex.getMessage());
        }
    }

    /** Main-thread recovery for entries accepted after the last successful durable snapshot. */
    public int rollbackUndurable() {
        Set<String> durableKeys = durableSnapshot.keySet();
        int removed = 0;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TemporaryBlock> mapEntry = iterator.next();
            if (durableKeys.contains(mapEntry.getKey())) continue;
            TemporaryBlock entry = mapEntry.getValue();
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            RestoreOutcome outcome = restoreLoaded(world, entry,
                    TerminalReason.PERSISTENCE_ROLLBACK);
            if (!outcome.terminal()) continue;
            remove(iterator, outcome.reason());
            removed++;
        }
        return removed;
    }

    private void remove(java.util.Iterator<?> iterator, TerminalReason reason) {
        iterator.remove();
        terminalReasons.merge(reason, 1L, Long::sum);
    }

    private void logPersistenceFailure(String prefix, Exception ex) {
        if (persistenceFailureLogged) return;
        persistenceFailureLogged = true;
        plugin.getLogger().severe(prefix + ": " + ex.getMessage());
    }

    private String key(TemporaryBlock entry) {
        return entry.worldUuid() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z();
    }

    public record Diagnostics(int tracked, long expired, long pendingClear,
                              boolean persistenceHealthy, int capacityCurrent,
                              int capacityMaximum) { }

    public enum TerminalReason {
        EXPIRED_RESTORED,
        CLEAR_RESTORED,
        CURRENT_BLOCK_DIFFERENT,
        WORLD_MISSING,
        RESET_CONFIRMED,
        PERSISTENCE_ROLLBACK,
        EXPLICIT_DISCARD
    }

    private record RestoreOutcome(boolean terminal, TerminalReason reason) {
        static RestoreOutcome terminal(TerminalReason reason) {
            return new RestoreOutcome(true, reason);
        }

        static RestoreOutcome retry() { return new RestoreOutcome(false, null); }
    }
}
