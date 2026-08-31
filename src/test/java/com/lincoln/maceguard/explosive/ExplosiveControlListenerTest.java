package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test void cartModifierAllowsOnlyTheFourVanillaRailBlocks() {
        assertTrue(ExplosiveControlListener.isCartRail(Material.RAIL));
        assertTrue(ExplosiveControlListener.isCartRail(Material.POWERED_RAIL));
        assertTrue(ExplosiveControlListener.isCartRail(Material.DETECTOR_RAIL));
        assertTrue(ExplosiveControlListener.isCartRail(Material.ACTIVATOR_RAIL));
        assertFalse(ExplosiveControlListener.isCartRail(Material.MINECART));
        assertFalse(ExplosiveControlListener.isCartRail(Material.TNT));
        assertFalse(ExplosiveControlListener.isCartRail(Material.REDSTONE_WIRE));
    }

    @Test void cartGrantOnlyReopensWorldGuardDeniedCancellation() {
        assertTrue(ExplosiveControlListener.shouldReopenCartGrant(true, false));
        assertFalse(ExplosiveControlListener.shouldReopenCartGrant(true, true));
        assertFalse(ExplosiveControlListener.shouldReopenCartGrant(false, false));
        assertFalse(ExplosiveControlListener.shouldReopenCartGrant(false, true));
    }

    @Test void windBurstClassificationRequiresMaceAndEnchant() {
        assertTrue(ExplosiveControlListener.isWindBurstMace(Material.MACE, true));
        assertFalse(ExplosiveControlListener.isWindBurstMace(Material.MACE, false));
        assertFalse(ExplosiveControlListener.isWindBurstMace(Material.DIAMOND_SWORD, true));
    }

    @Test
    void windBurstPrimeIsNotCancelledByExplosivesDeny() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard, entity -> true);
        Player player = mock(Player.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getEntityType()).thenReturn(EntityType.PLAYER);

        listener.onPrime(event);

        verify(event, never()).setCancelled(true);
        verify(worldGuard, never()).explosivesDenied(any(Location.class), isNull());
    }

    @Test
    void nonWindBurstPlayerExplosionStillRespectsExplosivesDeny() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard, entity -> false);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getEntityType()).thenReturn(EntityType.PLAYER);
        when(player.getLocation()).thenReturn(location);
        when(plugin.isFeatureEnabled()).thenReturn(true);
        when(worldGuard.explosivesDenied(location, null)).thenReturn(true);

        listener.onPrime(event);

        verify(event).setCancelled(true);
    }
}
