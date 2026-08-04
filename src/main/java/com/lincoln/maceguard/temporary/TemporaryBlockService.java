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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

public final class TemporaryBlockService implements Listener {
    private static final int TRACE_LIMIT = 64;
    private static final int EMERGENCY_SCAN_LIMIT = 256;
    private static final int EMERGENCY_CHUNKS_PER_PASS = 2;
    private static final int EMERGENCY_ENTRIES_PER_PASS = 64;
    private static final long EMERGENCY_PENDING_LOG_INTERVAL_MILLIS = 30_000L;
    private static final String WORLD_UNAVAILABLE_DATA = "<world-unavailable>";

    private final JavaPlugin plugin;
    private final TemporaryBlockRepository repository;
    private final TemporaryBlockRepository emergencyRepository;
    private final Executor io;
    private final int maxTracked;
    private final Function<UUID, World> worldLookup;
    private final Function<String, BlockData> blockDataFactory;
    private final Map<String, TemporaryBlock> tracked = new LinkedHashMap<>();
    private final EnumMap<TerminalReason, Long> terminalReasons =
            new EnumMap<>(TerminalReason.class);
    private final Deque<TraceEvent> traceEvents = new ArrayDeque<>(TRACE_LIMIT);
    private final Object persistenceLock = new Object();
    private final List<CompletableFuture<Void>> pendingWriteCompletions = new ArrayList<>();
    private BukkitTask ticker;
    private Map<String, TemporaryBlock> pendingSnapshot = Map.of();
    private boolean snapshotPending;
    private boolean writerScheduled;
    private volatile Map<String, TemporaryBlock> durableSnapshot = Map.of();
    private volatile Map<String, TemporaryBlock> emergencyRecovery = Map.of();
    private volatile boolean persistenceHealthy = true;
    private volatile boolean emergencyJournalHealthy = true;
    private volatile boolean emergencyJournalDirty;
    private volatile boolean rollbackScheduled;
    private volatile boolean persistenceFailureLogged;
    private volatile boolean emergencyJournalFailureLogged;
    private boolean shuttingDown;
    private boolean emergencyRecoveryStartedLogged;
    private long lastEmergencyPendingLog;

