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
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Arrow;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

class ExplosiveControlListenerTest {
    @Test void predictedBlockUseDenialDoesNotDenyFlintItemUse() {
        FlintHarness f = new FlintHarness();
        when(f.interaction.isCancelled()).thenReturn(true);
        when(f.interaction.useInteractedBlock()).thenReturn(org.bukkit.event.Event.Result.DENY);
        when(f.interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
        f.cart.listener.onWorldGuardCartUseItem(f.itemUse);
        verify(f.itemUse).setAllowed(true);
        verify(f.interaction, never()).setCancelled(false);
        clearInvocations(f.itemUse);
        when(f.interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY);
        f.cart.listener.onWorldGuardCartUseItem(f.itemUse);
        verify(f.itemUse, never()).setAllowed(true);
    }
    @Test void cartsGrantFlintBlockAndItemUseEvenWhenLighterAlreadyAllowed() {
        FlintHarness f = new FlintHarness();
        when(f.cart.worldGuard.lighterAllowed(f.cart.location, f.player)).thenReturn(true);
        f.cart.listener.onWorldGuardCartUseBlock(f.blockUse);
        f.cart.listener.onWorldGuardCartUseItem(f.itemUse);
        verify(f.blockUse).setAllowed(true);
        verify(f.itemUse).setAllowed(true);
        verify(f.interaction, never()).setCancelled(false);
    }

    @Test void cartsOffDoNotGrantFlintUse() {
        FlintHarness f = new FlintHarness();
        when(f.cart.runtime.rotations().active()).thenReturn(new WarzoneConfig.ActiveSet(
                java.util.List.of(), "None", "None", Set.of(), Map.of()));
        f.cart.listener.onWorldGuardCartUseBlock(f.blockUse);
        f.cart.listener.onWorldGuardCartUseItem(f.itemUse);
        verify(f.blockUse, never()).setAllowed(true);
        verify(f.itemUse, never()).setAllowed(true);
    }

    @Test void flintCannotCrossIntoSpawnOrOutsideWarzone() {
        FlintHarness f = new FlintHarness();
        Location outside = mock(Location.class);
        when(f.fire.getLocation()).thenReturn(outside);
        when(f.cart.runtime.appliesAt(outside)).thenReturn(false);
        f.cart.listener.onWorldGuardCartUseBlock(f.blockUse);
        verify(f.blockUse, never()).setAllowed(true);
    }

    @Test void playerOutsideWarzoneCannotGainFlintGrant() {
        FlintHarness f = new FlintHarness();
        Location outside = mock(Location.class);
        when(f.player.getLocation()).thenReturn(outside);
        when(f.cart.module.appliesAt(outside)).thenReturn(false);
        f.cart.listener.onWorldGuardCartUseBlock(f.blockUse);
        verify(f.blockUse, never()).setAllowed(true);
    }

    @Test void cancelledAndLeftClickFlintInteractionsAreNotReopened() {
        FlintHarness f = new FlintHarness();
        when(f.interaction.isCancelled()).thenReturn(true);
        when(f.interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY);
        f.cart.listener.onWorldGuardCartUseBlock(f.blockUse);
        when(f.interaction.isCancelled()).thenReturn(false);
        when(f.interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
        when(f.interaction.getAction()).thenReturn(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK);
        f.cart.listener.onWorldGuardCartUseItem(f.itemUse);
        verify(f.blockUse, never()).setAllowed(true);
        verify(f.itemUse, never()).setAllowed(true);
    }

    @Test void flintGrantDoesNotPermitOrdinaryBlockPlacementOrTnt() {
        FlintHarness f = new FlintHarness();
        var place = mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(place.getOriginalEvent()).thenReturn(f.interaction);
        for (Material material : java.util.List.of(Material.STONE, Material.TNT, Material.NETHER_PORTAL)) {
            when(place.getEffectiveMaterial()).thenReturn(material);
            f.cart.listener.onWorldGuardCartBlockPlace(place);
        }
        verify(place, never()).setAllowed(true);
    }

    @Test void flintGrantCoversCandleAndCampfireModification() {
        FlintHarness f = new FlintHarness();
        for (Material material : java.util.List.of(Material.CANDLE, Material.RED_CANDLE,
                Material.CANDLE_CAKE, Material.RED_CANDLE_CAKE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)) {
            var place = mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
            when(place.getOriginalEvent()).thenReturn(f.interaction);
            when(place.getEffectiveMaterial()).thenReturn(material);
            f.cart.listener.onWorldGuardCartBlockPlace(place);
            verify(place).setAllowed(true);
        }
    }

    @Test void fireIgnitionGrantDoesNotDependOnLighterFlagDenial() {
        FlintHarness f = new FlintHarness();
        var original = mock(org.bukkit.event.block.BlockIgniteEvent.class);
        var place = mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(original.getCause()).thenReturn(org.bukkit.event.block.BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL);
        when(original.getPlayer()).thenReturn(f.player);
        when(original.getBlock()).thenReturn(f.fire);
        when(place.getOriginalEvent()).thenReturn(original);
        when(f.cart.worldGuard.lighterAllowed(f.cart.location, f.player)).thenReturn(true);
        f.cart.listener.onWorldGuardCartBlockPlace(place);
        verify(place).setAllowed(true);
        verify(original, never()).setCancelled(false);
    }

    @Test void holdingFlintDoesNotGrantChestDoorOrOrdinaryTntAccess() {
        FlintHarness f = new FlintHarness();
        for (Material material : java.util.List.of(Material.CHEST, Material.OAK_DOOR, Material.TNT)) {
            when(f.clicked.getType()).thenReturn(material);
            f.cart.listener.onWorldGuardCartUseBlock(f.blockUse);
        }
        verify(f.blockUse, never()).setAllowed(true);
    }

    private final class FlintHarness {
        final CartHarness cart = cartHarness();
        final Player player = mock(Player.class);
        final Block clicked = mock(Block.class);
        final Block fire = mock(Block.class);
        final org.bukkit.event.player.PlayerInteractEvent interaction =
                mock(org.bukkit.event.player.PlayerInteractEvent.class);
        final com.sk89q.worldguard.bukkit.event.block.UseBlockEvent blockUse =
                mock(com.sk89q.worldguard.bukkit.event.block.UseBlockEvent.class);
        final com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent itemUse =
                mock(com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent.class);

        FlintHarness() {
            org.mockito.Mockito.doAnswer(invocation -> {
                Material material = invocation.getArgument(0);
                return material == Material.CHEST || material == Material.OAK_DOOR;
            }).when(cart.listener).isInteractableMaterial(any(Material.class));
            var item = mock(org.bukkit.inventory.ItemStack.class);
            when(item.getType()).thenReturn(Material.FLINT_AND_STEEL);
            when(interaction.getItem()).thenReturn(item);
            when(interaction.getPlayer()).thenReturn(player);
            when(interaction.getClickedBlock()).thenReturn(clicked);
            when(interaction.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK);
            when(interaction.getBlockFace()).thenReturn(org.bukkit.block.BlockFace.UP);
            when(clicked.getRelative(org.bukkit.block.BlockFace.UP)).thenReturn(fire);
            when(clicked.getLocation()).thenReturn(cart.location);
            when(clicked.getType()).thenReturn(Material.STONE);
            when(fire.getLocation()).thenReturn(cart.location);
            when(player.getLocation()).thenReturn(cart.location);
            when(blockUse.getOriginalEvent()).thenReturn(interaction);
            when(itemUse.getOriginalEvent()).thenReturn(interaction);
        }
    }

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

    @Test void worldGuardGlobalTntCancellationIsReopenedForActiveOwnedCart() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getEntity()).thenReturn(cart);
        // NORMAL observes an originally-clear event; HIGH observes WorldGuard's cancellation.
        when(event.isCancelled()).thenReturn(false, true);
        when(harness.worldGuard.tntExplosionsGloballyBlocked(harness.location)).thenReturn(true);

        harness.listener.onCartPrimeBeforeWorldGuard(event);
        harness.listener.onCartPrimeWorldGuardBypass(event);

        verify(event).setCancelled(false);
        verify(event, never()).setCancelled(true);
    }

