package com.lincoln.maceguard.storage;

import com.lincoln.maceguard.temporary.TemporaryBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryBlockRepositoryTest {
    @TempDir Path directory;

    @Test void legacyRecordWithoutNewFlagsLoadsAsFalse() throws Exception {
        Path file = directory.resolve("temporary-blocks.json");
        String world = UUID.randomUUID().toString();
        Files.writeString(file, "{\"entry\":{\"worldUuid\":\"" + world
                + "\",\"x\":1,\"y\":2,\"z\":3,\"expectedBlockData\":\"minecraft:cobweb\","
                + "\"originalBlockData\":\"minecraft:air\",\"expiresAt\":123}}");

        TemporaryBlock loaded = new TemporaryBlockRepository(file).load().get("entry");
        assertNotNull(loaded);
        assertFalse(loaded.pendingClear());
        assertFalse(loaded.warzoneOwned());
        assertEquals(world, loaded.worldUuid());
    }

    @Test void pendingClearAndOwnershipSurviveRepositoryRestart() throws Exception {
        Path file = directory.resolve("temporary-blocks.json");
        TemporaryBlock block = new TemporaryBlock(UUID.randomUUID().toString(), 1, 2, 3,
                "minecraft:cobweb", "minecraft:air", 123L, true, true);
        new TemporaryBlockRepository(file).save(Map.of("entry", block));

        TemporaryBlock loaded = new TemporaryBlockRepository(file).load().get("entry");
        assertNotNull(loaded);
        assertTrue(loaded.pendingClear());
        assertTrue(loaded.warzoneOwned());
        assertEquals(block, loaded);
    }

    @Test void forcingClearPreservesWarzoneOwnership() {
        TemporaryBlock block = new TemporaryBlock(UUID.randomUUID().toString(), 1, 2, 3,
                "minecraft:cobweb", "minecraft:air", 123L, false, true);
        TemporaryBlock pending = block.withPendingClear();
        assertTrue(pending.pendingClear());
        assertTrue(pending.warzoneOwned());
    }
}
