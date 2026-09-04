package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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

    @Test void worldGuardRailGrantPreAllowsOnlyItsDelegateDecision() {
        CartHarness harness = cartHarness();
        Block placed = mock(Block.class);
        Player player = mock(Player.class);
        BlockPlaceEvent original = mock(BlockPlaceEvent.class);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(placed.getType()).thenReturn(Material.RAIL);
        when(placed.getLocation()).thenReturn(harness.location);
        when(original.getBlockPlaced()).thenReturn(placed);
        when(original.getPlayer()).thenReturn(player);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(harness.worldGuard.blockPlaceAllowed(harness.location, player)).thenReturn(false);

        harness.listener.onWorldGuardCartBlockPlace(delegate);

        verify(delegate).setAllowed(true);
        verify(original, never()).setCancelled(false);
    }

    @Test void preCancelledRailPlacementIsNeverReopened() {
        CartHarness harness = cartHarness();
        Block placed = mock(Block.class);
        Player player = mock(Player.class);
        BlockPlaceEvent original = mock(BlockPlaceEvent.class);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(placed.getType()).thenReturn(Material.RAIL);
        when(placed.getLocation()).thenReturn(harness.location);
        when(original.getBlockPlaced()).thenReturn(placed);
        when(original.getPlayer()).thenReturn(player);
        when(original.isCancelled()).thenReturn(true);
        when(delegate.getOriginalEvent()).thenReturn(original);

        harness.listener.onWorldGuardCartBlockPlace(delegate);

        verify(delegate, never()).setAllowed(true);
        verify(original, never()).setCancelled(false);
    }

    @Test void preCancelledCartPrimeRemainsCancelled() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(event.getEntity()).thenReturn(cart);
        when(event.isCancelled()).thenReturn(true);

        harness.listener.onPrime(event);

        verify(event, never()).setCancelled(false);
        verify(event, never()).setCancelled(true);
    }

    @Test void preCancelledCartExplosionRemainsCancelledAndDoesNotRewriteBlocks() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(event.getEntity()).thenReturn(cart);
        when(event.getLocation()).thenReturn(harness.location);
        when(event.isCancelled()).thenReturn(true);

        harness.listener.onEntityExplosion(event);

        verify(event, never()).setCancelled(false);
        verify(event, never()).blockList();
    }

    @Test void allowedCartExplosionClearsBlocksWithoutCancellingEntityEffects() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        blocks.add(mock(Block.class));
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(event.getEntity()).thenReturn(cart);
        when(event.getLocation()).thenReturn(harness.location);
        when(event.isCancelled()).thenReturn(false);
        when(event.blockList()).thenReturn(blocks);

        harness.listener.onEntityExplosion(event);

        assertTrue(blocks.isEmpty());
        verify(event, never()).setCancelled(false);
        verify(event, never()).setCancelled(true);
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

    private CartHarness cartHarness() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        PluginRuntime pluginRuntime = mock(PluginRuntime.class);
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        Location location = mock(Location.class);
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                java.util.List.of("carts"), "Carts", "Carts",
                Set.of(WarzoneConfig.Effect.CARTS), Map.of());

        when(plugin.isFeatureEnabled()).thenReturn(true);
        when(plugin.runtime()).thenReturn(pluginRuntime);
        when(pluginRuntime.warzone()).thenReturn(module);
        when(module.runtime()).thenReturn(runtime);
        when(runtime.appliesAt(location)).thenReturn(true);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.active()).thenReturn(active);

        return new CartHarness(new ExplosiveControlListener(plugin, worldGuard, entity -> false),
                worldGuard, location);
    }

    private record CartHarness(ExplosiveControlListener listener,
                               WorldGuardQueryService worldGuard,
                               Location location) { }
}
