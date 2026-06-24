package com.lincoln.maceguard.core.service;

import java.util.concurrent.atomic.LongAdder;

public final class PerformanceCounters {
    private final LongAdder zoneQueries = new LongAdder();
    private final LongAdder protectedChecks = new LongAdder();
    private final LongAdder skippedEvents = new LongAdder();
    private final LongAdder liquidEvents = new LongAdder();
    private final LongAdder liquidSkippedCount = new LongAdder();
    private final LongAdder explosionBlocksScannedCount = new LongAdder();
    private final LongAdder explosionBlocksRemovedCount = new LongAdder();
    private final LongAdder resetBlocksProcessedCount = new LongAdder();
    private final LongAdder drainQueuePeak = new LongAdder();
    private final LongAdder snapshotLoadMillis = new LongAdder();
    private final LongAdder snapshotSaveMillis = new LongAdder();
    private final LongAdder snapshotLoadFailureCount = new LongAdder();
    private final LongAdder snapshotSaveFailureCount = new LongAdder();
    private final LongAdder backstopRepairCount = new LongAdder();
    private final LongAdder reloadTaskRestarts = new LongAdder();

    public void zoneQuery() {
        zoneQueries.increment();
    }

    public void protectedCheck() {
        protectedChecks.increment();
    }

    public void skippedEvent() {
        skippedEvents.increment();
    }

    public void liquidEvent() {
        liquidEvents.increment();
    }

    public void liquidSkipped() {
        liquidSkippedCount.increment();
    }

    public void explosionBlocksScanned(int count) {
        explosionBlocksScannedCount.add(count);
    }

    public void explosionBlocksRemoved(int count) {
        explosionBlocksRemovedCount.add(count);
    }

    public void resetBlocksProcessed(int count) {
        resetBlocksProcessedCount.add(count);
    }

    public void drainQueueSize(int size) {
        drainQueuePeak.add(size);
    }

    public void snapshotLoad(long millis, boolean success) {
        snapshotLoadMillis.add(Math.max(0L, millis));
        if (!success) {
            snapshotLoadFailureCount.increment();
        }
    }

    public void snapshotSave(long millis, boolean success) {
        snapshotSaveMillis.add(Math.max(0L, millis));
        if (!success) {
            snapshotSaveFailureCount.increment();
        }
    }

    public void backstopRepair() {
        backstopRepairCount.increment();
    }

    public void reloadTaskRestart() {
        reloadTaskRestarts.increment();
    }

    public long snapshotLoadFailures() {
        return snapshotLoadFailureCount.sum();
    }

    public long snapshotSaveFailures() {
        return snapshotSaveFailureCount.sum();
    }

    public long backstopRepairs() {
        return backstopRepairCount.sum();
    }

    public String summary() {
        return "zoneQueries=" + zoneQueries.sum()
                + ", protectedChecks=" + protectedChecks.sum()
                + ", skippedEvents=" + skippedEvents.sum()
                + ", liquidEvents=" + liquidEvents.sum()
                + ", liquidSkipped=" + liquidSkippedCount.sum()
                + ", explosionBlocksScanned=" + explosionBlocksScannedCount.sum()
                + ", explosionBlocksRemoved=" + explosionBlocksRemovedCount.sum()
                + ", resetBlocksProcessed=" + resetBlocksProcessedCount.sum()
                + ", drainQueueTotal=" + drainQueuePeak.sum()
                + ", snapshotLoadMillis=" + snapshotLoadMillis.sum()
                + ", snapshotSaveMillis=" + snapshotSaveMillis.sum()
                + ", snapshotLoadFailures=" + snapshotLoadFailureCount.sum()
                + ", snapshotSaveFailures=" + snapshotSaveFailureCount.sum()
                + ", backstopRepairs=" + backstopRepairCount.sum()
                + ", reloadTaskRestarts=" + reloadTaskRestarts.sum();
    }
}
