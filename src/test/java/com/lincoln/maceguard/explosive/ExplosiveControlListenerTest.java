package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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

    @Test
    void windBurstMacePlayerIsRecognizedAsEnchantmentExplosionSource() {
        assertTrue(ExplosiveControlListener.isWindBurstSource(windBurstPlayer(true)));
    }

    @Test
    void ordinaryPlayerExplosionIsNotMisclassifiedAsWindBurst() {
        assertFalse(ExplosiveControlListener.isWindBurstSource(windBurstPlayer(false)));
    }

    @Test
    void windBurstPrimeIsNotCancelledByExplosivesDeny() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard);
        Player player = windBurstPlayer(true);
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
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard);
        Player player = windBurstPlayer(false);
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

    private Player windBurstPlayer(boolean enchanted) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack held = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(held.getType()).thenReturn(Material.MACE);
        when(held.containsEnchantment(Enchantment.WIND_BURST)).thenReturn(enchanted);
        return player;
    }
}
