package com.lincoln.maceguard.explosive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;
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

    /** Retries the already-selected generation after a transient persistence failure. */
    boolean ensurePersisted() {
        if (persisted && healthy) return true;
        return generation != 0L && persist(generation);
    }

    /**
     * Invalidates every previously tagged cart. Use a fresh random token rather than a small
     * counter so deletion/corruption of this state file cannot plausibly make an old unloaded cart
     * collide with the next runtime's generation. Memory advances before I/O so a failed write can
     * retry exactly the same new fence rather than reverting to a stale generation.
     */
    boolean advance() {
        generation = freshGeneration(generation);
        persisted = false;
        return persist(generation);
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
            if (generation == 0L) throw new NumberFormatException("zero generation");
            persisted = true;
        } catch (IOException | NumberFormatException ex) {
            generation = freshGeneration(0L);
            persisted = false;
            logger.warning("Cart artifact generation state was unreadable; a fresh generation will "
                    + "invalidate previously tagged TNT minecarts once persisted: " + ex.getMessage());
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
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
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

    private static long freshGeneration(long previous) {
        long candidate;
        do candidate = ThreadLocalRandom.current().nextLong();
        while (candidate == 0L || candidate == previous);
        return candidate;
    }
}
