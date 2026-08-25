package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatCarryoverRestrictionTest {
    private final UUID playerId = UUID.randomUUID();
    private final RestrictionTarget mace = RestrictionTarget.parse("MACE").orElseThrow();
    private final WarzoneConfig.Restriction disabled =
            new WarzoneConfig.Restriction(mace, RestrictionMode.DISABLED, null);

    @Test void insideRegionAlwaysUsesFullRestrictions() {
        RestrictionService service = service(false, Map.of());
        assertEquals(RestrictionDecision.Result.DISABLED,
                service.material(playerId, Material.MACE, false, true, false).result());
    }

    @Test void outsideRequiresBothLatchAndExactCarriedRestriction() {
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service(false, Map.of(mace, disabled))
                        .material(playerId, Material.MACE, false, false, false).result());
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service(true, Map.of())
                        .material(playerId, Material.MACE, false, false, false).result());
        assertEquals(RestrictionDecision.Result.DISABLED,
                service(true, Map.of(mace, disabled))
                        .material(playerId, Material.MACE, false, false, false).result());
    }

    @Test void excludedActorSuppressesCarryoverOutsideEffectiveScope() {
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service(true, Map.of(mace, disabled))
                        .material(playerId, Material.MACE, false, false, false, true).result());
    }

    @Test void excludedActorCannotBypassRestrictionsAgainstActiveTarget() {
        assertEquals(RestrictionDecision.Result.DISABLED,
                service(true, Map.of(mace, disabled))
                        .material(playerId, Material.MACE, false, false, true, true).result());
    }

    @Test void bypassAlwaysWins() {
        assertEquals(RestrictionDecision.Result.UNRESTRICTED,
                service(true, Map.of(mace, disabled))
                        .material(playerId, Material.MACE, true, false, false).result());
    }

    private RestrictionService service(boolean latched,
                                       Map<RestrictionTarget, WarzoneConfig.Restriction> carried) {
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(List.of("mace"), "Mace", "",
                Set.of(), Map.of(mace, disabled), Set.of(), carried);
        return new RestrictionService(() -> active, new CooldownService(() -> 0L),
                ignored -> latched);
    }
}
