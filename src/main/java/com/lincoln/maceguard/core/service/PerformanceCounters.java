package com.lincoln.maceguard.core.service;

import java.util.concurrent.atomic.LongAdder;

public final class PerformanceCounters {
    private final LongAdder zoneQueries = new LongAdder();
    private final LongAdder protectedChecks = new LongAdder();
    private final LongAdder skippedEvents = new LongAdder();
    private final LongAdder liquidEvents = new LongAdder();
    private final LongAdder liquidSkipped = new LongAdder();
    private final LongAdder explosionBlocksScanned = new LongAdder();
    private final LongAdder explosionBlocksRemoved = new LongAdder();
    private final LongAdder resetBlocksProcessed = new LongAdder();
    private final LongAdder drainQueuePeak = new LongAdder();
    private final LongAdder snapshotLoadMillis = new LongAdder();
    private final LongAdder snapshotSaveMillis = new LongAdder();
    private final LongAdder snapshotLoadFailures = new LongAdder();
    private final LongAdder snapshotSaveFailures = new LongAdder();
    private final LongAdder backstopRepairs = new LongAdder();
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
        liquidSkipped.increment();
    }

    public void explosionBlocksScanned(int count) {
        explosionBlocksScanned.add(count);
    }

    public void explosionBlocksRemoved(int count) {
        explosionBlocksRemoved.add(count);
    }

    public void resetBlocksProcessed(int count) {
        resetBlocksProcessed.add(count);
    }

    public void drainQueueSize(int size) {
        drainQueuePeak.add(size);
    }

    public void snapshotLoad(long millis, boolean success) {
        snapshotLoadMillis.add(Math.max(0L, millis));
        if (!success) {
            snapshotLoadFailures.increment();
        }
    }

    public void snapshotSave(long millis, boolean success) {
        snapshotSaveMillis.add(Math.max(0L, millis));
        if (!success) {
            snapshotSaveFailures.increment();
        }
    }

    public void backstopRepair() {
        backstopRepairs.increment();
    }

    public void reloadTaskRestart() {
        reloadTaskRestarts.increment();
    }

    public long snapshotLoadFailures() {
        return snapshotLoadFailures.sum();
    }

    public long snapshotSaveFailures() {
        return snapshotSaveFailures.sum();
    }

    public long backstopRepairs() {
        return backstopRepairs.sum();
    }

    public String summary() {
        return "zoneQueries=" + zoneQueries.sum()
                + ", protectedChecks=" + protectedChecks.sum()
                + ", skippedEvents=" + skippedEvents.sum()
                + ", liquidEvents=" + liquidEvents.sum()
                + ", liquidSkipped=" + liquidSkipped.sum()
                + ", explosionBlocksScanned=" + explosionBlocksScanned.sum()
                + ", explosionBlocksRemoved=" + explosionBlocksRemoved.sum()
                + ", resetBlocksProcessed=" + resetBlocksProcessed.sum()
                + ", drainQueueTotal=" + drainQueuePeak.sum()
                + ", snapshotLoadMillis=" + snapshotLoadMillis.sum()
                + ", snapshotSaveMillis=" + snapshotSaveMillis.sum()
                + ", snapshotLoadFailures=" + snapshotLoadFailures.sum()
                + ", snapshotSaveFailures=" + snapshotSaveFailures.sum()
                + ", backstopRepairs=" + backstopRepairs.sum()
                + ", reloadTaskRestarts=" + reloadTaskRestarts.sum();
    }
}
