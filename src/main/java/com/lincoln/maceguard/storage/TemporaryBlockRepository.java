package com.lincoln.maceguard.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lincoln.maceguard.temporary.TemporaryBlock;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TemporaryBlockRepository {
    private final Path file;
    private final Gson gson = new Gson();

    public TemporaryBlockRepository(Path file) { this.file = file; }

    public TemporaryBlockRepository siblingWithSuffix(String suffix) {
        String name = file.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String sibling = extension < 0
                ? name + suffix
                : name.substring(0, extension) + suffix + name.substring(extension);
        return new TemporaryBlockRepository(file.resolveSibling(sibling));
    }

    // Ordered copies keep the on-disk journal deterministic; all maps here are method-local.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    public Map<String, TemporaryBlock> load() throws IOException {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, TemporaryBlock> result = gson.fromJson(reader,
                    new TypeToken<Map<String, TemporaryBlock>>() { }.getType());
            return result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid temporary block state", ex);
        }
    }

    public void save(Map<String, TemporaryBlock> blocks) throws IOException {
        save(blocks, false);
    }

    public void saveAtomically(Map<String, TemporaryBlock> blocks) throws IOException {
        save(blocks, true);
    }

    private void save(Map<String, TemporaryBlock> blocks, boolean atomicRequired)
            throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "temporary-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(blocks, writer);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                if (atomicRequired)
                    throw new IOException("Atomic temporary-block state replacement unavailable",
                            ex);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
