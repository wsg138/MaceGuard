package com.lincoln.maceguard.warzone.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.EnderPearl;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Authoritative launch metadata persisted on the Ender Pearl entity. */
final class StasisPearlMetadata {
    static final String NAMESPACE = "maceguard";
    static final int FORMAT_VERSION = 1;
    static final String MARKER_KEY = "stasis-pearl-marker";
    static final String FORMAT_KEY = "stasis-pearl-format";
    static final String OWNER_KEY = "stasis-pearl-owner";
    static final String LAUNCHED_AT_KEY = "stasis-pearl-launched-at";
    private static final byte MARKER_VALUE = 1;
    private static final long MAX_REASONABLE_AGE_MILLIS = Duration.ofDays(365).toMillis();

    private final NamespacedKey markerKey = key(MARKER_KEY);
    private final NamespacedKey formatKey = key(FORMAT_KEY);
    private final NamespacedKey ownerKey = key(OWNER_KEY);
    private final NamespacedKey launchedAtKey = key(LAUNCHED_AT_KEY);

    StasisPearlMetadata() { }

    static NamespacedKey key(String value) {
        return new NamespacedKey(NAMESPACE, value);
    }

    void mark(EnderPearl pearl, UUID ownerId, long launchedAtMillis) {
        PersistentDataContainer data = pearl.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, MARKER_VALUE);
        data.set(formatKey, PersistentDataType.INTEGER, FORMAT_VERSION);
        data.set(ownerKey, PersistentDataType.STRING, ownerId.toString());
        data.set(launchedAtKey, PersistentDataType.LONG, launchedAtMillis);
    }

    ReadResult read(EnderPearl pearl, UUID entityOwner, long nowMillis) {
        PersistentDataContainer data = pearl.getPersistentDataContainer();
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        if (marker == null) return ReadResult.unmarked();

        Optional<ReadResult> markerFailure = validateMarker(marker, entityOwner);
        if (markerFailure.isPresent()) return markerFailure.orElseThrow();

        Optional<ReadResult> formatFailure = validateFormat(data, entityOwner);
        if (formatFailure.isPresent()) return formatFailure.orElseThrow();

        OwnerValidation owner = validateOwner(data, entityOwner);
        if (owner.failure().isPresent()) return owner.failure().orElseThrow();
        return readTimestamp(data, owner.ownerId(), nowMillis);
    }

    private Optional<ReadResult> validateMarker(Byte marker, UUID entityOwner) {
        if (marker == MARKER_VALUE) return Optional.empty();
        return Optional.of(ReadResult.invalid(entityOwner, 0L, "invalid tracking marker"));
    }

    private Optional<ReadResult> validateFormat(PersistentDataContainer data, UUID entityOwner) {
        Integer format = data.get(formatKey, PersistentDataType.INTEGER);
        if (format == null)
            return Optional.of(ReadResult.invalid(entityOwner, 0L, "missing metadata format"));
        if (format == FORMAT_VERSION) return Optional.empty();
        return Optional.of(ReadResult.invalid(entityOwner, 0L,
                "unsupported metadata format " + format));
    }

    private OwnerValidation validateOwner(PersistentDataContainer data, UUID entityOwner) {
        String ownerText = data.get(ownerKey, PersistentDataType.STRING);
        UUID metadataOwner;
        try {
            metadataOwner = ownerText == null ? null : UUID.fromString(ownerText);
        } catch (IllegalArgumentException malformed) {
            return OwnerValidation.invalid(
                    ReadResult.invalid(entityOwner, 0L, "malformed owner UUID"));
        }
        if (metadataOwner == null)
            return OwnerValidation.invalid(
                    ReadResult.invalid(entityOwner, 0L, "missing owner UUID"));
        if (entityOwner != null && !entityOwner.equals(metadataOwner))
            return OwnerValidation.invalid(ReadResult.invalid(
                    entityOwner, 0L, "entity owner does not match metadata owner"));
        return OwnerValidation.valid(metadataOwner);
    }

    private ReadResult readTimestamp(PersistentDataContainer data, UUID metadataOwner,
                                     long nowMillis) {
        Long launchedAt = data.get(launchedAtKey, PersistentDataType.LONG);
        if (launchedAt == null || launchedAt <= 0L)
            return ReadResult.invalid(metadataOwner, launchedAt == null ? 0L : launchedAt,
                    "missing or invalid launch timestamp");
        if (launchedAt > nowMillis)
            return ReadResult.invalid(metadataOwner, launchedAt,
                    "launch timestamp is in the future");
        long age = nowMillis - launchedAt;
        if (age > MAX_REASONABLE_AGE_MILLIS)
            return ReadResult.invalid(metadataOwner, launchedAt,
                    "launch timestamp is unreasonably old");
        return ReadResult.valid(metadataOwner, launchedAt);
    }

    private record OwnerValidation(UUID ownerId, Optional<ReadResult> failure) {
        static OwnerValidation valid(UUID ownerId) {
            return new OwnerValidation(ownerId, Optional.empty());
        }

        static OwnerValidation invalid(ReadResult failure) {
            return new OwnerValidation(failure.ownerId(), Optional.of(failure));
        }
    }

    record ReadResult(Status status, UUID ownerId, long launchedAtMillis, String diagnostic) {
        enum Status { UNMARKED, VALID, INVALID }
        static ReadResult unmarked() { return new ReadResult(Status.UNMARKED, null, 0L, null); }
        static ReadResult valid(UUID ownerId, long launchedAtMillis) {
            return new ReadResult(Status.VALID, ownerId, launchedAtMillis, null);
        }
        static ReadResult invalid(UUID ownerId, long launchedAtMillis, String diagnostic) {
            return new ReadResult(Status.INVALID, ownerId, launchedAtMillis, diagnostic);
        }
        boolean marked() { return status != Status.UNMARKED; }
        boolean failClosed() { return status == Status.INVALID; }
    }
}
