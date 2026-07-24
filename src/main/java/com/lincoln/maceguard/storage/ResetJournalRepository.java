package com.lincoln.maceguard.storage;

import com.google.gson.Gson;
import com.lincoln.maceguard.reset.ResetJournal;

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
import java.util.Optional;

public final class ResetJournalRepository {
    private final Path file;
    private final Gson gson = new Gson();
    public ResetJournalRepository(Path file) { this.file = file; }

    public synchronized Optional<ResetJournal> load() throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return Optional.ofNullable(gson.fromJson(reader, ResetJournal.class)); }
        catch (RuntimeException ex) { throw new IOException("Invalid reset journal", ex); }
    }

    public synchronized void save(ResetJournal journal) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "restore-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { gson.toJson(journal, writer); }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** Starts a restore only when no prior partial or failed restore still needs administrator review. */
    public synchronized boolean savePreparedIfNoUnresolved(ResetJournal prepared) throws IOException {
        if (load().filter(ResetJournal::requiresAdministratorReview).isPresent()) return false;
        save(prepared);
        return true;
    }
}
