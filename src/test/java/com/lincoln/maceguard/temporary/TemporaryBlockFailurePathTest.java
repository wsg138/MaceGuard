package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.storage.TemporaryBlockRepository;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporaryBlockFailurePathTest {
    private static final String AIR_DATA = "minecraft:air";
    private static final String COBWEB_DATA = "minecraft:cobweb";

    @TempDir Path directory;

    private JavaPlugin plugin;
    private World world;
    private Block block;
    private UUID worldUuid;
    private Material material;
    private String serialized;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TemporaryBlockFailurePathTest"));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return task;
        });

        worldUuid = UUID.randomUUID();
        world = mock(World.class);
        block = mock(Block.class);
        material = Material.COBWEB;
        serialized = COBWEB_DATA;
        when(world.getUID()).thenReturn(worldUuid);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(4, 64, 4)).thenReturn(block);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(4);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(4);
        when(block.getType()).thenAnswer(ignored -> material);
        when(block.getBlockData()).thenAnswer(ignored -> blockData(serialized));
        when(block.getLocation()).thenReturn(new Location(world, 4, 64, 4));
        doAnswer(invocation -> {
            BlockData replacement = invocation.getArgument(0);
            material = replacement.getMaterial();
            serialized = replacement.getAsString(true);
            return null;
        }).when(block).setBlockData(any(BlockData.class), eq(false));
    }

    @Test
    void asynchronousPersistenceFailureRollsBackPreviouslyAcceptedPlacement() throws Exception {
        Path invalidParent = directory.resolve("not-a-directory");
        Files.writeString(invalidParent, "blocker");
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockService service = new TemporaryBlockService(plugin,
                new TemporaryBlockRepository(invalidParent.resolve("temporary.json")), io, 10,
                uuid -> uuid.equals(worldUuid) ? world : null, this::blockData, false);

        assertTrue(service.track(block, AIR_DATA, 100L));
        assertEquals(1, service.count());

        io.runAll();

        assertFalse(service.persistenceHealthy());
        assertEquals(0, service.count());
        assertEquals(Material.AIR, material);
        assertEquals(1L, service.terminalReasonCounts()
                .get(TemporaryBlockService.TerminalReason.PERSISTENCE_ROLLBACK));
        assertEquals("PERSISTENCE_ROLLBACK", service.recentTrace(1).getFirst().reason());
    }

    @Test
    void transientRestorationFailureRetainsEntryAndRetries() {
        AtomicBoolean firstRestore = new AtomicBoolean(true);
        Function<String, BlockData> factory = value -> {
            if (value.equals(AIR_DATA) && firstRestore.getAndSet(false))
                throw new IllegalArgumentException("synthetic transient failure");
            return blockData(value);
        };
        TemporaryBlockService service = new TemporaryBlockService(plugin,
                new TemporaryBlockRepository(directory.resolve("retry.json")), Runnable::run, 10,
                uuid -> uuid.equals(worldUuid) ? world : null, factory, false);
        assertTrue(service.track(block, AIR_DATA, 10L));

        service.expireNow(11L);
        assertEquals(1, service.count());
        assertEquals(Material.COBWEB, material);
        assertEquals("RESTORE_RETRY", service.recentTrace(1).getFirst().reason());

        service.expireNow(12L);
        assertEquals(0, service.count());
        assertEquals(Material.AIR, material);
        assertEquals(1L, service.terminalReasonCounts()
                .get(TemporaryBlockService.TerminalReason.EXPIRED_RESTORED));
        assertEquals("EXPIRED_RESTORED", service.recentTrace(1).getFirst().reason());
    }

    @Test
    void terminalTraceIncludesExactBlockAndEntryContext() {
        TemporaryBlockService service = service("trace.json");
        assertTrue(service.track(block, AIR_DATA, 10L));
        serialized = "minecraft:cobweb[synthetic-default=true]";

        service.expireNow(11L);

        TemporaryBlockService.TraceEvent trace = service.recentTrace(1).getFirst();
        assertEquals("EXPIRED_RESTORED", trace.reason());
        assertEquals(worldUuid.toString(), trace.worldUuid());
        assertEquals(4, trace.x());
        assertEquals(64, trace.y());
        assertEquals(4, trace.z());
        assertEquals(COBWEB_DATA, trace.expectedBlockData());
        assertEquals("minecraft:cobweb[synthetic-default=true]", trace.currentBlockData());
        assertEquals(AIR_DATA, trace.originalBlockData());
        assertEquals(10L, trace.expiresAt());
        assertFalse(trace.pendingClear());
    }

    @Test
    void activeDiagnosticsIdentifyUnloadedChunkWaitingState() {
        TemporaryBlockService service = service("active.json");
        assertTrue(service.track(block, AIR_DATA, 10L));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);

        TemporaryBlockService.ActiveDiagnostic diagnostic =
                service.activeDiagnostics(11L, 10).getFirst();

        assertEquals("WAITING_UNLOADED_CHUNK", diagnostic.status());
        assertEquals("<chunk-unloaded>", diagnostic.currentBlockData());
        assertEquals(COBWEB_DATA, diagnostic.expectedBlockData());
        assertEquals(AIR_DATA, diagnostic.originalBlockData());
        assertEquals(10L, diagnostic.expiresAt());
        assertFalse(diagnostic.pendingClear());
    }

    private TemporaryBlockService service(String file) {
        return new TemporaryBlockService(plugin,
                new TemporaryBlockRepository(directory.resolve(file)), Runnable::run, 10,
                uuid -> uuid.equals(worldUuid) ? world : null, this::blockData, false);
    }

    private BlockData blockData(String value) {
        Material valueMaterial = Material.valueOf(TemporaryBlockService.materialName(value));
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(valueMaterial);
        when(data.getAsString(true)).thenReturn(value);
        when(data.matches(any(BlockData.class))).thenAnswer(invocation ->
                invocation.<BlockData>getArgument(0).getMaterial() == valueMaterial);
        return data;
    }

    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override public void execute(Runnable command) { queued.addLast(command); }

        private void runAll() {
            while (!queued.isEmpty()) queued.removeFirst().run();
        }
    }
}
