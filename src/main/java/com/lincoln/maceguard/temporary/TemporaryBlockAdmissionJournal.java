package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import org.bukkit.block.Block;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Synchronous write-ahead admission journal for temporary blocks.
 *
 * <p>The primary temporary-block snapshot remains asynchronous. A placement is acknowledged only
 * after this journal durably contains the complete recovery record. Once a primary snapshot that
 * includes the entry commits, the journal entry is removed. On startup, journal entries are merged
 * into the primary snapshot before {@link TemporaryBlockService} is constructed.</p>
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class TemporaryBlockAdmissionJournal {
    private static final int ABSOLUTE_PENDING_LIMIT = 512;
    private static final int WRITE_ATTEMPTS = 3;

    private final TemporaryBlockRepository repository;
    private final Logger logger;
    private final int capacity;
    private final Object lock = new Object();
    private final Map<String, TemporaryBlock> pending = new LinkedHashMap<>();
    private final Map<String, TemporaryBlock> primaryCommitted = new LinkedHashMap<>();
    private boolean writeFailureLogged;
    private boolean capacityWarningLogged;

    public TemporaryBlockAdmissionJournal(TemporaryBlockRepository repository, Logger logger,
                                          int requestedCapacity) throws IOException {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.capacity = Math.max(1, Math.min(ABSOLUTE_PENDING_LIMIT, requestedCapacity));
        pending.putAll(repository.load());
    }

    /**
     * Replays acknowledged-but-not-yet-compacted admissions into the primary snapshot. The
     * admission file is cleared only after the merged primary snapshot has been forced and replaced
     * atomically.
     */
    public static int recoverIntoPrimary(TemporaryBlockRepository primary,
                                         TemporaryBlockRepository admissions) throws IOException {
        Map<String, TemporaryBlock> journal = admissions.load();
        if (journal.isEmpty()) return 0;
        Map<String, TemporaryBlock> merged = new LinkedHashMap<>(primary.load());
        // The WAL is newer than the primary snapshot for a reused coordinate.
        merged.putAll(journal);
        primary.saveAtomically(merged);
        admissions.saveAtomically(Map.of());
        return journal.size();
    }

    public boolean track(TemporaryBlockService service, Block block, String originalData,
                         long expiresAt, boolean warzoneOwned) {
        reconcile(service);
        TemporaryBlock entry = new TemporaryBlock(block.getWorld().getUID().toString(),
                block.getX(), block.getY(), block.getZ(), block.getBlockData().getAsString(true),
                originalData, expiresAt, false, warzoneOwned);
        if (!admit(entry)) return false;
        boolean tracked = service.track(block, originalData, expiresAt, warzoneOwned);
        if (!tracked) {
            clearWithRetry(entry);
            return false;
        }
        // Queue a completion guaranteed to cover the current service state. The WAL remains
        // authoritative until that primary write is known to have committed.
        service.persistCurrentState().whenComplete((ignored, failure) -> {
            if (failure != null) return;
            markPrimaryCommitted(entry);
            clearWithRetry(entry);
        });
        return true;
    }

    /**
     * Bounded reconciliation for failed primary writes or failed WAL compaction. Call from the
     * Bukkit main thread. Live uncommitted entries remain durable; entries known to be in the
     * primary snapshot, or no longer tracked at all, are removed from the WAL.
     */
    public void reconcile(TemporaryBlockService service) {
        Map<String, TemporaryBlock> snapshot;
        Map<String, TemporaryBlock> committed;
        synchronized (lock) {
            if (pending.isEmpty()) return;
            snapshot = new LinkedHashMap<>(pending);
            committed = new LinkedHashMap<>(primaryCommitted);
        }

        List<String> removable = new ArrayList<>();
        snapshot.forEach((key, entry) -> {
            boolean durableInPrimary = entry.equals(committed.get(key));
            boolean stillTracked = service.countMatching(candidate -> candidate.equals(entry)) > 0;
            if (durableInPrimary || !stillTracked) removable.add(key);
        });
        if (removable.isEmpty()) return;

        synchronized (lock) {
            Map<String, TemporaryBlock> next = new LinkedHashMap<>(pending);
            for (String key : removable) {
                TemporaryBlock expected = snapshot.get(key);
                if (expected != null && expected.equals(next.get(key))) next.remove(key);
            }
            if (next.size() == pending.size()) return;
            if (!saveWithRetry(next,
                    "Temporary-block admission journal reconciliation failed")) return;
            pending.clear();
            pending.putAll(next);
            primaryCommitted.keySet().removeIf(key -> !pending.containsKey(key));
        }
    }

    public int pendingCount() {
        synchronized (lock) { return pending.size(); }
    }

    private boolean admit(TemporaryBlock entry) {
        synchronized (lock) {
            String key = key(entry);
            if (pending.containsKey(key)) return false;
            if (pending.size() >= capacity) {
                if (!capacityWarningLogged) {
                    capacityWarningLogged = true;
                    logger.warning("Temporary-block admission journal reached its bounded capacity "
                            + "of " + capacity + "; new temporary placements are rejected until "
                            + "durable compaction catches up.");
                }
                return false;
            }
            Map<String, TemporaryBlock> next = new LinkedHashMap<>(pending);
            next.put(key, entry);
            if (!saveWithRetry(next,
                    "Temporary-block admission was rejected because its durable journal could not "
                            + "be committed")) return false;
            pending.clear();
            pending.putAll(next);
            capacityWarningLogged = false;
            return true;
        }
    }

    private void markPrimaryCommitted(TemporaryBlock entry) {
        synchronized (lock) {
            if (entry.equals(pending.get(key(entry)))) primaryCommitted.put(key(entry), entry);
        }
    }

    private void clearWithRetry(TemporaryBlock entry) {
        synchronized (lock) {
            String key = key(entry);
            if (!entry.equals(pending.get(key))) return;
            Map<String, TemporaryBlock> next = new LinkedHashMap<>(pending);
            next.remove(key);
            if (!saveWithRetry(next,
                    "Temporary-block admission journal compaction failed; the durable recovery "
                            + "record was retained")) return;
            pending.clear();
            pending.putAll(next);
            primaryCommitted.remove(key);
            capacityWarningLogged = false;
        }
    }

    private boolean saveWithRetry(Map<String, TemporaryBlock> next, String failureMessage) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            try {
                repository.saveAtomically(next);
                writeFailureLogged = false;
                return true;
            } catch (IOException | RuntimeException ex) {
                lastFailure = ex;
            }
        }
        if (!writeFailureLogged && lastFailure != null) {
            writeFailureLogged = true;
            logger.severe(failureMessage + ": " + lastFailure.getMessage());
        }
        return false;
    }

    private static String key(TemporaryBlock entry) {
        return entry.worldUuid() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z();
    }
}
