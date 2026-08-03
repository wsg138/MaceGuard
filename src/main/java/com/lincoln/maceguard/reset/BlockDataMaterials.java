package com.lincoln.maceguard.reset;

import org.bukkit.Material;

import java.util.Locale;

final class BlockDataMaterials {
    private BlockDataMaterials() { }

    static Material of(String blockData) {
        if (blockData == null || blockData.isBlank())
            throw new IllegalArgumentException("missing block data");
        String key = blockData;
        int properties = key.indexOf('[');
        if (properties >= 0) key = key.substring(0, properties);
        int namespace = key.indexOf(':');
        if (namespace >= 0) key = key.substring(namespace + 1);
        Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
        if (material == null) throw new IllegalArgumentException("unknown material");
        return material;
    }

    static boolean isAir(String blockData) {
        Material material = of(blockData);
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }
}