    @Test void cancellationAlreadyPresentBeforeWorldGuardIsNeverReopened() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getEntity()).thenReturn(cart);
        when(event.isCancelled()).thenReturn(true);
        when(harness.worldGuard.tntExplosionsGloballyBlocked(harness.location)).thenReturn(true);

        harness.listener.onCartPrimeBeforeWorldGuard(event);
        harness.listener.onCartPrimeWorldGuardBypass(event);

        verify(event, never()).setCancelled(false);
        verify(event, never()).setCancelled(true);
    }

    @Test void preCancelledCartExplosionRemainsCancelledAndDoesNotRewriteBlocks() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getEntity()).thenReturn(cart);
        when(event.getLocation()).thenReturn(harness.location);
        when(event.isCancelled()).thenReturn(true);

        harness.listener.onEntityExplosion(event);

        verify(event, never()).setCancelled(false);
        verify(event, never()).blockList();
    }

    @Test void allowedCartExplosionClearsBlocksBeforeAndAfterWorldGuard() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        blocks.add(mock(Block.class));
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getEntity()).thenReturn(cart);
        when(event.getLocation()).thenReturn(harness.location);
        when(event.isCancelled()).thenReturn(false);
        when(event.blockList()).thenReturn(blocks);

        harness.listener.onCartExplosionPrepare(event);
        assertTrue(blocks.isEmpty());
        harness.listener.onEntityExplosion(event);

        assertTrue(blocks.isEmpty());
        verify(event, never()).setCancelled(false);
        verify(event, never()).setCancelled(true);
    }

    @Test void ownedCartExplosionDamageCannotCrossOutOfWarzone() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        Entity victim = mock(Entity.class);
        DamageSource source = mock(DamageSource.class);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        Location outside = mock(Location.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(source.getDirectEntity()).thenReturn(cart);
        when(event.getDamageSource()).thenReturn(source);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        when(event.getEntity()).thenReturn(victim);
        when(victim.getLocation()).thenReturn(outside);
        when(harness.runtime.appliesAt(outside)).thenReturn(false);

        harness.listener.onExplosionDamage(event);

        verify(event).setCancelled(true);
    }

    @Test void ownedCartExplosionDamageRemainsAllowedInsideWarzone() {
        CartHarness harness = cartHarness();
        Entity cart = mock(Entity.class);
        Entity victim = mock(Entity.class);
        DamageSource source = mock(DamageSource.class);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(source.getDirectEntity()).thenReturn(cart);
        when(event.getDamageSource()).thenReturn(source);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION);
        when(event.getEntity()).thenReturn(victim);
        when(victim.getLocation()).thenReturn(harness.location);

        harness.listener.onExplosionDamage(event);

        verify(event, never()).setCancelled(true);
    }

    @Test void worldGuardArrowGrantIncludesSelfDamageInsideWarzone() {
        CartHarness harness = cartHarness();
        Player shooter = mock(Player.class);
        Arrow arrow = mock(Arrow.class);
        EntityDamageByEntityEvent original = mock(EntityDamageByEntityEvent.class);
        com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent.class);
        when(arrow.getShooter()).thenReturn(shooter);
        when(original.getDamager()).thenReturn(arrow);
        when(original.getEntity()).thenReturn(shooter);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(delegate.getEntity()).thenReturn(shooter);
        when(delegate.getTarget()).thenReturn(harness.location);
        when(shooter.getLocation()).thenReturn(harness.location);

        harness.listener.onWorldGuardWarzoneArrowDamage(delegate);

        verify(delegate).setAllowed(true);
    }

    @Test void worldGuardArrowGrantDoesNotReachOutsideWarzone() {
        CartHarness harness = cartHarness();
        Player shooter = mock(Player.class);
        Player target = mock(Player.class);
        Arrow arrow = mock(Arrow.class);
        Location outside = mock(Location.class);
        EntityDamageByEntityEvent original = mock(EntityDamageByEntityEvent.class);
        com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent.class);
        when(arrow.getShooter()).thenReturn(shooter);
        when(original.getDamager()).thenReturn(arrow);
        when(original.getEntity()).thenReturn(target);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(delegate.getEntity()).thenReturn(target);
        when(delegate.getTarget()).thenReturn(outside);
        when(shooter.getLocation()).thenReturn(harness.location);
        when(harness.runtime.appliesAt(outside)).thenReturn(false);
        when(harness.module.appliesAt(outside)).thenReturn(false);

        harness.listener.onWorldGuardWarzoneArrowDamage(delegate);

        verify(delegate, never()).setAllowed(true);
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
        when(module.appliesAt(any(Location.class))).thenReturn(true);
        when(runtime.appliesAt(location)).thenReturn(true);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.active()).thenReturn(active);

        return new CartHarness(org.mockito.Mockito.spy(new ExplosiveControlListener(plugin, worldGuard, entity -> false)),
                worldGuard, runtime, module, location);
    }

    private record CartHarness(ExplosiveControlListener listener,
                               WorldGuardQueryService worldGuard,
                               WarzoneRuntime runtime, WarzoneModule module, Location location) { }
}
