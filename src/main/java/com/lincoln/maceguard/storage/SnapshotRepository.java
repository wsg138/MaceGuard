package com.lincoln.maceguard.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lincoln.maceguard.reset.Snapshot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;

public final class SnapshotRepository {
    private final Path directory;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public SnapshotRepository(Path directory) { this.directory = directory; }

    public void save(Snapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        Path target = path(snapshot.worldUuid(), snapshot.regionId());
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".incomplete");
        boolean moved = false;
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) { gson.toJson(snapshot, writer); }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            moved = true;
            forceDirectory();
        } finally { if (!moved) Files.deleteIfExists(temp); }
    }

    public Optional<Snapshot> load(String worldUuid, String regionId) throws IOException {
        Path path = path(worldUuid, regionId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { return Optional.ofNullable(gson.fromJson(reader, Snapshot.class)); }
        catch (RuntimeException ex) { throw new IOException("Invalid snapshot JSON", ex); }
    }

    public boolean hasIncompleteFiles() throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) { return files.anyMatch(path -> path.getFileName().toString().endsWith(".incomplete")); }
    }

    private Path path(String worldUuid, String regionId) {
        String safe = regionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return directory.resolve(worldUuid + "--" + safe + ".snapshot.json");
    }

    private void forceDirectory() {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException ignored) { /* Not supported on every platform; file itself was forced. */ }
    }
}
