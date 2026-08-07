package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporaryBlockAdmissionJournalTest {
    private static final String COBWEB = "minecraft:cobweb";
    private static final String AIR = "minecraft:air";

    @TempDir Path directory;

    @Test
    void acceptedQueuedPlacementRecoversWithoutRunningPrimaryWriter() throws Exception {
        JavaPlugin plugin = plugin();
        TemporaryBlockRepository primary = repository("primary.json");
        TemporaryBlockRepository emergency = repository("emergency.json");
        TemporaryBlockRepository admissionFile = repository("admission.json");
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockService service = service(plugin, primary, emergency, io);
        TemporaryBlockAdmissionJournal admissions =
                new TemporaryBlockAdmissionJournal(admissionFile, plugin.getLogger(), 10);
        Block block = block(12);

        assertTrue(admissions.track(service, block, AIR, 10_000L, true));
        assertTrue(primary.load().isEmpty(), "primary write must still be queued");
        assertEquals(1, admissionFile.load().size(), "accepted placement needs a durable WAL");

        assertEquals(1, TemporaryBlockAdmissionJournal.recoverIntoPrimary(primary, admissionFile));
        TemporaryBlockService restarted = service(plugin, primary, emergency, Runnable::run);

        assertEquals(1, restarted.count());
        assertTrue(admissionFile.load().isEmpty());
    }

    @Test
    void shutdownWithBlockedWriterLeavesDurableAdmissionRecord() throws Exception {
        JavaPlugin plugin = plugin();
        TemporaryBlockRepository primary = repository("shutdown-primary.json");
        TemporaryBlockRepository emergency = repository("shutdown-emergency.json");
        TemporaryBlockRepository admissionFile = repository("shutdown-admission.json");
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockService service = service(plugin, primary, emergency, io);
        TemporaryBlockAdmissionJournal admissions =
                new TemporaryBlockAdmissionJournal(admissionFile, plugin.getLogger(), 10);

        assertTrue(admissions.track(service, block(12), AIR, 10_000L, false));
        service.shutdown();

        assertTrue(primary.load().isEmpty());
        assertEquals(1, admissionFile.load().size());
    }

    @Test
    void failedPrimaryPersistIsReconciledAfterEntryBecomesTerminal() throws Exception {
        JavaPlugin plugin = plugin();
        TemporaryBlockRepository admissionFile = repository("failed-persist-admission.json");
        TemporaryBlockAdmissionJournal admissions =
                new TemporaryBlockAdmissionJournal(admissionFile, plugin.getLogger(), 10);
        TemporaryBlockService service = mock(TemporaryBlockService.class);
        when(service.track(any(Block.class), anyString(), anyLong(), anyBoolean()))
                .thenReturn(true);
        when(service.persistCurrentState()).thenReturn(
                CompletableFuture.failedFuture(new IOException("primary unavailable")));
        when(service.countMatching(any())).thenReturn(1, 0);

        assertTrue(admissions.track(service, block(12), AIR, 10_000L, false));
        assertEquals(1, admissions.pendingCount());

        admissions.reconcile(service);
        assertEquals(1, admissions.pendingCount(), "live entry must retain its WAL");
        admissions.reconcile(service);

        assertEquals(0, admissions.pendingCount());
        assertTrue(admissionFile.load().isEmpty(), "terminal stale WAL must be compacted away");
    }

    @Test
    void boundedJournalRejectsGrowthUntilCompactionCatchesUp() throws Exception {
        JavaPlugin plugin = plugin();
        TemporaryBlockRepository admissionFile = repository("bounded-admission.json");
        TemporaryBlockAdmissionJournal admissions =
                new TemporaryBlockAdmissionJournal(admissionFile, plugin.getLogger(), 1);
        TemporaryBlockService service = mock(TemporaryBlockService.class);
        when(service.track(any(Block.class), anyString(), anyLong(), anyBoolean()))
                .thenReturn(true);
        when(service.persistCurrentState()).thenReturn(new CompletableFuture<>());
        when(service.countMatching(any())).thenReturn(1);

        assertTrue(admissions.track(service, block(12), AIR, 10_000L, false));
        assertFalse(admissions.track(service, block(13), AIR, 10_000L, false));

        assertEquals(1, admissions.pendingCount());
        assertEquals(1, admissionFile.load().size());
        verify(service, times(1)).track(any(Block.class), anyString(), anyLong(), anyBoolean());
    }

    private TemporaryBlockService service(JavaPlugin plugin, TemporaryBlockRepository primary,
                                          TemporaryBlockRepository emergency, Executor io) {
        return new TemporaryBlockService(plugin, primary, emergency, io, 10,
                ignored -> null, ignored -> mock(BlockData.class), false);
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TemporaryBlockAdmissionJournalTest"));
        return plugin;
    }

    private Block block(int x) {
        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        BlockData data = mock(BlockData.class);
        when(data.getAsString(true)).thenReturn(COBWEB);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(-7);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }

    private TemporaryBlockRepository repository(String name) {
        return new TemporaryBlockRepository(directory.resolve(name));
    }

    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
    }
}
