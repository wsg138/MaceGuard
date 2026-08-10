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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class WarzoneStateStore {
    private static final long RETRY_DELAY_SECONDS = 1L;

    private final Path file;
    private final Logger logger;
    private final Executor writer;
    private RotationState current;
    private RotationState dirty;
    private boolean writerScheduled;
    private volatile String lastFailure = "";

    public WarzoneStateStore(Path file, Logger logger, Executor writer) {
        this.file = file;
        this.logger = logger;
        this.writer = writer;
    }

    /** Creates an in-memory candidate store that can never write the production state file. */
    public static WarzoneStateStore staged(RotationState initial, Logger logger) {
        WarzoneStateStore store = new WarzoneStateStore(null, logger, Runnable::run);
        store.current = Objects.requireNonNull(initial, "initial");
        return store;
    }

    public synchronized Optional<RotationState> load() {
        if (current != null) return Optional.of(current);
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            current = yaml.contains("state-version") ? loadVersioned(yaml) : loadSchemaFiveState(yaml);
            lastFailure = "";
            return Optional.of(current);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException ex) {
            preserveInvalid(ex);
            return Optional.empty();
        }
    }

    private RotationState loadVersioned(YamlConfiguration yaml) {
        int version = integer(yaml, "state-version", true, -1);
        if (version != RotationState.VERSION)
            throw new IllegalArgumentException("unsupported state version " + version);
        String identity = string(yaml, "automatic.slot-identity", true);
        long slotIndex = number(yaml, "automatic.slot-index", true, 0);
        int cycleIndex = integer(yaml, "automatic.cycle-index", true, -1);
        int phaseOffset = integer(yaml, "schedule.phase-offset", true, 0);
        Boolean enabledOverride = optionalBoolean(yaml, "schedule.enabled-override");
        long start = number(yaml, "automatic.slot-start", true, 0);
        long end = number(yaml, "automatic.slot-end", true, 0);
        long automaticActivated = number(yaml, "automatic.activated-at", true, 0);
        SelectionSourceType automaticType = source(
                string(yaml, "automatic.source-type", true), true);
        String automaticId = blankToNull(string(yaml, "automatic.source-id", false));
        List<String> automaticModifiers = stableIds(
                stringList(yaml, "automatic.modifiers", true));
        validateSource("automatic", automaticType, automaticId, automaticModifiers, false);
        long sequence = number(yaml, "selection-sequence", true, 0);
        if (identity.isEmpty() || cycleIndex < 0 || start <= 0 || end <= start
                || automaticActivated <= 0 || automaticActivated > end || sequence < 0)
            throw new IllegalArgumentException("invalid automatic schedule state values");

        SelectionSourceType overrideType = source(
                string(yaml, "override.source-type", false), false);
        String overrideId = blankToNull(string(yaml, "override.source-id", false));
        List<String> overrideModifiers = stableIds(
                stringList(yaml, "override.modifiers", false));
        OverrideDurationMode mode = enumValue(
                string(yaml, "override.duration-mode", false),
                OverrideDurationMode.class, false);
        long activated = number(yaml, "override.activated-at", false, 0);
        long expires = number(yaml, "override.expires-at", false, 0);
        if (overrideType == null) {
            if (overrideId != null || !overrideModifiers.isEmpty() || mode != null
                    || activated != 0 || expires != 0)
                throw new IllegalArgumentException(
                        "manual override fields are present without an override source type");
            overrideId = null;
            overrideModifiers = List.of();
            mode = null;
            activated = 0;
            expires = 0;
        } else if (mode == null || activated <= 0
                || mode == OverrideDurationMode.UNTIL_CLEARED && expires != 0
                || mode != OverrideDurationMode.UNTIL_CLEARED && expires <= activated) {
            throw new IllegalArgumentException("invalid manual override state values");
        } else {
            validateSource("override", overrideType, overrideId, overrideModifiers, true);
        }
        LinkedHashSet<Long> warnings = new LinkedHashSet<>(
                longList(yaml, "emitted-warnings", false));
        if (warnings.stream().anyMatch(value -> value == null || value <= 0))
            throw new IllegalArgumentException("emitted warning values must be positive seconds");
        return new RotationState(version, identity, slotIndex, cycleIndex, phaseOffset,
                enabledOverride, start, end, automaticActivated, automaticType, automaticId,
                automaticModifiers, overrideType, overrideId,
                overrideModifiers, mode, activated, expires, warnings, sequence);
    }

    /** Reads the schema-5 weekly state as a one-entry RANDOM automatic slot. */
    private RotationState loadSchemaFiveState(YamlConfiguration yaml) {
        List<String> active = stableIds(
                stringList(yaml, "selection.active-modifiers", true));
        long boundary = number(yaml, "selection.weekly-boundary", true, 0);
        long transition = number(yaml, "selection.transition-at", true, 0);
        long sequence = number(yaml, "selection.sequence", false, 0);
        long activated = number(yaml, "selection.activated-at", false, boundary);
        List<Long> warnings = longList(yaml, "selection.emitted-warnings", false);
        if (active.isEmpty() || boundary <= 0 || transition <= boundary
                || activated <= 0 || activated > transition || sequence < 0
                || warnings.stream().anyMatch(value -> value <= 0))
            throw new IllegalArgumentException("invalid schema-5 weekly selection state values");
        return new RotationState(RotationState.VERSION, "legacy:" + boundary, 0, 0,
                0, null, boundary, transition, activated,
                SelectionSourceType.RANDOM, null, active,
                null, null, List.of(), null, 0, 0,
                new LinkedHashSet<>(warnings), sequence);
    }

    private void preserveInvalid(Exception ex) {
        Path backup = file.resolveSibling(file.getFileName() + ".invalid-"
                + System.currentTimeMillis() + ".bak");
        try { Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException copyFailure) {
            logger.severe("Could not preserve invalid warzone state: " + copyFailure.getMessage());
        }
        lastFailure = ex.getMessage();
        logger.severe("Could not parse warzone state; preserved it as " + backup.getFileName()
                + ": " + ex.getMessage());
    }

    public synchronized Optional<RotationState> snapshot() { return Optional.ofNullable(current); }

    public synchronized void update(RotationState state) {
        current = state;
        if (file == null) return;
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
            try {
                save(snapshot);
                lastFailure = "";
            } catch (IOException ex) {
                boolean firstFailure = !Objects.equals(lastFailure, ex.getMessage());
                lastFailure = ex.getMessage();
                if (firstFailure)
                    logger.severe("Could not persist warzone state; the newest state remains queued "
                            + "and will be retried: " + ex.getMessage());
                synchronized (this) {
                    // Any dirty value arrived after this snapshot was dequeued and is therefore
                    // newer, even if its selection sequence did not change (warnings are one case).
                    if (dirty == null) dirty = snapshot;
                }
                CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS, writer)
                        .execute(this::drain);
                return;
            }
        }
    }

    private void save(RotationState state) throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("state-version", state.stateVersion());
        yaml.set("automatic.slot-identity", state.automaticSlotIdentity());
        yaml.set("automatic.slot-index", state.automaticSlotIndex());
        yaml.set("automatic.cycle-index", state.currentCycleIndex());
        yaml.set("schedule.phase-offset", state.cyclePhaseOffset());
        yaml.set("schedule.enabled-override", state.scheduleEnabledOverride());
        yaml.set("automatic.slot-start", state.automaticSlotStartMillis());
        yaml.set("automatic.slot-end", state.automaticSlotEndMillis());
        yaml.set("automatic.activated-at", state.automaticActivatedAtMillis());
        yaml.set("automatic.source-type", state.automaticSourceType().name());
        yaml.set("automatic.source-id", state.automaticSourceId());
        yaml.set("automatic.modifiers", state.automaticModifierIds());
        yaml.set("override.source-type", state.overrideSourceType() == null
                ? null : state.overrideSourceType().name());
        yaml.set("override.source-id", state.overrideSourceId());
        yaml.set("override.modifiers", state.overrideModifierIds());
        yaml.set("override.duration-mode", state.overrideDurationMode() == null
                ? null : state.overrideDurationMode().name());
        yaml.set("override.activated-at", state.overrideActivatedAtMillis());
        yaml.set("override.expires-at", state.overrideExpiresAtMillis());
        yaml.set("emitted-warnings", state.emittedWarningsSeconds().stream().sorted().toList());
        yaml.set("selection-sequence", state.selectionSequence());
        byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), "warzone-state-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            if (Files.isRegularFile(file))
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void reportSemanticValidationFailure(String reason) {
        lastFailure = reason == null ? "semantic state validation failed" : reason;
        logger.warning("Stored Warzone state was rejected safely: " + lastFailure);
    }

    public boolean healthy() { return lastFailure.isEmpty(); }
    public String health() { return healthy() ? "healthy" : "failed: " + lastFailure; }

    private static void validateSource(String path, SelectionSourceType type, String sourceId,
                                       List<String> modifiers, boolean manual) {
        if (manual && type != SelectionSourceType.RANDOM && type != SelectionSourceType.KIT
                && type != SelectionSourceType.CUSTOM_OVERRIDE)
            throw new IllegalArgumentException(path + " source type " + type + " is not valid for a manual override");
        if (!manual && type == SelectionSourceType.CUSTOM_OVERRIDE)
            throw new IllegalArgumentException(path + " source type CUSTOM_OVERRIDE is not automatic");
        if (type == SelectionSourceType.KIT && sourceId == null)
            throw new IllegalArgumentException(path + " KIT source requires a source ID");
        if (type != SelectionSourceType.KIT && sourceId != null)
            throw new IllegalArgumentException(path + " source ID is only valid for KIT");
        if (type == SelectionSourceType.NONE && !modifiers.isEmpty())
            throw new IllegalArgumentException(path + " NONE source must not contain modifiers");
        if ((type == SelectionSourceType.RANDOM || type == SelectionSourceType.SCHEDULED_MODIFIERS)
                && modifiers.isEmpty())
            throw new IllegalArgumentException(path + " source type " + type
                    + " requires at least one modifier");
    }

    private static Boolean optionalBoolean(YamlConfiguration yaml, String path) {
        if (!yaml.contains(path)) return null;
        Object raw = yaml.get(path);
        if (raw instanceof Boolean value) return value;
        throw new IllegalArgumentException(path + " must be true or false");
    }

    private static int integer(YamlConfiguration yaml, String path,
                               boolean required, int fallback) {
        long value = number(yaml, path, required, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(path + " must be a 32-bit integer");
        return (int) value;
    }

    private static long number(YamlConfiguration yaml, String path,
                               boolean required, long fallback) {
        if (!yaml.contains(path)) {
            if (required) throw new IllegalArgumentException("missing " + path);
            return fallback;
        }
        Object raw = yaml.get(path);
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long) return ((Number) raw).longValue();
        throw new IllegalArgumentException(path + " must be an integer");
    }

    private static String string(YamlConfiguration yaml, String path, boolean required) {
        if (!yaml.contains(path)) {
            if (required) throw new IllegalArgumentException("missing " + path);
            return null;
        }
        Object raw = yaml.get(path);
        if (raw instanceof String value) return value.trim();
        throw new IllegalArgumentException(path + " must be a string");
    }

    private static List<String> stringList(YamlConfiguration yaml, String path,
                                           boolean required) {
        if (!yaml.contains(path)) {
            if (required) throw new IllegalArgumentException("missing " + path);
            return List.of();
        }
        Object raw = yaml.get(path);
        if (!(raw instanceof List<?> values))
            throw new IllegalArgumentException(path + " must be a list of strings");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text))
                throw new IllegalArgumentException(path + " must contain only strings");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static List<Long> longList(YamlConfiguration yaml, String path,
                                       boolean required) {
        if (!yaml.contains(path)) {
            if (required) throw new IllegalArgumentException("missing " + path);
            return List.of();
        }
        Object raw = yaml.get(path);
        if (!(raw instanceof List<?> values))
            throw new IllegalArgumentException(path + " must be a list of integers");
        java.util.ArrayList<Long> result = new java.util.ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long))
                throw new IllegalArgumentException(path + " must contain only integers");
            result.add(((Number) value).longValue());
        }
        return List.copyOf(result);
    }

    private static List<String> stableIds(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values.stream()
                .map(String::trim).filter(value -> !value.isEmpty()).toList()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static SelectionSourceType source(String value, boolean required) {
        SelectionSourceType result = enumValue(value, SelectionSourceType.class, required);
        if (required && result == null) throw new IllegalArgumentException("missing source type");
        return result;
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("missing " + type.getSimpleName());
            return null;
        }
        try { return Enum.valueOf(type, value.trim()); }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + " '" + value + "'");
        }
    }
}
