package com.lincoln.maceguard.adapter.storage;

import com.lincoln.maceguard.core.model.BlockKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Append-only, fsync'd sparse baseline journal. Entries are compacted after a successful reset. */
public final class SparseBaselineRepository {
    private final Path directory;

    public SparseBaselineRepository(Path directory) {
        this.directory = directory;
    }

    public synchronized Map<BlockKey, String> load(String zone) throws IOException {
        Path file = path(zone);
        Map<BlockKey, String> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 6);
                if (parts.length < 5) {
                    continue;
                }
                BlockKey key = new BlockKey(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                if ("D".equals(parts[0])) {
                    result.remove(key);
                } else if ("P".equals(parts[0]) && parts.length == 6) {
                    result.putIfAbsent(key, new String(Base64.getDecoder().decode(parts[5]), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    public synchronized boolean appendOriginal(String zone, BlockKey key, String data) {
        return append(zone, "P|" + key.worldName() + "|" + key.x() + "|" + key.y() + "|" + key.z() + "|" + Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)) + "\n");
    }

    public synchronized boolean appendDelete(String zone, BlockKey key) {
        return append(zone, "D|" + key.worldName() + "|" + key.x() + "|" + key.y() + "|" + key.z() + "\n");
    }

    public synchronized boolean clear(String zone) {
        try {
            Files.createDirectories(directory);
            Path file = path(zone);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    public synchronized long loadLastReset(String zone) {
        Path state = directory.resolve("weekly-reset.properties");
        if (!Files.exists(state)) {
            return 0L;
        }
        Properties values = new Properties();
        try (var input = Files.newInputStream(state)) {
            values.load(input);
            return Long.parseLong(values.getProperty(zone, "0"));
        } catch (IOException | NumberFormatException ex) {
            return 0L;
        }
    }

    public synchronized boolean saveLastReset(String zone, long instant) {
        try {
            Files.createDirectories(directory);
            Path state = directory.resolve("weekly-reset.properties");
            Properties values = new Properties();
            if (Files.exists(state)) {
                try (var input = Files.newInputStream(state)) {
                    values.load(input);
                }
            }
            values.setProperty(zone, Long.toString(instant));
            Path temporary = state.resolveSibling(state.getFileName() + ".tmp");
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                values.store(output, "MaceGuard weekly reset timestamps");
            }
            try {
                Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean append(String zone, String line) {
        try {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(path(zone), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private Path path(String zone) {
        return directory.resolve(zone.replaceAll("[^A-Za-z0-9._-]", "_") + ".journal");
    }
}