    public TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository,
                                 Executor io, int maxTracked) {
        this(plugin, repository, repository.siblingWithSuffix("-emergency"), io, maxTracked,
                Bukkit::getWorld, Bukkit::createBlockData, true);
    }

    public TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository,
                                 TemporaryBlockRepository emergencyRepository,
                                 Executor io, int maxTracked) {
        this(plugin, repository, emergencyRepository, io, maxTracked,
                Bukkit::getWorld, Bukkit::createBlockData, true);
    }

    TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository,
                          Executor io, int maxTracked, Function<UUID, World> worldLookup,
                          Function<String, BlockData> blockDataFactory, boolean startTicker) {
        this(plugin, repository, repository.siblingWithSuffix("-emergency"), io, maxTracked,
                worldLookup, blockDataFactory, startTicker);
    }

    TemporaryBlockService(JavaPlugin plugin, TemporaryBlockRepository repository,
                          TemporaryBlockRepository emergencyRepository, Executor io,
                          int maxTracked, Function<UUID, World> worldLookup,
                          Function<String, BlockData> blockDataFactory, boolean startTicker) {
        this.plugin = plugin;
        this.repository = repository;
        this.emergencyRepository = emergencyRepository;
        this.io = io;
        this.maxTracked = maxTracked;
        this.worldLookup = worldLookup;
        this.blockDataFactory = blockDataFactory;
        for (TerminalReason reason : TerminalReason.values()) terminalReasons.put(reason, 0L);
        loadPrimaryState();
        loadEmergencyState();
        if (startTicker) start();
    }

    private void loadPrimaryState() {
        try {
            tracked.putAll(repository.load());
            durableSnapshot = Map.copyOf(tracked);
        } catch (IOException ex) {
            persistenceHealthy = false;
            logPersistenceFailure("Temporary block persistence disabled", ex);
        }
    }

    private void loadEmergencyState() {
        try {
            Map<String, TemporaryBlock> recovered = emergencyRepository.load();
            if (recovered.isEmpty()) return;
            emergencyRecovery = Map.copyOf(recovered);
            tracked.putAll(recovered);
            plugin.getLogger().warning("Loaded " + recovered.size()
                    + " temporary blocks from the emergency rollback journal; "
                    + "new temporary blocks remain disabled until recovery completes.");
        } catch (IOException ex) {
            persistenceHealthy = false;
            emergencyJournalHealthy = false;
            logEmergencyJournalFailure("Emergency temporary-block journal could not be loaded", ex);
        }
    }

    public boolean track(Block block, String originalData, long expiresAt) {
        return track(block, originalData, expiresAt, false);
    }

    public boolean track(Block block, String originalData, long expiresAt, boolean warzoneOwned) {
        if (!acceptingNewTemporaryBlocks() || tracked.size() >= maxTracked) return false;
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

    public int emergencyRecoveryCount() { return emergencyRecovery.size(); }

    public boolean acceptingNewTemporaryBlocks() {
        return !shuttingDown && persistenceHealthy && emergencyJournalHealthy
                && !emergencyJournalDirty && emergencyRecovery.isEmpty();
    }

    private boolean emergencyRecoveryPending() {
        return emergencyJournalDirty || !emergencyRecovery.isEmpty();
    }

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

    public List<TraceEvent> recentTrace(int limit) {
        int requested = Math.max(0, limit);
        int skip = Math.max(0, traceEvents.size() - requested);
        return traceEvents.stream().skip(skip).toList();
    }

    public List<ActiveDiagnostic> activeDiagnostics(long now, int limit) {
        List<ActiveDiagnostic> diagnostics = new ArrayList<>();
        int maximum = Math.max(0, limit);
        for (TemporaryBlock entry : tracked.values()) {
            if (diagnostics.size() >= maximum) break;
            World world = world(entry);
            String status;
            String current;
            if (world == null) {
                status = validWorldUuid(entry) ? "WORLD_UNAVAILABLE" : "INVALID_WORLD_UUID";
                current = WORLD_UNAVAILABLE_DATA;
            } else if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
                status = "WAITING_UNLOADED_CHUNK";
                current = "<chunk-unloaded>";
            } else {
                status = entry.expiresAt() <= now ? "EXPIRED_LOADED" : "TRACKED";
                current = currentBlockData(world, entry);
            }
            diagnostics.add(new ActiveDiagnostic(status, entry.worldUuid(), entry.x(), entry.y(),
                    entry.z(), entry.expectedBlockData(), current, entry.originalBlockData(),
                    entry.expiresAt(), entry.pendingClear()));
        }
        return List.copyOf(diagnostics);
    }

    public void shutdown() {
        shuttingDown = true;
        if (ticker != null) ticker.cancel();
        if (persistenceHealthy && !emergencyRecoveryPending()) {
            persist();
            return;
        }
        rollbackUndurable();
        logEmergencyPendingIfNeeded(true);
    }

    /**
     * Runs after the ordered writer has terminated so a failure discovered during shutdown still
     * receives one additional bounded physical rollback pass before runtime memory is discarded.
     */
    public void finishShutdownRecovery() {
        if (persistenceHealthy && !emergencyRecoveryPending()) return;
        rollbackUndurable();
        logEmergencyPendingIfNeeded(true);
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
            if (emergencyRecovery.containsKey(trackedEntry.getKey())) continue;
            World world = world(entry);
            if (world == null) {
                if (!validWorldUuid(entry)) {
                    remove(iterator, entry, TerminalReason.WORLD_MISSING, WORLD_UNAVAILABLE_DATA);
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
                remove(iterator, entry, outcome.reason(), outcome.observedCurrentBlockData());
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
            Map.Entry<String, TemporaryBlock> trackedEntry = iterator.next();
            if (emergencyRecovery.containsKey(trackedEntry.getKey())) continue;
            TemporaryBlock entry = trackedEntry.getValue();
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (matchesManagedBlock(block, entry)) continue;
            remove(iterator, entry, TerminalReason.CURRENT_BLOCK_DIFFERENT,
                    block.getBlockData().getAsString(true));
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
            Map.Entry<String, TemporaryBlock> trackedEntry = iterator.next();
            if (emergencyRecovery.containsKey(trackedEntry.getKey())) continue;
            TemporaryBlock entry = trackedEntry.getValue();
            if (!selected.test(entry)) continue;
            World world = world(entry);
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) continue;
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (!matchesRecordedData(block, entry.originalBlockData(), false)) continue;
            remove(iterator, entry, TerminalReason.RESET_CONFIRMED,
                    block.getBlockData().getAsString(true));
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
            Map.Entry<String, TemporaryBlock> trackedEntry = iterator.next();
            if (emergencyRecovery.containsKey(trackedEntry.getKey())) continue;
            TemporaryBlock entry = trackedEntry.getValue();
            if (!selected.test(entry)) continue;
            remove(iterator, entry, TerminalReason.EXPLICIT_DISCARD, currentBlockData(entry));
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
        if (emergencyRecoveryPending()) rollbackUndurable();
        if (expireEligibleEntries(now)) persist();
    }

    private boolean expireEligibleEntries(long now) {
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TemporaryBlock> trackedEntry = iterator.next();
            if (emergencyRecovery.containsKey(trackedEntry.getKey())) continue;
            TemporaryBlock entry = trackedEntry.getValue();
            if (isDue(entry, now)) changed |= expireEntry(iterator, entry);
        }
        return changed;
    }

    private boolean isDue(TemporaryBlock entry, long now) {
        return entry.pendingClear() || entry.expiresAt() <= now;
    }

    private boolean expireEntry(java.util.Iterator<?> iterator, TemporaryBlock entry) {
        World world = world(entry);
        if (world == null) return discardInvalidWorld(iterator, entry);
        if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) return false;
        return restoreAndRemove(iterator, world, entry, restorationReason(entry));
    }

    private boolean discardInvalidWorld(java.util.Iterator<?> iterator, TemporaryBlock entry) {
        if (validWorldUuid(entry)) return false;
        remove(iterator, entry, TerminalReason.WORLD_MISSING, WORLD_UNAVAILABLE_DATA);
        return true;
    }

    private TerminalReason restorationReason(TemporaryBlock entry) {
        return entry.pendingClear()
                ? TerminalReason.CLEAR_RESTORED : TerminalReason.EXPIRED_RESTORED;
    }

    private boolean restoreAndRemove(java.util.Iterator<?> iterator, World world,
                                     TemporaryBlock entry, TerminalReason reason) {
        RestoreOutcome outcome = restoreLoaded(world, entry, reason);
        if (!outcome.terminal()) return false;
        remove(iterator, entry, outcome.reason(), outcome.observedCurrentBlockData());
        return true;
    }

    void processAvailable(World world, int chunkX, int chunkZ, long now) {
        if (emergencyRecoveryPending()) rollbackUndurable();
        if (restoreAvailableEntries(world, chunkX, chunkZ, now)) persist();
    }

    private boolean restoreAvailableEntries(World world, int chunkX, int chunkZ, long now) {
        boolean changed = false;
        var iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TemporaryBlock> trackedEntry = iterator.next();
            if (emergencyRecovery.containsKey(trackedEntry.getKey())) continue;
            TemporaryBlock entry = trackedEntry.getValue();
            if (availableInChunk(entry, world, chunkX, chunkZ, now))
                changed |= restoreAndRemove(iterator, world, entry, restorationReason(entry));
        }
        return changed;
    }

    private boolean availableInChunk(TemporaryBlock entry, World world,
                                     int chunkX, int chunkZ, long now) {
        return entry.worldUuid().equals(world.getUID().toString())
                && (entry.x() >> 4) == chunkX
                && (entry.z() >> 4) == chunkZ
                && isDue(entry, now);
    }

    private RestoreOutcome restoreLoaded(World world, TemporaryBlock entry,
                                         TerminalReason restoredReason) {
        Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
        String observedCurrent = block.getBlockData().getAsString(true);
        if (!matchesManagedBlock(block, entry))
            return RestoreOutcome.terminal(TerminalReason.CURRENT_BLOCK_DIFFERENT,
                    observedCurrent);
        try {
            block.setBlockData(blockDataFactory.apply(entry.originalBlockData()), false);
            return RestoreOutcome.terminal(restoredReason, observedCurrent);
        } catch (RuntimeException ex) {
            recordTrace(entry, "RESTORE_RETRY", observedCurrent);
            plugin.getLogger().warning("Could not restore temporary block at "
                    + block.getLocation() + ": " + ex.getMessage());
            return RestoreOutcome.retry(observedCurrent);
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
        try {
            return worldLookup.apply(UUID.fromString(entry.worldUuid()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean validWorldUuid(TemporaryBlock entry) {
        try {
            UUID.fromString(entry.worldUuid());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String currentBlockData(TemporaryBlock entry) {
        World world = world(entry);
        if (world == null) return WORLD_UNAVAILABLE_DATA;
        if (!world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) return "<chunk-unloaded>";
        return currentBlockData(world, entry);
    }

    private String currentBlockData(World world, TemporaryBlock entry) {
        try {
            return world.getBlockAt(entry.x(), entry.y(), entry.z())
                    .getBlockData().getAsString(true);
        } catch (RuntimeException ex) {
            return "<block-data-unavailable>";
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
            snapshotPending = true;
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
                        List.of(), Map.copyOf(tracked));
            }
        }
        return completion;
    }

    private void drainPersistenceQueue() {
        while (true) {
            Map<String, TemporaryBlock> snapshot;
            List<CompletableFuture<Void>> completions;
            synchronized (persistenceLock) {
                if (!snapshotPending) {
                    writerScheduled = false;
                    return;
                }
                snapshot = pendingSnapshot;
                pendingSnapshot = Map.of();
                snapshotPending = false;
                completions = List.copyOf(pendingWriteCompletions);
                pendingWriteCompletions.clear();
            }
            try {
                repository.save(snapshot);
                durableSnapshot = snapshot;
                completions.forEach(completion -> completion.complete(null));
            } catch (IOException ex) {
                failPersistence("Temporary block state could not be committed; "
                        + "no further blocks will be tracked", ex, completions, snapshot);
                return;
            }
        }
    }

    private void failPersistence(String prefix, Exception ex,
                                 List<CompletableFuture<Void>> activeCompletions,
                                 Map<String, TemporaryBlock> failedSnapshot) {
        List<CompletableFuture<Void>> failed = new ArrayList<>(activeCompletions);
        Map<String, TemporaryBlock> latestSnapshot;
        synchronized (persistenceLock) {
            persistenceHealthy = false;
            latestSnapshot = snapshotPending ? pendingSnapshot : failedSnapshot;
            writerScheduled = false;
            pendingSnapshot = Map.of();
            snapshotPending = false;
            failed.addAll(pendingWriteCompletions);
            pendingWriteCompletions.clear();
        }
        captureUndurableEntries(latestSnapshot);
        checkpointEmergencyJournal();
        logPersistenceFailure(prefix, ex);
        failed.forEach(completion -> completion.completeExceptionally(ex));
        scheduleRollbackUndurable();
    }

    // This LinkedHashMap is a thread-confined ordered copy, not shared concurrent state.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private void captureUndurableEntries(Map<String, TemporaryBlock> latestSnapshot) {
        Map<String, TemporaryBlock> recovery = new LinkedHashMap<>(emergencyRecovery);
        for (Map.Entry<String, TemporaryBlock> entry : latestSnapshot.entrySet()) {
            TemporaryBlock durable = durableSnapshot.get(entry.getKey());
            if (!entry.getValue().equals(durable)) recovery.put(entry.getKey(), entry.getValue());
        }
        emergencyRecovery = Map.copyOf(recovery);
        emergencyJournalDirty = true;
    }

    private boolean checkpointEmergencyJournal() {
        try {
            emergencyRepository.saveAtomically(emergencyRecovery);
            emergencyJournalHealthy = true;
            emergencyJournalDirty = false;
            return true;
        } catch (IOException | RuntimeException ex) {
            emergencyJournalHealthy = false;
            emergencyJournalDirty = true;
            logEmergencyJournalFailure("Emergency temporary-block journal could not be committed",
                    ex);
            return false;
        }
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
            plugin.getLogger().severe("Could not schedule emergency rollback of temporary blocks: "
                    + ex.getMessage());
        }
    }

    /**
     * Bounded main-thread recovery for entries accepted after the last successful durable primary
     * snapshot. Healthy TTL cleanup never calls this for ordinary unloaded entries.
     */
    public int rollbackUndurable() {
        RecoveryPass pass = beginRecoveryPass();
        if (pass == null) return 0;
        for (Map.Entry<ChunkKey, List<RecoveryEntry>> chunkEntry : pass.chunks.entrySet()) {
            if (pass.limitReached()) break;
            recoverChunk(pass, chunkEntry.getKey(), chunkEntry.getValue());
        }
        finishRecoveryPass(pass);
        return pass.removed;
    }

    private RecoveryPass beginRecoveryPass() {
        Map<String, TemporaryBlock> recovery = emergencyRecovery;
        if (recovery.isEmpty()) {
            if (emergencyJournalDirty) {
                if (checkpointEmergencyJournal())
                    plugin.getLogger().info("Emergency temporary-block journal checkpoint "
                            + "completed; new placements may resume when persistence is healthy.");
                else
                    logEmergencyPendingIfNeeded(false);
            }
            return null;
        }
        logEmergencyRecoveryStarted(recovery.size());
        return createRecoveryPass(recovery);
    }

    // LinkedHashMap preserves deterministic bounded retry order; this state is main-thread confined.
    private RecoveryPass createRecoveryPass(Map<String, TemporaryBlock> recovery) {
        return new RecoveryPass(recovery, scanEmergencyChunks(recovery),
                new LinkedHashMap<>(recovery));
    }

    private void recoverChunk(RecoveryPass pass, ChunkKey chunk,
                              List<RecoveryEntry> entries) {
        World world = world(chunk.worldUuid());
        if (world == null) {
            rotatePending(pass.remaining, entries);
            return;
        }
        ChunkLease lease = acquireChunk(world, chunk);
        if (!lease.available()) {
            rotatePending(pass.remaining, entries);
            return;
        }
        pass.processedChunks++;
        try {
            recoverChunkEntries(pass, world, entries);
        } finally {
            releaseChunk(world, chunk, lease);
        }
    }

    private void recoverChunkEntries(RecoveryPass pass, World world,
                                     List<RecoveryEntry> entries) {
        for (RecoveryEntry recoveryEntry : entries) {
            if (pass.entryLimitReached()) return;
            recoverEntry(pass, world, recoveryEntry);
        }
    }

    private void recoverEntry(RecoveryPass pass, World world, RecoveryEntry recoveryEntry) {
        TemporaryBlock currentEntry = tracked.get(recoveryEntry.key());
        if (currentEntry == null) {
            pass.remaining.remove(recoveryEntry.key());
            return;
        }
        pass.processedEntries++;
        RestoreOutcome outcome = restoreLoaded(world, currentEntry,
                TerminalReason.PERSISTENCE_ROLLBACK);
        if (!outcome.terminal()) return;
        removeRecoveryEntry(recoveryEntry.key(), currentEntry, outcome);
        pass.remaining.remove(recoveryEntry.key());
        pass.removed++;
    }

    private void finishRecoveryPass(RecoveryPass pass) {
        Map<String, TemporaryBlock> remaining = Map.copyOf(pass.remaining);
        if (!remaining.equals(pass.originalRecovery)) emergencyJournalDirty = true;
        emergencyRecovery = remaining;
        if (pass.removed > 0 && persistenceHealthy) persist();
        if (emergencyJournalDirty && !checkpointEmergencyJournal()) {
            logEmergencyPendingIfNeeded(false);
            return;
        }
        if (remaining.isEmpty()) {
            plugin.getLogger().info("Emergency temporary-block rollback completed; "
                    + "the recovery journal is clear.");
        } else {
            logEmergencyPendingIfNeeded(false);
        }
    }

    // Chunk insertion is isolated so the bounded scan loop performs no direct allocations.
    private LinkedHashMap<ChunkKey, List<RecoveryEntry>> scanEmergencyChunks(
            Map<String, TemporaryBlock> recovery) {
        LinkedHashMap<ChunkKey, List<RecoveryEntry>> chunks = new LinkedHashMap<>();
        int scanned = 0;
        for (Map.Entry<String, TemporaryBlock> entry : recovery.entrySet()) {
            if (scanned >= EMERGENCY_SCAN_LIMIT) break;
            addEmergencyChunk(chunks, entry);
            scanned++;
        }
        return chunks;
    }

    private void addEmergencyChunk(Map<ChunkKey, List<RecoveryEntry>> chunks,
                                   Map.Entry<String, TemporaryBlock> entry) {
        TemporaryBlock block = entry.getValue();
        ChunkKey chunk = new ChunkKey(block.worldUuid(), block.x() >> 4, block.z() >> 4);
        chunks.computeIfAbsent(chunk, ignored -> createRecoveryEntryList())
                .add(new RecoveryEntry(entry.getKey(), block));
    }

    private List<RecoveryEntry> createRecoveryEntryList() {
        return new ArrayList<>();
    }

    private void rotatePending(LinkedHashMap<String, TemporaryBlock> remaining,
                               List<RecoveryEntry> entries) {
        for (RecoveryEntry entry : entries) {
            TemporaryBlock value = remaining.remove(entry.key());
            if (value != null) remaining.put(entry.key(), value);
        }
    }

    private World world(String serializedUuid) {
        try {
            return worldLookup.apply(UUID.fromString(serializedUuid));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ChunkLease acquireChunk(World world, ChunkKey chunk) {
        if (world.isChunkLoaded(chunk.x(), chunk.z())) return ChunkLease.ALREADY_LOADED;
        try {
            boolean ownedTicket = world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin);
            if (world.isChunkLoaded(chunk.x(), chunk.z()))
                return new ChunkLease(true, ownedTicket);
            if (ownedTicket) world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
            plugin.getLogger().warning("Emergency rollback could not load chunk "
                    + chunk.description() + "; recovery remains pending.");
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Emergency rollback could not acquire chunk "
                    + chunk.description() + ": " + ex.getMessage());
        }
        return ChunkLease.UNAVAILABLE;
    }

    private void releaseChunk(World world, ChunkKey chunk, ChunkLease lease) {
        if (!lease.ownedTicket()) return;
        try {
            if (!world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin))
                plugin.getLogger().severe("Emergency rollback could not release its chunk ticket for "
                        + chunk.description() + ".");
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Emergency rollback could not release its chunk ticket for "
                    + chunk.description() + ": " + ex.getMessage());
        }
    }

    private void removeRecoveryEntry(String key, TemporaryBlock entry, RestoreOutcome outcome) {
        recordTrace(entry, outcome.reason().name(), outcome.observedCurrentBlockData());
        tracked.remove(key);
        terminalReasons.merge(outcome.reason(), 1L, Long::sum);
    }

    private void logEmergencyRecoveryStarted(int count) {
        if (emergencyRecoveryStartedLogged) return;
        emergencyRecoveryStartedLogged = true;
        plugin.getLogger().warning("Emergency temporary-block rollback started for " + count
                + " entr" + (count == 1 ? "y" : "ies")
                + "; recovery is bounded and may continue across ticks or restart.");
    }

    private void logEmergencyPendingIfNeeded(boolean force) {
    int pending = emergencyRecovery.size();
    long now = System.currentTimeMillis();
    if (!shouldLogEmergencyPending(force, pending, now)) return;
    lastEmergencyPendingLog = now;
    if (pending == 0) {
        logEmergencyJournalCheckpointPending();
        return;
    }
    logEmergencyEntriesPending(pending);
}

private boolean shouldLogEmergencyPending(boolean force, int pending, long now) {
    if (pending == 0 && !emergencyJournalDirty) return false;
    return force
            || now - lastEmergencyPendingLog >= EMERGENCY_PENDING_LOG_INTERVAL_MILLIS;
}

private void logEmergencyJournalCheckpointPending() {
    plugin.getLogger().warning("Emergency temporary-block physical rollback is complete, "
            + "but the recovery journal checkpoint remains pending; new temporary blocks "
            + "remain disabled.");
}

private void logEmergencyEntriesPending(int pending) {
    plugin.getLogger().warning("Emergency temporary-block rollback remains pending for "
            + pending + " " + recoveryEntryNoun(pending) + " ("
            + emergencyJournalStatus()
            + "). Worlds or chunks will be retried without permanent force-loading.");
}

private String emergencyJournalStatus() {
    return emergencyJournalHealthy && !emergencyJournalDirty
            ? "durably journaled" : "journal checkpoint pending";
}

private String recoveryEntryNoun(int pending) {
    return pending == 1 ? "entry" : "entries";
}

    private void remove(java.util.Iterator<?> iterator, TemporaryBlock entry,
                        TerminalReason reason, String observedCurrentBlockData) {
        recordTrace(entry, reason.name(), observedCurrentBlockData);
        iterator.remove();
        terminalReasons.merge(reason, 1L, Long::sum);
        removeFromEmergencyRecovery(entry);
    }

    // This LinkedHashMap is a main-thread-confined ordered copy, not shared concurrent state.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private void removeFromEmergencyRecovery(TemporaryBlock entry) {
        String entryKey = key(entry);
        if (!emergencyRecovery.containsKey(entryKey)) return;
        Map<String, TemporaryBlock> remaining = new LinkedHashMap<>(emergencyRecovery);
        remaining.remove(entryKey);
        emergencyRecovery = Map.copyOf(remaining);
        emergencyJournalDirty = true;
        if (!checkpointEmergencyJournal()) logEmergencyPendingIfNeeded(false);
    }

    private void recordTrace(TemporaryBlock entry, String reason,
                             String observedCurrentBlockData) {
        TraceEvent event = new TraceEvent(System.currentTimeMillis(), reason, entry.worldUuid(),
                entry.x(), entry.y(), entry.z(), entry.expectedBlockData(),
                observedCurrentBlockData, entry.originalBlockData(), entry.expiresAt(),
                entry.pendingClear());
        TraceEvent previous = traceEvents.peekLast();
        if (previous != null && previous.sameOutcome(event)) return;
        if (traceEvents.size() >= TRACE_LIMIT) traceEvents.removeFirst();
        traceEvents.addLast(event);
    }

    private void logPersistenceFailure(String prefix, Exception ex) {
        if (persistenceFailureLogged) return;
        persistenceFailureLogged = true;
        plugin.getLogger().severe(prefix + ": " + ex.getMessage());
    }

    private void logEmergencyJournalFailure(String prefix, Exception ex) {
        if (emergencyJournalFailureLogged) return;
        emergencyJournalFailureLogged = true;
        plugin.getLogger().severe(prefix + ": " + ex.getMessage()
                + ". Physical rollback will continue while the runtime is available.");
    }

    private String key(TemporaryBlock entry) {
        return entry.worldUuid() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z();
    }

    public record Diagnostics(int tracked, long expired, long pendingClear,
                              boolean persistenceHealthy, int capacityCurrent,
                              int capacityMaximum) { }

    public record ActiveDiagnostic(String status, String worldUuid, int x, int y, int z,
                                   String expectedBlockData, String currentBlockData,
                                   String originalBlockData, long expiresAt,
                                   boolean pendingClear) { }

    public record TraceEvent(long observedAt, String reason, String worldUuid,
                             int x, int y, int z, String expectedBlockData,
                             String currentBlockData, String originalBlockData,
                             long expiresAt, boolean pendingClear) {
        private boolean sameOutcome(TraceEvent other) {
            return reason.equals(other.reason) && worldUuid.equals(other.worldUuid)
                    && x == other.x && y == other.y && z == other.z
                    && currentBlockData.equals(other.currentBlockData)
                    && pendingClear == other.pendingClear;
        }
    }

    public enum TerminalReason {
        EXPIRED_RESTORED,
        CLEAR_RESTORED,
        CURRENT_BLOCK_DIFFERENT,
        WORLD_MISSING,
        RESET_CONFIRMED,
        PERSISTENCE_ROLLBACK,
        EXPLICIT_DISCARD
    }

    private static final class RecoveryPass {
        private final Map<String, TemporaryBlock> originalRecovery;
        private final LinkedHashMap<ChunkKey, List<RecoveryEntry>> chunks;
        private final LinkedHashMap<String, TemporaryBlock> remaining;
        private int processedChunks;
        private int processedEntries;
        private int removed;

        private RecoveryPass(Map<String, TemporaryBlock> originalRecovery,
                             LinkedHashMap<ChunkKey, List<RecoveryEntry>> chunks,
                             LinkedHashMap<String, TemporaryBlock> remaining) {
            this.originalRecovery = originalRecovery;
            this.chunks = chunks;
            this.remaining = remaining;
        }

        private boolean limitReached() {
            return processedChunks >= EMERGENCY_CHUNKS_PER_PASS || entryLimitReached();
        }

        private boolean entryLimitReached() {
            return processedEntries >= EMERGENCY_ENTRIES_PER_PASS;
        }
    }

    private record ChunkKey(String worldUuid, int x, int z) {
        private String description() { return worldUuid + " [" + x + "," + z + "]"; }
    }

    private record RecoveryEntry(String key, TemporaryBlock entry) { }

    private record ChunkLease(boolean available, boolean ownedTicket) {
        private static final ChunkLease ALREADY_LOADED = new ChunkLease(true, false);
        private static final ChunkLease UNAVAILABLE = new ChunkLease(false, false);
    }

    private record RestoreOutcome(boolean terminal, TerminalReason reason,
                                  String observedCurrentBlockData) {
        static RestoreOutcome terminal(TerminalReason reason, String observedCurrentBlockData) {
            return new RestoreOutcome(true, reason, observedCurrentBlockData);
        }

        static RestoreOutcome retry(String observedCurrentBlockData) {
            return new RestoreOutcome(false, null, observedCurrentBlockData);
        }
    }
}
