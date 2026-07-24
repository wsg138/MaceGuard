package com.lincoln.maceguard.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lincoln.maceguard.reset.ArmState;

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
import java.util.Optional;

public final class ArmStateRepository {
    private final Path file;
    private final Gson gson = new Gson();
    private final Map<String, ArmState> states = new LinkedHashMap<>();

    public ArmStateRepository(Path file) { this.file = file; }

    public synchronized void load() throws IOException {
        states.clear();
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, ArmState> loaded = gson.fromJson(reader, new TypeToken<Map<String, ArmState>>() { }.getType());
            if (loaded != null) states.putAll(loaded);
        } catch (RuntimeException ex) { throw new IOException("Invalid arming state", ex); }
    }

    public synchronized Optional<ArmState> get(String worldUuid, String regionId) { return Optional.ofNullable(states.get(key(worldUuid, regionId))); }
    public synchronized Map<String, ArmState> all() { return Map.copyOf(states); }

    public synchronized void arm(ArmState state) throws IOException { states.put(key(state.worldUuid(), state.regionId()), state); persist(); }
    public synchronized void disarm(String worldUuid, String regionId) throws IOException { states.remove(key(worldUuid, regionId)); persist(); }
    public synchronized void disarmAll() throws IOException { states.clear(); persist(); }

    private String key(String worldUuid, String regionId) { return worldUuid + ":" + regionId.toLowerCase(); }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "armed-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { gson.toJson(states, writer); }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
