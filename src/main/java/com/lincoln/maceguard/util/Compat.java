package com.lincoln.maceguard.util;

import org.bukkit.Material;

public final class Compat {
    private static final Material MACE_MATERIAL;

    static {
        Material found = null;
        try {
            found = Material.valueOf("MACE");
        } catch (IllegalArgumentException ignored) { }
        MACE_MATERIAL = found;
    }

    private Compat() {}

    public static boolean isMaceSupported() {
        return MACE_MATERIAL != null;
    }

    public static boolean isMace(Material mat) {
        return MACE_MATERIAL != null && mat == MACE_MATERIAL;
    }

    public static boolean isSpear(Material mat) {
        return mat != null && mat != Material.AIR && mat.name().contains("SPEAR");
    }

    public static boolean isSpearEntity(String entityTypeName) {
        return entityTypeName != null && entityTypeName.toUpperCase(java.util.Locale.ROOT).contains("SPEAR");
    }
}
