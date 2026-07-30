package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LungeRestrictionTest {
    private final UUID player = UUID.randomUUID();
    private final AtomicLong millis = new AtomicLong(1_000);
    private final CooldownService cooldowns = new CooldownService(millis::get);

    @Test void disabledLungeDoesNotDisableTheSpearItem() {
        RestrictionService service = service(RestrictionMode.DISABLED, null);
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service.material(player, Material.IRON_SPEAR, false, true, false).result());
        assertEquals(RestrictionDecision.Result.DISABLED,
                service.lunge(player, false, true, false).result());
    }

    @Test void cooldownAllowsFirstLungeAndStartsOnlyAfterSuccess() {
        RestrictionService service = service(RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        RestrictionDecision first = service.lunge(player, false, true, false);
        assertEquals(RestrictionDecision.Result.COOLDOWN_READY, first.result());
        assertFalse(cooldowns.active(player, RestrictionTarget.SPEAR_LUNGE));
        service.success(player, first);
        assertTrue(cooldowns.active(player, RestrictionTarget.SPEAR_LUNGE));
    }

    @Test void cancelledLungeDecisionDoesNotStartCooldownWithoutSuccessCallback() {
        RestrictionService service = service(RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        RestrictionDecision accepted = service.lunge(player, false, true, false);
        assertTrue(accepted.startsCooldownAfterSuccess());
        assertFalse(cooldowns.active(player, RestrictionTarget.SPEAR_LUNGE));
    }

    @Test void activeLungeCooldownSuppressesOnlyTheLungeDecision() {
        RestrictionService service = service(RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        service.success(player, service.lunge(player, false, true, false));
        assertEquals(RestrictionDecision.Result.COOLDOWN_ACTIVE,
                service.lunge(player, false, true, false).result());
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service.material(player, Material.IRON_SPEAR, false, true, true).result());
    }

    @Test void normalSpearAttackRemainsUnrestrictedDuringLungeCooldown() {
        RestrictionService service = service(RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        service.success(player, service.lunge(player, false, true, false));
        RestrictionDecision attack = service.material(player, Material.IRON_SPEAR, false, true, true);
        assertFalse(attack.denied());
        assertFalse(attack.startsCooldownAfterSuccess());
    }

    @Test void targetContextExpiresAndAnewAttackReplacesIt() {
        AtomicLong nanos = new AtomicLong();
        LungeTargetTracker tracker = new LungeTargetTracker(nanos::get, Duration.ofMillis(450));
        tracker.record(player, true);
        assertTrue(tracker.targetInside(player));
        tracker.record(player, false);
        assertFalse(tracker.targetInside(player));
        tracker.record(player, true);
        nanos.addAndGet(Duration.ofMillis(451).toNanos());
        assertFalse(tracker.targetInside(player));
        assertEquals(0, tracker.size());
    }

    private RestrictionService service(RestrictionMode mode, Duration cooldown) {
        WarzoneConfig.Restriction restriction =
                new WarzoneConfig.Restriction(RestrictionTarget.SPEAR_LUNGE, mode, cooldown);
        WarzoneConfig.Rotation rotation = new WarzoneConfig.Rotation("test", "Test", "Test",
                Duration.ofHours(1), true, Map.of(RestrictionTarget.SPEAR_LUNGE, restriction), "", null, null);
        return new RestrictionService(() -> rotation, cooldowns);
    }
}
