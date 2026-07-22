package com.lincoln.maceguard.worldguard;

import java.util.UUID;

public record RegionDescriptor(String id, String worldName, UUID worldUuid, String type,
                               int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                               String geometryHash, long volume) {
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
