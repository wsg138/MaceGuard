package com.lincoln.maceguard.policy;

import org.bukkit.Material;

/** Fail-closed destination allowlist shared by buckets and natural water flow. */
public final class WarzoneWaterProtection {
    private WarzoneWaterProtection() { }

    public static boolean canReplace(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR || material == Material.WATER
                || material == Material.COBWEB;
    }

    static boolean waterTransforms(Material original, Material formed) {
        return solidifiesFluid(original, formed) || hardensConcrete(original, formed);
    }

    private static boolean solidifiesFluid(Material original, Material formed) {
        return (original == Material.LAVA || original == Material.WATER)
                && (formed == Material.OBSIDIAN || formed == Material.COBBLESTONE
                || formed == Material.STONE);
    }

    private static boolean hardensConcrete(Material original, Material formed) {
        return original != null && formed != null
                && original.name().endsWith("_CONCRETE_POWDER")
                && formed.name().equals(original.name().replace("_POWDER", ""));
    }
}
