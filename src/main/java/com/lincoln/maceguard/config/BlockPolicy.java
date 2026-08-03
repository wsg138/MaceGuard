package com.lincoln.maceguard.config;

import org.bukkit.Material;

import java.util.Set;

public record BlockPolicy(
        String name,
        MaterialRule place,
        MaterialRule breakRule,
        BucketRule buckets,
        LiquidRule liquids,
        boolean allowNonPlayerSources
) {
    public record MaterialRule(boolean denyUnlisted, Set<Material> materials) {
        public MaterialRule { materials = Set.copyOf(materials); }
        public boolean allows(Material material) {
            return denyUnlisted ? materials.contains(material) : !materials.contains(material);
        }
    }

    public record BucketRule(Set<Material> empty, Set<Material> fill) {
        public BucketRule {
            empty = Set.copyOf(empty);
            fill = Set.copyOf(fill);
        }
    }

    public record LiquidRule(boolean confineToRegion, boolean blockInfiniteWaterSources) { }
}
