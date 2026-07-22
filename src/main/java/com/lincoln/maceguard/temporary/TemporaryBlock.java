package com.lincoln.maceguard.temporary;

public record TemporaryBlock(String worldUuid, int x, int y, int z, String expectedBlockData,
                             String originalBlockData, long expiresAt) { }
