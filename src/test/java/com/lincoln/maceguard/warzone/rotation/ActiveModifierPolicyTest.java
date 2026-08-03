package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveModifierPolicyTest {
    private final ModifierSelector selector = new ModifierSelector(new java.util.Random(1L));

    @Test void maceDisabledAndMaceCooldownRemainDistinctMutuallyExclusiveModes() {
        var config = ModifierSelectorTest.config(1, 3);
        RestrictionTarget mace = RestrictionTarget.parse("MACE").orElseThrow();

        var disabled = selector.compose(config, List.of("mace-disabled"));
        assertEquals(RestrictionMode.DISABLED, disabled.restrictions().get(mace).mode());

        var cooldown = selector.compose(config, List.of("mace-cooldown"));
        assertEquals(RestrictionMode.COOLDOWN, cooldown.restrictions().get(mace).mode());
        assertEquals(Duration.ofSeconds(10), cooldown.restrictions().get(mace).cooldown());
    }

    @Test void elytraModifierAllowsGlidingButBlocksActualBoosts() {
        var active = selector.compose(ModifierSelectorTest.config(1, 3),
                List.of("elytra-no-rockets"));
        assertTrue(active.elytraGlidingAllowed());
        assertTrue(active.fireworkBoostBlocked());
    }

    @Test void inactiveElytraModifierBlocksStartingGlideButDoesNotCreateRocketRule() {
        var active = selector.compose(ModifierSelectorTest.config(1, 3), List.of("cobwebs"));
        assertFalse(active.elytraGlidingAllowed());
        assertFalse(active.fireworkBoostBlocked());
    }
}
