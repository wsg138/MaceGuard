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
            java.util.List<String> active = yaml.getStringList("selection.active-modifiers").stream()
                    .map(String::trim).filter(value -> !value.isEmpty()).distinct().sorted().toList();
            long activated = yaml.getLong("selection.activated-at");
            long boundary = yaml.getLong("selection.weekly-boundary");
            long transition = yaml.getLong("selection.transition-at");
            long sequence = yaml.getLong("selection.sequence", 0);
            if (active.isEmpty() || activated <= 0 || boundary <= 0 || transition <= activated || sequence < 0)
                throw new IllegalArgumentException("invalid weekly selection state values");
            current = new RotationState(active, activated, boundary, transition,
                    new LinkedHashSet<>(yaml.getLongList("selection.emitted-warnings")), sequence);
            return Optional.of(current);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException ex) {
            Path backup = file.resolveSibling(file.getFileName() + ".invalid-" + System.currentTimeMillis() + ".bak");
            try { Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException copyFailure) {
                logger.severe("Could not preserve invalid warzone state: " + copyFailure.getMessage());
            }
            logger.severe("Could not parse weekly warzone state; preserved it as " + backup.getFileName()
                    + ": " + ex.getMessage());
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
            catch (IOException ex) { logger.severe("Could not persist weekly warzone state: " + ex.getMessage()); }
        }
    }

    private void save(RotationState state) throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("selection.active-modifiers", state.activeModifierIds());
        yaml.set("selection.activated-at", state.activatedAtMillis());
        yaml.set("selection.weekly-boundary", state.weeklyBoundaryMillis());
        yaml.set("selection.transition-at", state.transitionAtMillis());
        yaml.set("selection.emitted-warnings", state.emittedWarningsSeconds().stream().sorted().toList());
        yaml.set("selection.sequence", state.selectionSequence());
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
