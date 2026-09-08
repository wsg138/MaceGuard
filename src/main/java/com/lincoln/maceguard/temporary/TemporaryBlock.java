package com.lincoln.maceguard.temporary;

public record TemporaryBlock(String worldUuid, int x, int y, int z, String expectedBlockData,
                             String originalBlockData, long expiresAt, boolean pendingClear,
                             boolean warzoneOwned, Kind kind) {
    public enum Kind {
        COBWEB,
        CART_RAIL
    }

    /** Backward-compatible constructor for existing callers and pre-kind persisted state. */
    public TemporaryBlock(String worldUuid, int x, int y, int z, String expectedBlockData,
                          String originalBlockData, long expiresAt, boolean pendingClear,
                          boolean warzoneOwned) {
        this(worldUuid, x, y, z, expectedBlockData, originalBlockData, expiresAt, pendingClear,
                warzoneOwned, Kind.COBWEB);
    }

    /** Old JSON snapshots deserialize a missing kind as null; treat those as legacy cobwebs. */
    public boolean isKind(Kind expected) {
        Kind effective = kind == null ? Kind.COBWEB : kind;
        return effective == expected;
    }

    public TemporaryBlock withPendingClear() {
        return pendingClear ? this : new TemporaryBlock(worldUuid, x, y, z, expectedBlockData,
                originalBlockData, expiresAt, true, warzoneOwned, kind);
    }
}
