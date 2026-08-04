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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporaryBlockEmergencyJournalCheckpointTest {
    private static final String AIR_DATA = "minecraft:air";
    private static final String COBWEB_DATA = "minecraft:cobweb";

    @TempDir Path directory;

    private JavaPlugin plugin;
    private final Deque<Runnable> mainTasks = new ArrayDeque<>();
    private final Map<UUID, WorldHarness> worlds = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(
                "TemporaryBlockEmergencyJournalCheckpointTest"));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            mainTasks.addLast(invocation.getArgument(1));
            return task;
        });
    }

    @Test
    void partialRecoveryCheckpointsRemainingJournalBeforeRestart() throws Exception {
        WorldHarness world = world();
        Scenario first = failingScenario("partial-restart");
        List<BlockHarness> blocks = new ArrayList<>();
        for (int chunk = 0; chunk < 5; chunk++) {
            BlockHarness block = world.block(chunk * 16, 64, 0);
            blocks.add(block);
            assertTrue(first.service.track(block.block, AIR_DATA, 100L));
        }

        first.io.runAll();
        for (int chunk = 0; chunk < 5; chunk++) world.setChunkLoaded(chunk, 0, false);
        runNextMainTask();

        assertEquals(3, first.service.emergencyRecoveryCount());
        assertEquals(3, first.emergency.load().size());
        assertEquals(2, blocks.stream().filter(block -> block.material == Material.AIR).count());
        BlockHarness completed = blocks.stream()
                .filter(block -> block.material == Material.AIR)
                .findFirst()
                .orElseThrow();

        completed.set(Material.COBWEB, COBWEB_DATA);
        TemporaryBlockService restarted = service(first.primary, first.emergency, Runnable::run);
        assertEquals(3, restarted.count());
        assertEquals(3, restarted.emergencyRecoveryCount());

        restarted.rollbackUndurable();
        restarted.rollbackUndurable();

        assertEquals(Material.COBWEB, completed.material);
        assertTrue(blocks.stream().filter(block -> block != completed)
                .allMatch(block -> block.material == Material.AIR));
        assertEquals(0, restarted.count());
        assertEquals(0, restarted.emergencyRecoveryCount());
        assertTrue(first.emergency.load().isEmpty());
    }

    @Test
    void emergencyEntryLimitCannotBeBypassedByHealthyTtlCleanup() throws Exception {
        WorldHarness world = world();
        Scenario scenario = failingScenario("entry-limit");
        List<BlockHarness> blocks = new ArrayList<>();
        for (int index = 0; index < 70; index++) {
            BlockHarness block = world.block(index % 16, 64 + index, 0);
            blocks.add(block);
            assertTrue(scenario.service.track(block.block, AIR_DATA, 10L));
        }

        scenario.io.runAll();
        scenario.service.expireNow(11L);

        long restored = blocks.stream().filter(block -> block.material == Material.AIR).count();
        long pending = blocks.stream().filter(block -> block.material == Material.COBWEB).count();
        assertEquals(64, restored);
        assertEquals(6, pending);
        assertEquals(6, scenario.service.count());
        assertEquals(6, scenario.service.emergencyRecoveryCount());
        assertEquals(6, scenario.emergency.load().size());

        runAllMainTasks();

        assertTrue(blocks.stream().allMatch(block -> block.material == Material.AIR));
        assertEquals(0, scenario.service.count());
        assertEquals(0, scenario.service.emergencyRecoveryCount());
        assertTrue(scenario.emergency.load().isEmpty());
    }

    private Scenario failingScenario(String name) throws Exception {
        QueuedExecutor io = new QueuedExecutor();
        Path blocker = directory.resolve(name + "-primary");
        Files.writeString(blocker, "not a directory");
        TemporaryBlockRepository primary = new TemporaryBlockRepository(
                blocker.resolve("temporary.json"));
        TemporaryBlockRepository emergency = new TemporaryBlockRepository(
                directory.resolve(name + "-emergency.json"));
        return new Scenario(service(primary, emergency, io), primary, emergency, io);
    }

    private TemporaryBlockService service(TemporaryBlockRepository primary,
                                          TemporaryBlockRepository emergency, Executor io) {
        return new TemporaryBlockService(plugin, primary, emergency, io, 10_000,
                uuid -> {
                    WorldHarness world = worlds.get(uuid);
                    return world == null ? null : world.world;
                }, this::blockData, false);
    }

    private WorldHarness world() {
        WorldHarness world = new WorldHarness();
        worlds.put(world.uuid, world);
        return world;
    }

    private void runNextMainTask() {
        mainTasks.removeFirst().run();
    }

    private void runAllMainTasks() {
        while (!mainTasks.isEmpty()) mainTasks.removeFirst().run();
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

    private final class WorldHarness {
        private final UUID uuid = UUID.randomUUID();
        private final World world = mock(World.class);
        private final Map<String, BlockHarness> blocks = new ConcurrentHashMap<>();
        private final Map<String, Boolean> loadedChunks = new ConcurrentHashMap<>();
        private final Set<String> ownedTickets = ConcurrentHashMap.newKeySet();

        private WorldHarness() {
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
                        loadedChunks.put(key, true);
                        return ownedTickets.add(key);
                    });
            when(world.removePluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
                    .thenAnswer(invocation -> ownedTickets.remove(chunkKey(
                            invocation.getArgument(0), invocation.getArgument(1))));
        }

        private BlockHarness block(int x, int y, int z) {
            BlockHarness block = new BlockHarness(this, x, y, z);
            blocks.put(blockKey(x, y, z), block);
            return block;
        }

        private void setChunkLoaded(int x, int z, boolean loaded) {
            loadedChunks.put(chunkKey(x, z), loaded);
        }
    }

    private final class BlockHarness {
        private final Block block = mock(Block.class);
        private Material material = Material.COBWEB;
        private String data = COBWEB_DATA;

        private BlockHarness(WorldHarness world, int x, int y, int z) {
            when(block.getWorld()).thenReturn(world.world);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getType()).thenAnswer(ignored -> material);
            when(block.getBlockData()).thenAnswer(ignored -> blockData(data));
            when(block.getLocation()).thenReturn(new Location(world.world, x, y, z));
            doAnswer(invocation -> {
                BlockData replacement = invocation.getArgument(0);
                material = replacement.getMaterial();
                data = replacement.getAsString(true);
                return null;
            }).when(block).setBlockData(any(BlockData.class), eq(false));
        }

        private void set(Material replacement, String serialized) {
            material = replacement;
            data = serialized;
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
