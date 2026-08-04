package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpearRestrictionDecisionTest {
    @Test void authorizedProjectileImpactIsNotCancelledByCooldownStartedAtLaunch() {
        UUID player = UUID.randomUUID();
        long[] clock = {1000};
        CooldownService cooldowns = new CooldownService(() -> clock[0]);
        WarzoneConfig.Restriction cooldown = new WarzoneConfig.Restriction(
                RestrictionTarget.SPEAR, RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        RestrictionService service = new RestrictionService(
                () -> active(cooldown), cooldowns);

        RestrictionDecision launch = service.material(player, Material.WOODEN_SPEAR,
                false, true, false);
        assertTrue(launch.startsCooldownAfterSuccess());
        service.success(player, launch);
        assertTrue(service.material(player, Material.WOODEN_SPEAR,
                false, true, true).denied());
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service.materialDisableOnly(player, Material.WOODEN_SPEAR,
                        false, true, true).result());
    }

    @Test void newlyDisabledSpearStillBlocksInFlightProjectileDamage() {
        WarzoneConfig.Restriction disabled = new WarzoneConfig.Restriction(
                RestrictionTarget.SPEAR, RestrictionMode.DISABLED, Duration.ZERO);
        RestrictionService service = new RestrictionService(
                () -> active(disabled), new CooldownService(() -> 0));
        assertEquals(RestrictionDecision.Result.DISABLED,
                service.materialDisableOnly(UUID.randomUUID(), Material.WOODEN_SPEAR,
                        false, true, true).result());
    }

    private WarzoneConfig.ActiveSet active(WarzoneConfig.Restriction restriction) {
        return new WarzoneConfig.ActiveSet(List.of("test"), "test", "test", Set.of(),
                Map.of(restriction.target(), restriction));
    }
}
