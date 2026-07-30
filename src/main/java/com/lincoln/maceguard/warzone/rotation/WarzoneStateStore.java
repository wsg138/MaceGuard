package com.lincoln.maceguard.warzone.rotation;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

public final class WarzoneStateStore {
    private final Path file;
    private final Logger logger;
    private final Executor writer;
    private RotationState current;
    private RotationState dirty;
    private boolean writerScheduled;

    public WarzoneStateStore(Path file, Logger logger, Executor writer) {
        this.file = file;
        this.logger = logger;
        this.writer = writer;
    }

    public synchronized Optional<RotationState> load() {
        if (current != null) return Optional.of(current);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            String active = yaml.getString("rotation.active-id");
            if (active == null) return Optional.empty();
            long started = yaml.getLong("rotation.started-at");
            long ends = yaml.getLong("rotation.ends-at");
            String next = yaml.getString("rotation.next-id", "");
            if (active.isBlank() || next.isBlank() || started <= 0 || ends <= started)
                throw new IllegalArgumentException("invalid rotation state values");
            current = new RotationState(active, started, ends, next,
                    new LinkedHashSet<>(yaml.getLongList("rotation.emitted-warnings")));
            return Optional.of(current);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException ex) {
            Path backup = file.resolveSibling(file.getFileName() + ".invalid-" + System.currentTimeMillis() + ".bak");
            try { Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException copyFailure) {
                logger.severe("Could not preserve invalid warzone state: " + copyFailure.getMessage());
            }
            logger.severe("Could not parse warzone state; preserved it as " + backup.getFileName() + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    public synchronized Optional<RotationState> snapshot() { return Optional.ofNullable(current); }

    public synchronized void update(RotationState state) {
        current = state;
        dirty = state;
        if (writerScheduled) return;
        writerScheduled = true;
        writer.execute(this::drain);
    }

    private void drain() {
        while (true) {
            RotationState snapshot;
            synchronized (this) {
                snapshot = dirty;
                dirty = null;
                if (snapshot == null) {
                    writerScheduled = false;
                    return;
                }
            }
            try { save(snapshot); }
            catch (IOException ex) { logger.severe("Could not persist warzone state: " + ex.getMessage()); }
        }
    }

    private void save(RotationState state) throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("rotation.active-id", state.activeRotationId());
        yaml.set("rotation.started-at", state.startedAtMillis());
        yaml.set("rotation.ends-at", state.endsAtMillis());
        yaml.set("rotation.next-id", state.nextRotationId());
        yaml.set("rotation.emitted-warnings", state.emittedWarningsSeconds().stream().sorted().toList());
        byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), "warzone-state-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            if (Files.isRegularFile(file))
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
