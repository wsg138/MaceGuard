package com.lincoln.maceguard.adapter.storage;

import com.lincoln.maceguard.core.model.BlockKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SparseBaselineRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsFirstStateAndPersistsTombstones() throws Exception {
        SparseBaselineRepository repository = new SparseBaselineRepository(temporaryDirectory);
        BlockKey key = new BlockKey("world", 1, 64, -2);

        assertTrue(repository.appendOriginal("warzone", key, "minecraft:short_grass"));
        assertTrue(repository.appendOriginal("warzone", key, "minecraft:cobweb"));
        assertEquals("minecraft:short_grass", repository.load("warzone").get(key));

        assertTrue(repository.appendDelete("warzone", key));
        assertTrue(repository.load("warzone").isEmpty());
    }
}
