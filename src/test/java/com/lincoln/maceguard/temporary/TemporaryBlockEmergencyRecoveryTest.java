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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporaryBlockEmergencyRecoveryTest {
    @TempDir Path directory;

    private JavaPlugin plugin;
    private BukkitScheduler scheduler;
    private BukkitTask task;
    private final Deque<Runnable> mainTasks = new ArrayDeque<>();
    private final Map<UUID, TestWorld> worlds = new HashMap<>();

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(
                "TemporaryBlockEmergencyRecoveryTest"));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            mainTasks.addLast(invocation.getArgument(1));
            return task;
        });
    }

    @Test
    void asynchronousPersistenceFailureWithLoadedChunkRollsBackOnMainTask() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(4, 64, 4);
        Scenario scenario = failingScenario("loaded");

        assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));
        scenario.io.runAll();
        assertFalse(scenario.service.persistenceHealthy());
        assertEquals(1, scenario.service.count());

        runMainTasks();

        assertEquals(Material.AIR, block.material);
        assertEquals(0, scenario.service.count());
        assertEquals(0, scenario.service.emergencyRecoveryCount());
    }

    @Test
    void asynchronousFailureFollowedByChunkUnloadUsesOwnedTemporaryTicket() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(20, 64, 4);
        Scenario scenario = failingScenario("unload");
        assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));

        scenario.io.runAll();
        world.setChunkLoaded(1, 0, false);
        runMainTasks();

        assertEquals(Material.AIR, block.material);
        assertEquals(1, world.ticketAdds(1, 0));
        assertEquals(1, world.ticketRemoves(1, 0));
        assertTrue(world.ownedTickets.isEmpty());
    }

    @Test
    void shutdownWithUndurableUnloadedEntrySurvivesRestartAndClearsJournal()
            throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(36, 64, 4);
        Scenario first = failingScenario("restart");
        assertTrue(first.service.track(block.block, "minecraft:air", 100L));
        first.io.runAll();
        world.setChunkLoaded(2, 0, false);
        world.ticketLoadingAllowed = false;

        first.service.shutdown();
        mainTasks.clear();
        assertEquals(Material.COBWEB, block.material);
        assertEquals(1, first.emergency.load().size());

        world.ticketLoadingAllowed = true;
        TemporaryBlockService restarted = service(first.primary, first.emergency, Runnable::run);
        assertEquals(1, restarted.count());
        assertEquals(1, restarted.emergencyRecoveryCount());

        restarted.rollbackUndurable();

        assertEquals(Material.AIR, block.material);
        assertEquals(0, restarted.count());
        assertEquals(0, restarted.emergencyRecoveryCount());
        assertTrue(first.emergency.load().isEmpty());
    }

    @Test
    void failureDiscoveredDuringShutdownIsRecoveredAfterRestart() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(52, 64, 4);
        Scenario first = failingScenario("shutdown-race");
        assertTrue(first.service.track(block.block, "minecraft:air", 100L));
        world.setChunkLoaded(3, 0, false);
        world.ticketLoadingAllowed = false;

        first.service.shutdown();
        first.io.runAll();
        first.service.finishShutdownRecovery();
        mainTasks.clear();

        assertEquals(Material.COBWEB, block.material);
        assertEquals(1, first.emergency.load().size());

        world.ticketLoadingAllowed = true;
        TemporaryBlockService restarted = service(first.primary, first.emergency, Runnable::run);
        restarted.rollbackUndurable();
        assertEquals(Material.AIR, block.material);
        assertEquals(0, restarted.count());
        assertTrue(first.emergency.load().isEmpty());
    }

    @Test
    void pluginReloadConsumesEmergencyJournal() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(68, 64, 4);
        Scenario first = failingScenario("reload");
        assertTrue(first.service.track(block.block, "minecraft:air", 100L));
        first.io.runAll();
        world.available = false;
        runMainTasks();
        first.service.shutdown();
        assertEquals(1, first.emergency.load().size());

        world.available = true;
        TemporaryBlockService reloaded = service(first.primary, first.emergency, Runnable::run);
        reloaded.rollbackUndurable();

        assertEquals(Material.AIR, block.material);
        assertEquals(0, reloaded.count());
        assertTrue(first.emergency.load().isEmpty());
    }

    @Test
    void temporarilyUnavailableWorldRemainsRecoverableUntilItReturns() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(84, 64, 4);
        Scenario scenario = failingScenario("world-unavailable");
        assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));
        scenario.io.runAll();
        world.available = false;

        runMainTasks();
        assertEquals(1, scenario.service.count());
        assertEquals(Material.COBWEB, block.material);
        assertEquals(1, scenario.emergency.load().size());

        world.available = true;
        scenario.service.rollbackUndurable();
        assertEquals(0, scenario.service.count());
        assertEquals(Material.AIR, block.material);
        assertTrue(scenario.emergency.load().isEmpty());
    }

    @Test
    void multipleUndurableEntriesInOneChunkShareOneOwnedTicket() throws Exception {
        TestWorld world = world();
        List<TestBlock> blocks = new ArrayList<>();
        Scenario scenario = failingScenario("one-chunk");
        for (int x = 0; x < 8; x++) {
            TestBlock block = world.block(x, 64, 8);
            blocks.add(block);
            assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));
        }
        scenario.io.runAll();
        world.setChunkLoaded(0, 0, false);

        runMainTasks();

        assertTrue(blocks.stream().allMatch(block -> block.material == Material.AIR));
        assertEquals(1, world.ticketAdds(0, 0));
        assertEquals(1, world.ticketRemoves(0, 0));
        assertEquals(0, scenario.service.count());
    }

    @Test
    void entriesAcrossSeveralChunksRecoverInBoundedPasses() throws Exception {
        TestWorld world = world();
        List<TestBlock> blocks = new ArrayList<>();
        Scenario scenario = failingScenario("many-chunks");
        for (int chunk = 0; chunk < 5; chunk++) {
            TestBlock block = world.block(chunk * 16, 64, 12);
            blocks.add(block);
            assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));
        }
        scenario.io.runAll();
        for (int chunk = 0; chunk < 5; chunk++) world.setChunkLoaded(chunk, 0, false);

        runMainTasks();
        assertEquals(3, scenario.service.emergencyRecoveryCount());
        scenario.service.rollbackUndurable();
        assertEquals(1, scenario.service.emergencyRecoveryCount());
        scenario.service.rollbackUndurable();

        assertEquals(0, scenario.service.count());
        assertTrue(blocks.stream().allMatch(block -> block.material == Material.AIR));
        assertTrue(world.ownedTickets.isEmpty());
    }

    @Test
    void entriesCrossingChunkBoundaryRecoverIndependently() throws Exception {
        TestWorld world = world();
        TestBlock west = world.block(15, 64, 16);
        TestBlock east = world.block(16, 64, 16);
        Scenario scenario = failingScenario("boundary");
        assertTrue(scenario.service.track(west.block, "minecraft:air", 100L));
        assertTrue(scenario.service.track(east.block, "minecraft:water[level=0]", 100L));
        scenario.io.runAll();
        world.setChunkLoaded(0, 1, false);
        world.setChunkLoaded(1, 1, false);

        runMainTasks();

        assertEquals(Material.AIR, west.material);
        assertEquals(Material.WATER, east.material);
        assertEquals("minecraft:water[level=0]", east.data);
        assertEquals(1, world.ticketAdds(0, 1));
        assertEquals(1, world.ticketAdds(1, 1));
    }

    @Test
    void recoveryReleasesOnlyTicketsItAdded() throws Exception {
        TestWorld world = world();
        TestBlock alreadyLoaded = world.block(4, 64, 20);
        TestBlock emergencyLoaded = world.block(20, 64, 20);
        Scenario scenario = failingScenario("ticket-ownership");
        assertTrue(scenario.service.track(alreadyLoaded.block, "minecraft:air", 100L));
        assertTrue(scenario.service.track(emergencyLoaded.block, "minecraft:air", 100L));
        scenario.io.runAll();
        world.setChunkLoaded(0, 1, true);
        world.setChunkLoaded(1, 1, false);

        runMainTasks();

        verify(world.world, never()).addPluginChunkTicket(0, 1, plugin);
        verify(world.world, never()).removePluginChunkTicket(0, 1, plugin);
        verify(world.world, times(1)).addPluginChunkTicket(1, 1, plugin);
        verify(world.world, times(1)).removePluginChunkTicket(1, 1, plugin);
        assertTrue(world.ownedTickets.isEmpty());
    }

    @Test
    void emergencyRecoveryNeverForceLoadsChunks() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(32, 64, 32);
        Scenario scenario = failingScenario("no-force-load");
        assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));
        scenario.io.runAll();
        world.setChunkLoaded(2, 2, false);

        runMainTasks();

        verify(world.world, never()).setChunkForceLoaded(anyInt(), anyInt(), anyBoolean());
        assertTrue(world.ownedTickets.isEmpty());
    }

    @Test
    void healthyExpiredEntryStillWaitsForNaturalChunkLoad() {
        TestWorld world = world();
        TestBlock block = world.block(48, 64, 48);
        TemporaryBlockRepository primary = new TemporaryBlockRepository(
                directory.resolve("healthy-primary.json"));
        TemporaryBlockRepository emergency = new TemporaryBlockRepository(
                directory.resolve("healthy-emergency.json"));
        TemporaryBlockService service = service(primary, emergency, Runnable::run);
        assertTrue(service.track(block.block, "minecraft:air", 10L));
        world.setChunkLoaded(3, 3, false);

        service.expireNow(11L);

        assertEquals(Material.COBWEB, block.material);
        assertEquals(1, service.count());
        verify(world.world, never()).addPluginChunkTicket(3, 3, plugin);

        world.setChunkLoaded(3, 3, true);
        service.processAvailable(world.world, 3, 3, 11L);
        assertEquals(Material.AIR, block.material);
        assertEquals(0, service.count());
    }

    @Test
    void startupEmergencyJournalIsConsumedAndCleared() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(64, 64, 64);
        TemporaryBlockRepository primary = new TemporaryBlockRepository(
                directory.resolve("startup-primary.json"));
        TemporaryBlockRepository emergency = new TemporaryBlockRepository(
                directory.resolve("startup-emergency.json"));
        TemporaryBlock entry = entry(block, "minecraft:air", 100L);
        emergency.save(Map.of(key(entry), entry));
        world.setChunkLoaded(4, 4, false);

        TemporaryBlockService service = service(primary, emergency, Runnable::run);
        assertFalse(service.acceptingNewTemporaryBlocks());
        service.rollbackUndurable();

        assertEquals(Material.AIR, block.material);
        assertEquals(0, service.count());
        assertEquals(0, service.emergencyRecoveryCount());
        assertTrue(emergency.load().isEmpty());
        assertTrue(service.acceptingNewTemporaryBlocks());
    }

    @Test
    void emergencyJournalWriteFailureFallsBackToPhysicalRollback() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(80, 64, 80);
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockRepository primary = invalidRepository("journal-fail-primary");
        TemporaryBlockRepository emergency = invalidRepository("journal-fail-emergency");
        TemporaryBlockService service = service(primary, emergency, io);

        assertTrue(service.track(block.block, "minecraft:air", 100L));
        io.runAll();
        runMainTasks();

        assertEquals(Material.AIR, block.material);
        assertEquals(0, service.count());
        assertEquals(0, service.emergencyRecoveryCount());
    }

    @Test
    void trackingCountReachesZeroOnlyAfterManagedPhysicalCobwebIsGone() throws Exception {
        TestWorld world = world();
        TestBlock block = world.block(96, 64, 96);
        Scenario scenario = failingScenario("count-invariant");
        assertTrue(scenario.service.track(block.block, "minecraft:air", 100L));
        scenario.io.runAll();
        world.setChunkLoaded(6, 6, false);
        world.ticketLoadingAllowed = false;

        runMainTasks();
        assertEquals(Material.COBWEB, block.material);
        assertEquals(1, scenario.service.count());
        assertEquals(1, scenario.service.emergencyRecoveryCount());

        world.ticketLoadingAllowed = true;
        scenario.service.rollbackUndurable();
        assertEquals(Material.AIR, block.material);
        assertEquals(0, scenario.service.count());
        assertEquals(0, scenario.service.emergencyRecoveryCount());
    }

    @Test
    void newTemporaryBlocksAreRejectedWhileEmergencyRecoveryIsPending() throws Exception {
        TestWorld world = world();
        TestBlock first = world.block(112, 64, 112);
        TestBlock rejected = world.block(113, 64, 112);
        Scenario scenario = failingScenario("reject-pending");
        assertTrue(scenario.service.track(first.block, "minecraft:air", 100L));
        scenario.io.runAll();
        world.available = false;
        runMainTasks();

        assertFalse(scenario.service.track(rejected.block, "minecraft:air", 100L));
        assertEquals(1, scenario.service.count());
    }

    private Scenario failingScenario(String name) throws Exception {
        QueuedExecutor io = new QueuedExecutor();
        TemporaryBlockRepository primary = invalidRepository(name + "-primary");
        TemporaryBlockRepository emergency = new TemporaryBlockRepository(
                directory.resolve(name + "-emergency.json"));
        return new Scenario(service(primary, emergency, io), primary, emergency, io);
    }

    private TemporaryBlockRepository invalidRepository(String name) throws Exception {
        Path blocker = directory.resolve(name);
        Files.writeString(blocker, "not a directory");
        return new TemporaryBlockRepository(blocker.resolve("temporary.json"));
    }

    private TemporaryBlockService service(TemporaryBlockRepository primary,
                                          TemporaryBlockRepository emergency, Executor io) {
        return new TemporaryBlockService(plugin, primary, emergency, io, 10_000,
                uuid -> {
                    TestWorld world = worlds.get(uuid);
                    return world == null || !world.available ? null : world.world;
                }, this::blockData, false);
    }

    private TestWorld world() {
        TestWorld world = new TestWorld();
        worlds.put(world.uuid, world);
        return world;
    }

    private void runMainTasks() {
        while (!mainTasks.isEmpty()) mainTasks.removeFirst().run();
    }

    private TemporaryBlock entry(TestBlock block, String original, long expiresAt) {
        return new TemporaryBlock(block.testWorld.uuid.toString(), block.x, block.y, block.z,
                block.data, original, expiresAt, false, false);
    }

    private static String key(TemporaryBlock entry) {
        return entry.worldUuid() + ":" + entry.x() + ":" + entry.y() + ":" + entry.z();
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

    private record Scenario(TemporaryBlockService service,
                            TemporaryBlockRepository primary,
                            TemporaryBlockRepository emergency,
                            QueuedExecutor io) { }

    private final class TestWorld {
        private final UUID uuid = UUID.randomUUID();
        private final World world = mock(World.class);
        private final Map<String, TestBlock> blocks = new HashMap<>();
        private final Map<String, Boolean> loadedChunks = new HashMap<>();
        private final Map<String, Integer> addedTickets = new HashMap<>();
        private final Map<String, Integer> removedTickets = new HashMap<>();
        private final Set<String> ownedTickets = new HashSet<>();
        private boolean available = true;
        private boolean ticketLoadingAllowed = true;

        private TestWorld() {
            when(world.getUID()).thenReturn(uuid);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(invocation ->
                    loadedChunks.getOrDefault(chunkKey(invocation.getArgument(0),
                            invocation.getArgument(1)), true));
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                    blocks.get(blockKey(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2))).block);
            when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
                    .thenAnswer(invocation -> {
                        int x = invocation.getArgument(0);
                        int z = invocation.getArgument(1);
                        String key = chunkKey(x, z);
                        if (!ticketLoadingAllowed) return false;
                        addedTickets.merge(key, 1, Integer::sum);
                        boolean added = ownedTickets.add(key);
                        loadedChunks.put(key, true);
                        return added;
                    });
            when(world.removePluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
                    .thenAnswer(invocation -> {
                        int x = invocation.getArgument(0);
                        int z = invocation.getArgument(1);
                        String key = chunkKey(x, z);
                        boolean removed = ownedTickets.remove(key);
                        if (removed) removedTickets.merge(key, 1, Integer::sum);
                        return removed;
                    });
        }

        private TestBlock block(int x, int y, int z) {
            TestBlock block = new TestBlock(this, x, y, z);
            blocks.put(blockKey(x, y, z), block);
            return block;
        }

        private void setChunkLoaded(int x, int z, boolean loaded) {
            loadedChunks.put(chunkKey(x, z), loaded);
        }

        private int ticketAdds(int x, int z) {
            return addedTickets.getOrDefault(chunkKey(x, z), 0);
        }

        private int ticketRemoves(int x, int z) {
            return removedTickets.getOrDefault(chunkKey(x, z), 0);
        }
    }

    private final class TestBlock {
        private final TestWorld testWorld;
        private final int x;
        private final int y;
        private final int z;
        private final Block block = mock(Block.class);
        private Material material = Material.COBWEB;
        private String data = "minecraft:cobweb";

        private TestBlock(TestWorld testWorld, int x, int y, int z) {
            this.testWorld = testWorld;
            this.x = x;
            this.y = y;
            this.z = z;
            when(block.getWorld()).thenReturn(testWorld.world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getType()).thenAnswer(ignored -> material);
            when(block.getBlockData()).thenAnswer(ignored -> blockData(data));
            when(block.getLocation()).thenReturn(new Location(testWorld.world, x, y, z));
            doAnswer(invocation -> {
                BlockData replacement = invocation.getArgument(0);
                material = replacement.getMaterial();
                data = replacement.getAsString(true);
                return null;
            }).when(block).setBlockData(any(BlockData.class), eq(false));
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
