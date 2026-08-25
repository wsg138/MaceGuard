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

    @Test void gateAcceptsHorizontalLungeWhileVerticalVelocityChanges() {
        AtomicLong nanos = new AtomicLong();
        LungeVelocityGate gate = new LungeVelocityGate(nanos::get, Duration.ofMillis(250));
        LungeVelocityGate.Vec3 look = new LungeVelocityGate.Vec3(0.6, 0.8, 0);
        gate.record(player, "IRON_SPEAR", look, new LungeVelocityGate.Vec3(0, -0.2, 0),
                false, true, true, RestrictionDecision.unrestricted());

        var attempt = gate.consumeIfLunge(player, new LungeVelocityGate.Vec3(0.6, 0.6, 0));
        assertTrue(attempt.isPresent());
        assertTrue(attempt.orElseThrow().targetInside());
        assertTrue(attempt.orElseThrow().actorExcluded());
        assertEquals("IRON_SPEAR", attempt.orElseThrow().materialName());
    }

    @Test void gateRejectsPerpendicularOrBackwardVelocity() {
        LungeVelocityGate gate = new LungeVelocityGate(() -> 0L, Duration.ofMillis(250));
        gate.record(player, "IRON_SPEAR", new LungeVelocityGate.Vec3(1, 0, 0),
                new LungeVelocityGate.Vec3(0, 0, 0), true, false, false,
                RestrictionDecision.unrestricted());
        assertTrue(gate.consumeIfLunge(player, new LungeVelocityGate.Vec3(0, 0, 1)).isEmpty());
        assertTrue(gate.consumeIfLunge(player, new LungeVelocityGate.Vec3(-1, 0, 0)).isEmpty());
        assertEquals(1, gate.size());
    }

    @Test void gateContextExpiresAndAnewAttackReplacesIt() {
        AtomicLong nanos = new AtomicLong();
        LungeVelocityGate gate = new LungeVelocityGate(nanos::get, Duration.ofMillis(250));
        gate.record(player, "IRON_SPEAR", new LungeVelocityGate.Vec3(1, 0, 0),
                new LungeVelocityGate.Vec3(0, 0, 0), false, true, false,
                RestrictionDecision.unrestricted());
        gate.record(player, "GOLDEN_SPEAR", new LungeVelocityGate.Vec3(0, 0, 1),
                new LungeVelocityGate.Vec3(0, 0, 0), true, false, true,
                RestrictionDecision.unrestricted());
        nanos.addAndGet(Duration.ofMillis(251).toNanos());
        assertTrue(gate.consumeIfLunge(player, new LungeVelocityGate.Vec3(0, 0, 1)).isEmpty());
        assertEquals(0, gate.size());
    }

    private RestrictionService service(RestrictionMode mode, Duration cooldown) {
        WarzoneConfig.Restriction restriction =
                new WarzoneConfig.Restriction(RestrictionTarget.SPEAR_LUNGE, mode, cooldown);
        WarzoneConfig.ActiveSet activeSet = new WarzoneConfig.ActiveSet(
                List.of("test"), "Test", "Test", Set.of(),
                Map.of(RestrictionTarget.SPEAR_LUNGE, restriction));
        return new RestrictionService(() -> activeSet, cooldowns);
    }
}
