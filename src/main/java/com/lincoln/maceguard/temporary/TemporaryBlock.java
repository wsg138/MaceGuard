package com.lincoln.maceguard.temporary;

import java.util.Locale;

public record TemporaryBlock(String worldUuid, int x, int y, int z, String expectedBlockData,
                             String originalBlockData, long expiresAt, boolean pendingClear,
                             boolean warzoneOwned, Kind kind) {
    public enum Kind {
        COBWEB,
        CART_RAIL
    }

    /** Backward-compatible constructor for existing callers. */
    public TemporaryBlock(String worldUuid, int x, int y, int z, String expectedBlockData,
                          String originalBlockData, long expiresAt, boolean pendingClear,
                          boolean warzoneOwned) {
        this(worldUuid, x, y, z, expectedBlockData, originalBlockData, expiresAt, pendingClear,
                warzoneOwned, inferKind(expectedBlockData));
    }

    /** Old JSON snapshots deserialize a missing kind as null; infer those from managed material. */
    public boolean isKind(Kind expected) {
        Kind effective = kind == null ? inferKind(expectedBlockData) : kind;
        return effective == expected;
    }

    public TemporaryBlock withPendingClear() {
        return pendingClear ? this : new TemporaryBlock(worldUuid, x, y, z, expectedBlockData,
                originalBlockData, expiresAt, true, warzoneOwned, kind);
    }

    private static Kind inferKind(String serialized) {
        String material = materialName(serialized);
        return switch (material) {
            case "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL" -> Kind.CART_RAIL;
            default -> Kind.COBWEB;
        };
    }

    private static String materialName(String serialized) {
        if (serialized == null || serialized.isBlank()) return "";
        int properties = serialized.indexOf('[');
        String key = properties < 0 ? serialized : serialized.substring(0, properties);
        int namespace = key.lastIndexOf(':');
        String value = namespace < 0 ? key : key.substring(namespace + 1);
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
