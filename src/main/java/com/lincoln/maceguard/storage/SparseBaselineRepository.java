package com.lincoln.maceguard.storage;

import com.google.gson.Gson;
import com.lincoln.maceguard.reset.SparseBaseline;

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

public final class SparseBaselineRepository {
    private final Path directory;
    private final Gson gson = new Gson();
    public SparseBaselineRepository(Path directory) { this.directory = directory; }

    public Optional<SparseBaseline> load(String worldUuid, String regionId) throws IOException {
        Path file = path(worldUuid, regionId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return Optional.ofNullable(gson.fromJson(reader, SparseBaseline.class)); }
        catch (RuntimeException ex) { throw new IOException("Invalid sparse baseline", ex); }
    }

    public void save(SparseBaseline baseline) throws IOException {
        Files.createDirectories(directory);
        Path target = path(baseline.worldUuid(), baseline.regionId());
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".incomplete");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { gson.toJson(baseline, writer); }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    public boolean hasIncompleteFiles() throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) { return files.anyMatch(path -> path.getFileName().toString().endsWith(".incomplete")); }
    }

    private Path path(String worldUuid, String regionId) { return directory.resolve(worldUuid + "--" + regionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + ".sparse.json"); }
}
