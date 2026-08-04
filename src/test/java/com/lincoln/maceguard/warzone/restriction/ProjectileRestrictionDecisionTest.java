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

import static org.junit.jupiter.api.Assertions.*;

class ProjectileRestrictionDecisionTest {
    @Test void disabledPearlAndWindChargeAreDeniedOnlyInsideEffectiveScope() {
        RestrictionTarget pearl = target("ENDER_PEARL");
        RestrictionTarget wind = target("WIND_CHARGE");
        RestrictionService service = service(Map.of(
                pearl, restriction(pearl, RestrictionMode.DISABLED, null),
                wind, restriction(wind, RestrictionMode.DISABLED, null)), new AtomicLong(1_000L));
        UUID player = UUID.randomUUID();

        assertTrue(service.material(player, Material.ENDER_PEARL,
                false, true, false).denied());
        assertTrue(service.material(player, Material.WIND_CHARGE,
                false, true, false).denied());
        assertFalse(service.material(player, Material.ENDER_PEARL,
                false, false, false).denied());
        assertFalse(service.material(player, Material.WIND_CHARGE,
                true, true, false).denied());
    }

    @Test void pearlCooldownBeginsOnlyAfterExplicitSuccessfulLaunch() {
        assertCooldownBeginsOnlyAfterSuccess("ENDER_PEARL", Material.ENDER_PEARL,
                Duration.ofSeconds(5));
        assertCooldownBeginsOnlyAfterSuccess("ENDER_PEARL", Material.ENDER_PEARL,
                Duration.ofSeconds(10));
    }

    @Test void windChargeCooldownBeginsOnlyAfterExplicitSuccessfulLaunch() {
        assertCooldownBeginsOnlyAfterSuccess("WIND_CHARGE", Material.WIND_CHARGE,
                Duration.ofSeconds(5));
        assertCooldownBeginsOnlyAfterSuccess("WIND_CHARGE", Material.WIND_CHARGE,
                Duration.ofSeconds(10));
    }

    private void assertCooldownBeginsOnlyAfterSuccess(String targetId, Material material,
                                                       Duration duration) {
        AtomicLong now = new AtomicLong(1_000L);
        RestrictionTarget target = target(targetId);
        CooldownService cooldowns = new CooldownService(now::get);
        RestrictionService service = service(Map.of(target,
                restriction(target, RestrictionMode.COOLDOWN, duration)), cooldowns);
        UUID player = UUID.randomUUID();

        RestrictionDecision pending = service.material(player, material,
                false, true, false);
        assertFalse(pending.denied());
        assertTrue(pending.startsCooldownAfterSuccess());
        assertFalse(cooldowns.active(player, target),
                "A decision alone must not start cooldown; cancelled launches stop here.");

        service.success(player, pending);
        assertTrue(cooldowns.active(player, target));
        RestrictionDecision active = service.material(player, material,
                false, true, false);
        assertTrue(active.denied());
        assertEquals(duration, active.remaining());

        now.addAndGet(duration.toMillis());
        RestrictionDecision readyAgain = service.material(player, material,
                false, true, false);
        assertFalse(readyAgain.denied());
        assertTrue(readyAgain.startsCooldownAfterSuccess());
    }

    private RestrictionService service(
            Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions,
            AtomicLong now) {
        return service(restrictions, new CooldownService(now::get));
    }

    private RestrictionService service(
            Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions,
            CooldownService cooldowns) {
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                List.of("projectile-test"), "Projectile Test", "Projectile Test",
                Set.of(), restrictions);
        return new RestrictionService(() -> active, cooldowns);
    }

    private WarzoneConfig.Restriction restriction(RestrictionTarget target,
                                                   RestrictionMode mode,
                                                   Duration cooldown) {
        return new WarzoneConfig.Restriction(target, mode, cooldown);
    }

    private RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }
}
