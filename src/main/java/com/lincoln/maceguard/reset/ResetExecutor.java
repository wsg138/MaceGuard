package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.storage.ResetJournalRepository;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class ResetExecutor {
    private final JavaPlugin plugin;
    private final Executor io;
    private final ResetJournalRepository journals;
    private final int batchSize;
    private final BlockStateCodec codec = new BlockStateCodec();

    public ResetExecutor(JavaPlugin plugin, Executor io, ResetJournalRepository journals, int batchSize) {
        this.plugin = plugin; this.io = io; this.journals = journals; this.batchSize = batchSize;
    }

    public void execute(World world, ResetPlan plan, Consumer<String> done, Consumer<String> lockRestores) {
        String operation = UUID.randomUUID().toString();
        ResetJournal prepared = journal(operation, plan, ResetJournal.Status.PREPARED, 0);
        io.execute(() -> {
            try {
                if (!journals.savePreparedIfNoUnresolved(prepared)) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        lockRestores.accept("an unresolved restore journal requires administrator review");
                        done.accept("Reset refused: an unresolved restore journal already exists.");
                    });
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> runBatches(world, plan, prepared, done, lockRestores));
            }
            catch (IOException ex) { plugin.getServer().getScheduler().runTask(plugin, () -> done.accept("Reset refused: could not persist restore journal: " + ex.getMessage())); }
        });
    }

    private void runBatches(World world, ResetPlan plan, ResetJournal initial, Consumer<String> done, Consumer<String> lockRestores) {
        new BukkitRunnable() {
            int index;
            boolean waiting;
            @Override public void run() {
                if (waiting) return;
                try {
                    int end = Math.min(plan.changes().size(), index + batchSize);
                    for (; index < end; index++) {
                        ResetPlan.Change change = plan.changes().get(index);
                        if (!world.isChunkLoaded(change.x() >> 4, change.z() >> 4)) throw new IllegalStateException("chunk unloaded during restore");
                        var block = world.getBlockAt(change.x(), change.y(), change.z());
                        if (!codec.sameState(change.before(), codec.capture(block))) throw new IllegalStateException("world state changed after preflight at " + change.x() + "," + change.y() + "," + change.z());
                        codec.restore(block, change.target());
                    }
                    ResetJournal.Status status = index == plan.changes().size() ? ResetJournal.Status.COMPLETE : ResetJournal.Status.RESTORING;
                    ResetJournal progress = journal(initial.operationId(), plan, status, index);
                    waiting = true;
                    io.execute(() -> {
                        try {
                            journals.save(progress);
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                waiting = false;
                                if (status == ResetJournal.Status.COMPLETE) { cancel(); done.accept("Reset completed: " + plan.regionId() + " changed " + plan.totalChanges() + " blocks."); }
                            });
                        } catch (IOException ex) { plugin.getServer().getScheduler().runTask(plugin, () -> fail("journal update failed: " + ex.getMessage())); }
                    });
                } catch (RuntimeException ex) { fail(ex.getMessage()); }
            }

            private void fail(String reason) {
                cancel();
                ResetJournal failed = journal(initial.operationId(), plan, ResetJournal.Status.FAILED, index);
                lockRestores.accept("restore " + initial.operationId() + " failed at " + index + "/" + plan.totalChanges() + ": " + reason);
                io.execute(() -> { try { journals.save(failed); } catch (IOException ignored) { } });
                done.accept("Reset interrupted and remains journaled: " + reason);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private ResetJournal journal(String id, ResetPlan plan, ResetJournal.Status status, int next) {
        return new ResetJournal(id, plan.worldUuid(), plan.regionId(), plan.geometryHash(), plan.snapshotChecksum(), plan.planHash(), status, next, plan.totalChanges(), System.currentTimeMillis());
    }
}
