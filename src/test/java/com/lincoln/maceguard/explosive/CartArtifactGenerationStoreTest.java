package com.lincoln.maceguard.explosive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartArtifactGenerationStoreTest {
    private static final Logger LOGGER = Logger.getLogger(CartArtifactGenerationStoreTest.class.getName());

    @Test
    void persistedGenerationSurvivesRestart(@TempDir Path tempDir) {
        Path state = tempDir.resolve("state/warzone-cart-generation.txt");
        CartArtifactGenerationStore first = new CartArtifactGenerationStore(state, LOGGER);

        assertTrue(first.advance());
        long generation = first.generation();
        assertNotEquals(0L, generation);
        assertTrue(first.healthy());
        assertTrue(Files.isRegularFile(state));

        CartArtifactGenerationStore restarted = new CartArtifactGenerationStore(state, LOGGER);
        assertEquals(generation, restarted.generation());
        assertTrue(restarted.healthy());
        assertTrue(restarted.ensurePersisted());
    }

    @Test
    void corruptStateGetsFreshFenceAndPersistsItBeforeUse(@TempDir Path tempDir) throws Exception {
        Path state = tempDir.resolve("state/warzone-cart-generation.txt");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "not-a-generation\n");

        CartArtifactGenerationStore recovered = new CartArtifactGenerationStore(state, LOGGER);
        long replacement = recovered.generation();
        assertNotEquals(0L, replacement);
        assertTrue(recovered.ensurePersisted());
        assertTrue(recovered.healthy());

        CartArtifactGenerationStore restarted = new CartArtifactGenerationStore(state, LOGGER);
        assertEquals(replacement, restarted.generation());
        assertTrue(restarted.healthy());
    }

    @Test
    void failedWriteStaysFailClosedAndRetriesTheSameFence(@TempDir Path tempDir) throws Exception {
        Path blockedParent = tempDir.resolve("blocked-parent");
        Files.writeString(blockedParent, "not a directory");
        Path state = blockedParent.resolve("warzone-cart-generation.txt");
        CartArtifactGenerationStore store = new CartArtifactGenerationStore(state, LOGGER);

        assertFalse(store.advance());
        long selected = store.generation();
        assertNotEquals(0L, selected);
        assertFalse(store.healthy());
        assertFalse(store.ensurePersisted());
        assertEquals(selected, store.generation());

        Files.delete(blockedParent);
        assertTrue(store.ensurePersisted());
        assertEquals(selected, store.generation());
        assertTrue(store.healthy());

        CartArtifactGenerationStore restarted = new CartArtifactGenerationStore(state, LOGGER);
        assertEquals(selected, restarted.generation());
    }
}
