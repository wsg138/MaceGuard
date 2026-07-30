package com.lincoln.maceguard.temporary;

public record TemporaryBlock(String worldUuid, int x, int y, int z, String expectedBlockData,
                             String originalBlockData, long expiresAt, boolean pendingClear,
                             boolean warzoneOwned) {
    public TemporaryBlock withPendingClear() {
        return pendingClear ? this : new TemporaryBlock(worldUuid, x, y, z, expectedBlockData,
                originalBlockData, expiresAt, true, warzoneOwned);
    }
}
