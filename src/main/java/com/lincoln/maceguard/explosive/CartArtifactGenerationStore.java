package com.lincoln.maceguard.explosive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/** Durable generation fence for TNT-minecart artifacts that can survive unloaded chunks/restarts. */
final class CartArtifactGenerationStore {
    private final Path file;
    private final Logger logger;
    private long generation;
    private boolean persisted;
    private boolean healthy = true;

    CartArtifactGenerationStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    long generation() { return generation; }
    boolean healthy() { return healthy; }

    /** Ensures the current generation is durable before carts are allowed to be tagged with it. */
    boolean ensurePersisted() {
        if (persisted && healthy) return true;
        return persist(generation);
    }

    /**
     * Invalidates every previously tagged cart. A newly placed cart receives the returned durable
     * generation and therefore cannot be confused with artifacts left in an unloaded chunk.
     */
    boolean advance() {
        long next = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        if (!persist(next)) return false;
        generation = next;
        return true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            generation = 0L;
            persisted = false;
            return;
        }
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            generation = Long.parseLong(value);
            if (generation < 0L) throw new NumberFormatException("negative generation");
            persisted = true;
        } catch (IOException | NumberFormatException ex) {
            // Fail closed with a fresh generation. Once it is persisted, all old entity tags are
            // stale and will be removed when their chunks become available.
            generation = Math.max(1L, System.currentTimeMillis());
            persisted = false;
            logger.warning("Cart artifact generation state was unreadable; a fresh generation will "
                    + "invalidate previously tagged TNT minecarts: " + ex.getMessage());
        }
    }

    private boolean persist(long value) {
        Path parent = file.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "cart-generation-", ".tmp");
            Files.writeString(temp, Long.toString(value) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            persisted = true;
            healthy = true;
            return true;
        } catch (IOException | RuntimeException ex) {
            healthy = false;
            persisted = false;
            logger.severe("Could not persist TNT-minecart artifact generation; new Warzone carts "
                    + "will be rejected until persistence succeeds: " + ex.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (IOException ignored) { }
            }
        }
    }
}
