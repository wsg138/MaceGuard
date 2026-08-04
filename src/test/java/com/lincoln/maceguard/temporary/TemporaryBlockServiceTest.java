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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
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

class TemporaryBlockServiceTest {
    @TempDir Path directory;

    private JavaPlugin plugin;
    private BukkitScheduler scheduler;
    private BukkitTask task;
    private final Map<UUID, TestWorld> worlds = new HashMap<>();

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TemporaryBlockServiceTest"));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return task;
        });
    }

    @Test
    void rapidPlacementTracksExactlyOneEntryPerCoordinate() {
        TestWorld world = world();
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockService service = service("rapid.json", io, 10_000);

        for (int index = 0; index < 125; index++) {
            TestBlock block = world.block(index, 64, 0, Material.COBWEB, "minecraft:cobweb");
            assertTrue(service.track(block.block, "minecraft:air", 2_000L));
        }

        assertEquals(125, service.count());
        io.runAll();
        assertEquals(125, new TemporaryBlockRepository(directory.resolve("rapid.json")).load().size());
    }

    @Test
    void expirationRestoresEveryRapidlyTrackedCobweb() {
        TestWorld world = world();
        TemporaryBlockService service = service("expire.json", Runnable::run, 10_000);
        List<TestBlock> blocks = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            TestBlock block = world.block(index, 64, 1, Material.COBWEB, "minecraft:cobweb");
            blocks.add(block);
            assertTrue(service.track(block.block, "minecraft:air", 1_000L));
        }

        service.expireNow(1_001L);

        assertEquals(0, service.count());
        assertTrue(blocks.stream().allMatch(block -> block.material == Material.AIR));
        assertEquals(100L, service.terminalReasonCounts()
                .get(TemporaryBlockService.TerminalReason.EXPIRED_RESTORED));
    }

    @Test
    void liveRegressionCannotReportZeroWhileManagedCobwebsRemain() {
        TestWorld world = world();
        TemporaryBlockService service = service("regression.json", Runnable::run, 10_000);
        List<TestBlock> blocks = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            TestBlock block = world.block(index, 70, 2, Material.COBWEB, "minecraft:cobweb");
            blocks.add(block);
            assertTrue(service.track(block.block, "minecraft:air", 60_000L));
        }

        service.expireNow(60_001L);

        long remainingCobwebs = blocks.stream()
                .filter(block -> block.material == Material.COBWEB).count();
        assertEquals(0, service.count());
        assertEquals(0, remainingCobwebs);
    }

    @Test
    void equivalentSerializationDifferenceDoesNotStrandCobweb() {
        TestWorld world = world();
        TemporaryBlockService service = service("serialization.json", Runnable::run, 10);
        TestBlock block = world.block(1, 64, 1, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(block.block, "minecraft:air", 10L));
        block.set(Material.COBWEB, "minecraft:cobweb[synthetic-default=true]");

        service.expireNow(11L);

        assertEquals(Material.AIR, block.material);
        assertEquals(0, service.count());
        assertEquals(1L, service.terminalReasonCounts()
                .get(TemporaryBlockService.TerminalReason.EXPIRED_RESTORED));
    }

    @Test
    void genuinelyPlayerReplacedBlockIsNotOverwritten() {
        TestWorld world = world();
        TemporaryBlockService service = service("stale.json", Runnable::run, 10);
        TestBlock block = world.block(2, 64, 2, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(block.block, "minecraft:air", 10L));
        block.set(Material.STONE, "minecraft:stone");

        service.expireNow(11L);

        assertEquals(Material.STONE, block.material);
        assertEquals(0, service.count());
        assertEquals(1L, service.terminalReasonCounts()
                .get(TemporaryBlockService.TerminalReason.CURRENT_BLOCK_DIFFERENT));
    }

    @Test
    void airRestorationPreservesRecordedData() {
        assertRestores("air.json", "minecraft:air", Material.AIR);
    }

    @Test
    void sourceWaterRestorationPreservesRecordedData() {
        assertRestores("source-water.json", "minecraft:water[level=0]", Material.WATER);
    }

    @Test
    void flowingWaterRestorationPreservesRecordedData() {
        assertRestores("flowing-water.json", "minecraft:water[level=5]", Material.WATER);
    }

    @Test
    void unloadedChunkWaitsAndRestoresOnChunkLoad() {
        TestWorld world = world();
        TemporaryBlockService service = service("chunk.json", Runnable::run, 10);
        TestBlock block = world.block(32, 64, 0, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(block.block, "minecraft:air", 10L));
        world.setChunkLoaded(2, 0, false);

        service.expireNow(11L);
        assertEquals(1, service.count());
        assertEquals(Material.COBWEB, block.material);

        world.setChunkLoaded(2, 0, true);
        service.processAvailable(world.world, 2, 0, 11L);
        assertEquals(0, service.count());
        assertEquals(Material.AIR, block.material);
    }

    @Test
    void restartBeforeExpirationLoadsAndCleansPersistedEntry() {
        TestWorld world = world();
        TemporaryBlockService first = service("restart.json", Runnable::run, 10);
        TestBlock block = world.block(3, 64, 3, Material.COBWEB, "minecraft:cobweb");
        assertTrue(first.track(block.block, "minecraft:air", 100L));
        first.shutdown();

        TemporaryBlockService restarted = service("restart.json", Runnable::run, 10);
        assertEquals(1, restarted.count());
        restarted.expireNow(101L);
        assertEquals(0, restarted.count());
        assertEquals(Material.AIR, block.material);
    }

    @Test
    void reloadBeforeExpirationPreservesTrackingUntilDue() {
        TestWorld world = world();
        TemporaryBlockService first = service("reload.json", Runnable::run, 10);
        TestBlock block = world.block(4, 64, 4, Material.COBWEB, "minecraft:cobweb");
        assertTrue(first.track(block.block, "minecraft:air", 200L));
        first.shutdown();

        TemporaryBlockService reloaded = service("reload.json", Runnable::run, 10);
        reloaded.expireNow(199L);
        assertEquals(1, reloaded.count());
        assertEquals(Material.COBWEB, block.material);
        reloaded.expireNow(200L);
        assertEquals(0, reloaded.count());
        assertEquals(Material.AIR, block.material);
    }

    @Test
    void persistenceFailureRollsBackAcceptedPhysicalCobweb() throws Exception {
        TestWorld world = world();
        Path invalidParent = directory.resolve("not-a-directory");
        Files.writeString(invalidParent, "blocker");
        TemporaryBlockService service = new TemporaryBlockService(plugin,
                new TemporaryBlockRepository(invalidParent.resolve("temporary.json")),
                Runnable::run, 10, uuid -> worlds.get(uuid).world, this::blockData, false);
        TestBlock block = world.block(5, 64, 5, Material.COBWEB, "minecraft:cobweb");

        assertFalse(service.track(block.block, "minecraft:air", 100L));
        assertFalse(service.persistenceHealthy());
        assertEquals(0, service.count());
        assertEquals(Material.AIR, block.material);
        assertEquals(1L, service.terminalReasonCounts()
                .get(TemporaryBlockService.TerminalReason.PERSISTENCE_ROLLBACK));
    }

    @Test
    void maximumCapacityRejectsAdditionalCoordinateWithoutOverwrite() {
        TestWorld world = world();
        TemporaryBlockService service = service("capacity.json", Runnable::run, 1);
        TestBlock first = world.block(6, 64, 6, Material.COBWEB, "minecraft:cobweb");
        TestBlock second = world.block(7, 64, 6, Material.COBWEB, "minecraft:cobweb");

        assertTrue(service.track(first.block, "minecraft:air", 100L));
        assertFalse(service.track(second.block, "minecraft:air", 100L));
        assertEquals(1, service.count());
        assertEquals(1, service.diagnostics(0L).capacityCurrent());
        assertEquals(1, service.diagnostics(0L).capacityMaximum());
    }

    @Test
    void rapidlyQueuedPersistenceWritesFinishWithNewestSnapshot() throws Exception {
        TestWorld world = world();
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockService service = service("queued.json", io, 10);
        for (int index = 0; index < 4; index++) {
            TestBlock block = world.block(index, 80, 8, Material.COBWEB, "minecraft:cobweb");
            assertTrue(service.track(block.block, "minecraft:air", 1_000L));
        }

        io.runAll();

        Map<String, TemporaryBlock> persisted = new TemporaryBlockRepository(
                directory.resolve("queued.json")).load();
        assertEquals(4, persisted.size());
        assertTrue(service.persistenceHealthy());
    }

    @Test
    void successfulResetDiscardsOnlyCoordinatesConfirmedAtOriginalData() {
        TestWorld world = world();
        TemporaryBlockService service = service("reset.json", Runnable::run, 10);
        TestBlock restored = world.block(8, 64, 8, Material.COBWEB, "minecraft:cobweb");
        TestBlock leftover = world.block(9, 64, 8, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(restored.block, "minecraft:air", 100L));
        assertTrue(service.track(leftover.block, "minecraft:air", 100L));
        restored.set(Material.AIR, "minecraft:air");

        assertEquals(1, service.discardResetRestored(entry -> true));
        assertEquals(1, service.count());
        assertEquals(Material.COBWEB, leftover.material);
        service.expireNow(101L);
        assertEquals(Material.AIR, leftover.material);
        assertEquals(0, service.count());
    }

    @Test
    void adjacentAndCrossChunkEntriesRemainIndependent() {
        TestWorld world = world();
        TemporaryBlockService service = service("boundary.json", Runnable::run, 10);
        TestBlock west = world.block(15, 64, 0, Material.COBWEB, "minecraft:cobweb");
        TestBlock east = world.block(16, 64, 0, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(west.block, "minecraft:air", 10L));
        assertTrue(service.track(east.block, "minecraft:water[level=0]", 10L));
        world.setChunkLoaded(1, 0, false);

        service.expireNow(11L);
        assertEquals(Material.AIR, west.material);
        assertEquals(Material.COBWEB, east.material);
        assertEquals(1, service.count());

        world.setChunkLoaded(1, 0, true);
        service.processAvailable(world.world, 1, 0, 11L);
        assertEquals(Material.WATER, east.material);
        assertEquals("minecraft:water[level=0]", east.data);
        assertEquals(0, service.count());
    }

    @Test
    void everyRemovedEntryHasOneSpecificTerminalReason() {
        TestWorld world = world();
        TemporaryBlockService service = service("reasons.json", Runnable::run, 10);
        TestBlock restored = world.block(10, 64, 10, Material.COBWEB, "minecraft:cobweb");
        TestBlock replaced = world.block(11, 64, 10, Material.COBWEB, "minecraft:cobweb");
        TestBlock reset = world.block(12, 64, 10, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(restored.block, "minecraft:air", 10L));
        assertTrue(service.track(replaced.block, "minecraft:air", 10L));
        assertTrue(service.track(reset.block, "minecraft:air", 100L));
        replaced.set(Material.STONE, "minecraft:stone");
        reset.set(Material.AIR, "minecraft:air");

        service.discardResetRestored(entry -> entry.x() == 12);
        service.expireNow(11L);

        long terminalTotal = service.terminalReasonCounts().values().stream()
                .mapToLong(Long::longValue).sum();
        assertEquals(3, terminalTotal);
        assertEquals(0, service.count());
    }

    @Test
    void duplicateCoordinateCannotOverwriteOriginalTrackingRecord() {
        TestWorld world = world();
        TemporaryBlockService service = service("duplicate.json", Runnable::run, 10);
        TestBlock block = world.block(13, 64, 13, Material.COBWEB, "minecraft:cobweb");
        assertTrue(service.track(block.block, "minecraft:air", 10L));
        assertFalse(service.track(block.block, "minecraft:water[level=0]", 10L));

        service.expireNow(11L);

        assertEquals(Material.AIR, block.material);
        assertEquals("minecraft:air", block.data);
        assertEquals(0, service.count());
    }

    private void assertRestores(String file, String originalData, Material material) {
        TestWorld world = world();
        TemporaryBlockService service = service(file, Runnable::run, 10);
        TestBlock block = world.block(20, 64, file.hashCode(), Material.COBWEB,
                "minecraft:cobweb");
        assertTrue(service.track(block.block, originalData, 10L));
        service.expireNow(11L);
        assertEquals(material, block.material);
        assertEquals(originalData, block.data);
        assertEquals(0, service.count());
    }

    private TemporaryBlockService service(String file, Executor io, int maximum) {
        return new TemporaryBlockService(plugin,
                new TemporaryBlockRepository(directory.resolve(file)), io, maximum,
                uuid -> {
                    TestWorld world = worlds.get(uuid);
                    return world == null ? null : world.world;
                }, this::blockData, false);
    }

    private TestWorld world() {
        TestWorld world = new TestWorld();
        worlds.put(world.uuid, world);
        return world;
    }

    private BlockData blockData(String serialized) {
        Material material = Material.valueOf(TemporaryBlockService.materialName(serialized));
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAsString(true)).thenReturn(serialized);
        when(data.matches(any(BlockData.class))).thenAnswer(invocation ->
                invocation.<BlockData>getArgument(0).getMaterial() == material);
        return data;
    }

    private final class TestWorld {
        private final UUID uuid = UUID.randomUUID();
        private final World world = mock(World.class);
        private final Map<String, TestBlock> blocks = new HashMap<>();
        private final Map<String, Boolean> loadedChunks = new HashMap<>();

        private TestWorld() {
            when(world.getUID()).thenReturn(uuid);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(invocation ->
                    loadedChunks.getOrDefault(chunkKey(invocation.getArgument(0),
                            invocation.getArgument(1)), true));
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                    blocks.get(blockKey(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2))).block);
        }

        private TestBlock block(int x, int y, int z, Material material, String data) {
            TestBlock block = new TestBlock(this, x, y, z, material, data);
            blocks.put(blockKey(x, y, z), block);
            return block;
        }

        private void setChunkLoaded(int x, int z, boolean loaded) {
            loadedChunks.put(chunkKey(x, z), loaded);
        }
    }

    private final class TestBlock {
        private final Block block = mock(Block.class);
        private Material material;
        private String data;

        private TestBlock(TestWorld testWorld, int x, int y, int z,
                          Material material, String data) {
            this.material = material;
            this.data = data;
            when(block.getWorld()).thenReturn(testWorld.world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getType()).thenAnswer(ignored -> this.material);
            when(block.getBlockData()).thenAnswer(ignored -> blockData(this.data));
            when(block.getLocation()).thenReturn(new Location(testWorld.world, x, y, z));
            doAnswer(invocation -> {
                BlockData replacement = invocation.getArgument(0);
                this.material = replacement.getMaterial();
                this.data = replacement.getAsString(true);
                return null;
            }).when(block).setBlockData(any(BlockData.class), eq(false));
        }

        private void set(Material material, String data) {
            this.material = material;
            this.data = data;
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override public void execute(Runnable command) { queued.addLast(command); }

        private void runAll() {
            while (!queued.isEmpty()) queued.removeFirst().run();
        }
    }

    private static String blockKey(int x, int y, int z) { return x + ":" + y + ":" + z; }
    private static String chunkKey(int x, int z) { return x + ":" + z; }
}
