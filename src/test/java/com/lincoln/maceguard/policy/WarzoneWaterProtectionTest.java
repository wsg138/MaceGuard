package com.lincoln.maceguard.policy;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WarzoneWaterProtectionTest {
    @Test void everyNonCobwebBlockIsExcludedFromWaterReplacement() {
        Set<Material> allowed = Set.of(Material.AIR, Material.CAVE_AIR,
                Material.VOID_AIR, Material.WATER, Material.COBWEB);
        for (Material material : Material.values()) {
            assertEquals(allowed.contains(material), WarzoneWaterProtection.canReplace(material),
                    material.name());
        }
        assertFalse(WarzoneWaterProtection.canReplace(null));
    }
}
