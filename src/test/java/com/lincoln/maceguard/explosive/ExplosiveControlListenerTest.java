package com.lincoln.maceguard.explosive;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosiveControlListenerTest {
    @Test void windChargeVariantsBypassMaceGuardExplosivesFlag() {
        assertTrue(ExplosiveControlListener.isWindCharge(EntityType.WIND_CHARGE));
        assertTrue(ExplosiveControlListener.isWindCharge(EntityType.BREEZE_WIND_CHARGE));
    }

    @Test void ordinaryExplosivesRemainControlled() {
        assertFalse(ExplosiveControlListener.isWindCharge(EntityType.TNT));
        assertFalse(ExplosiveControlListener.isWindCharge(EntityType.TNT_MINECART));
        assertFalse(ExplosiveControlListener.isWindCharge(EntityType.END_CRYSTAL));
        assertFalse(ExplosiveControlListener.isWindCharge(EntityType.CREEPER));
    }
}
