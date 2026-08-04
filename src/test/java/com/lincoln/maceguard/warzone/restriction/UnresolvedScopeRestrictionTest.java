package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;

class UnresolvedScopeRestrictionTest {
    @Test void allMaterialAndLungeDecisionsAreInactiveOutsideEffectiveScope() {
        UUID player = UUID.randomUUID();
        AtomicLong now = new AtomicLong(1_000L);
        CooldownService cooldowns = new CooldownService(now::get);
        RestrictionTarget maceTarget = RestrictionTarget.parse("MACE").orElseThrow();
        RestrictionTarget pearlTarget = RestrictionTarget.parse("ENDER_PEARL").orElseThrow();
        RestrictionTarget windTarget = RestrictionTarget.parse("WIND_CHARGE").orElseThrow();
        WarzoneConfig.Restriction mace = new WarzoneConfig.Restriction(
                maceTarget, RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        WarzoneConfig.Restriction pearl = new WarzoneConfig.Restriction(
                pearlTarget, RestrictionMode.COOLDOWN, Duration.ofSeconds(5));
        WarzoneConfig.Restriction wind = new WarzoneConfig.Restriction(
                windTarget, RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        WarzoneConfig.Restriction lunge = new WarzoneConfig.Restriction(
                RestrictionTarget.SPEAR_LUNGE, RestrictionMode.DISABLED, null);
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(List.of("incident-test"),
                "Incident Test", "Incident Test", Set.of(),
                Map.of(maceTarget, mace, pearlTarget, pearl, windTarget, wind,
                        RestrictionTarget.SPEAR_LUNGE, lunge));
        RestrictionService service = new RestrictionService(() -> active, cooldowns);

        // Right click, placeable material, direct attack, projectile-style material, and mace use
        // all pass through this decision with both effective-scope booleans false.
        for (Material material : List.of(Material.MACE, Material.ENDER_PEARL,
                Material.WIND_CHARGE, Material.COBWEB, Material.STONE,
                Material.BOW, Material.FIREWORK_ROCKET)) {
            RestrictionDecision decision = service.material(player, material, false,
                    false, false);
            assertFalse(decision.denied());
            assertFalse(decision.startsCooldownAfterSuccess());
            service.success(player, decision);
        }

        RestrictionDecision lungeDecision = service.lunge(player, false, false, false);
        assertFalse(lungeDecision.denied());
        assertFalse(lungeDecision.startsCooldownAfterSuccess());
        assertFalse(cooldowns.active(player, maceTarget));
        assertFalse(cooldowns.active(player, pearlTarget));
        assertFalse(cooldowns.active(player, windTarget));
        assertFalse(cooldowns.active(player, RestrictionTarget.SPEAR_LUNGE));
    }
}
