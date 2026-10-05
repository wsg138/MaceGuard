package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlockPolicyWorldGuardGrantTest {
    private static final String WARZONE_SCOPE = "warzone";

    @Test void flowingWaterCannotDestroyAnyNonCobwebDecoration() {
        for (Material material : List.of(Material.SHORT_GRASS, Material.DANDELION,
                Material.TORCH, Material.REDSTONE_WIRE, Material.RAIL, Material.WHEAT,
                Material.SNOW, Material.VINE, Material.FIRE, Material.LAVA)) {
            var flow = new CobwebFlow(true, true, material);
            flow.listener.onWorldGuardPolicyPlace(flow.delegate);
            verify(flow.delegate, never()).setAllowed(true);
            flow.listener.onFlow(flow.event);
            verify(flow.event).setCancelled(true);
        }
    }

    @Test void disabledCobwebsStillProtectMapBlocksFromWater() {
        var flow = new CobwebFlow(false, true, Material.TORCH);
        flow.listener.onFlow(flow.event);
        verify(flow.event).setCancelled(true);
    }

    @Test void explicitFluidPolicyCannotOverrideProtectedDecoration() {
        var flow = new CobwebFlow(true, true, Material.REDSTONE_WIRE);
        when(flow.resolver.resolve(flow.source.getLocation())).thenReturn(resolved(WARZONE_SCOPE));
        when(flow.resolver.resolve(flow.target.getLocation())).thenReturn(resolved(WARZONE_SCOPE));
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
        flow.listener.onFlow(flow.event);
        verify(flow.event).setCancelled(true);
    }

    @Test void waterContactCannotTransformLavaOrConcreteInWarzone() {
        for (Material[] change : List.of(new Material[]{Material.LAVA, Material.OBSIDIAN},
                new Material[]{Material.LAVA, Material.COBBLESTONE},
                new Material[]{Material.WATER, Material.STONE},
                new Material[]{Material.WHITE_CONCRETE_POWDER, Material.WHITE_CONCRETE})) {
            var flow = new CobwebFlow(true, true, change[0]);
            var state = mock(org.bukkit.block.BlockState.class);
            when(state.getType()).thenReturn(change[1]);
            var form = mock(org.bukkit.event.block.BlockFormEvent.class);
            when(form.getBlock()).thenReturn(flow.target);
            when(form.getNewState()).thenReturn(state);
            flow.listener.onWaterBlockForm(form);
            verify(form).setCancelled(true);
        }
    }

    @Test void unrelatedFormationAndOutsideWarzoneRemainUnchanged() {
        var inside = new CobwebFlow(true, true, Material.AIR);
        var state = mock(org.bukkit.block.BlockState.class);
        when(state.getType()).thenReturn(Material.SNOW);
        var form = mock(org.bukkit.event.block.BlockFormEvent.class);
        when(form.getBlock()).thenReturn(inside.target);
        when(form.getNewState()).thenReturn(state);
        inside.listener.onWaterBlockForm(form);
        verify(form, never()).setCancelled(true);

        var outside = new CobwebFlow(true, false, Material.LAVA);
        when(form.getBlock()).thenReturn(outside.target);
        when(state.getType()).thenReturn(Material.OBSIDIAN);
        outside.listener.onWaterBlockForm(form);
        verify(form, never()).setCancelled(true);
    }

    @Test void bucketCannotDirectlyReplaceDecorationEvenWithPolicyBypass() {
        var flow = new CobwebFlow(true, true, Material.DANDELION);
        PlayerBucketEmptyEvent bucket = mock(PlayerBucketEmptyEvent.class);
        Player player = mock(Player.class);
        when(player.hasPermission("maceguard.block-policy.bypass")).thenReturn(true);
        when(bucket.getPlayer()).thenReturn(player);
        when(bucket.getBucket()).thenReturn(Material.WATER_BUCKET);
        when(bucket.getBlock()).thenReturn(flow.target);
        when(flow.delegate.getOriginalEvent()).thenReturn(bucket);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
        flow.listener.onBucketEmpty(bucket);
        verify(bucket).setCancelled(true);
    }

    @Test void waterStillFlowsIntoEmptySpaceWaterAndCobwebs() {
        for (Material material : List.of(Material.AIR, Material.CAVE_AIR,
                Material.VOID_AIR, Material.WATER, Material.COBWEB)) {
            var flow = new CobwebFlow(true, true, material);
            flow.listener.onWorldGuardPolicyPlace(flow.delegate);
            verify(flow.delegate).setAllowed(true);
            flow.listener.onFlow(flow.event);
            verify(flow.event, never()).setCancelled(true);
        }
    }

    @Test void cobwebWaterWithoutNamedPolicyGainsFlowGrant() {
        var flow = new CobwebFlow(true, true, Material.COBWEB);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate).setAllowed(true);
        flow.listener.onFlow(flow.event);
        verify(flow.event, never()).setCancelled(true);
    }

    @Test void cobwebWaterCanSpreadThroughAirBeforeReachingWeb() {
        var flow = new CobwebFlow(true, true, Material.AIR);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate).setAllowed(true);
    }

    @Test void inactiveCobwebModifierDoesNotGrantWaterFlow() {
        var flow = new CobwebFlow(false, true, Material.COBWEB);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
    }

    @Test void waterCannotFlowOutOfWarzoneOrIntoNestedSafeZone() {
        var flow = new CobwebFlow(true, false, Material.AIR);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
        flow.listener.onFlow(flow.event);
        verify(flow.event).setCancelled(true);
    }

    @Test void cobwebWaterPreservesOtherPluginCancellation() {
        var flow = new CobwebFlow(true, true, Material.COBWEB);
        when(flow.event.isCancelled()).thenReturn(true);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
    }

    @Test void lavaDoesNotGainCobwebWaterGrant() {
        var flow = new CobwebFlow(true, true, Material.COBWEB);
        when(flow.source.getType()).thenReturn(Material.LAVA);
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
    }

    @Test void missingNamedPolicyStillDeniesCobwebWater() {
        var flow = new CobwebFlow(true, true, Material.COBWEB);
        when(flow.resolver.resolve(flow.target.getLocation())).thenReturn(
                new BlockPolicyResolver.Resolution(WARZONE_SCOPE, "missing", null, true,
                        "direct", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING));
        flow.listener.onWorldGuardPolicyPlace(flow.delegate);
        verify(flow.delegate, never()).setAllowed(true);
        flow.listener.onFlow(flow.event);
        verify(flow.event).setCancelled(true);
    }

    private final class CobwebFlow {
        final BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        final Block source = block(Material.WATER, mock(Location.class));
        final Block target;
        final BlockFromToEvent event = mock(BlockFromToEvent.class);
        final com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        final BlockPolicyListener listener;

        CobwebFlow(boolean activeCobwebs, boolean targetInWarzone, Material targetType) {
            target = block(targetType, mock(Location.class));
            Block neighbor = block(Material.AIR, mock(Location.class));
            when(target.getRelative(any(BlockFace.class))).thenReturn(neighbor);
            WarzoneModule warzone = mock(WarzoneModule.class);
            WarzoneRuntime runtime = mock(WarzoneRuntime.class);
            RotationManager rotations = mock(RotationManager.class);
            when(warzone.runtime()).thenReturn(runtime);
            when(runtime.rotations()).thenReturn(rotations);
            when(rotations.active()).thenReturn(new WarzoneConfig.ActiveSet(
                    List.of(), "Test", "Test", activeCobwebs
                    ? Set.of(WarzoneConfig.Effect.COBWEBS) : Set.of(), Map.of()));
            when(warzone.appliesAt(source.getLocation())).thenReturn(true);
            when(warzone.appliesAt(target.getLocation())).thenReturn(targetInWarzone);
            when(resolver.resolve(any(Location.class))).thenReturn(
                    BlockPolicyResolver.Resolution.none(BlockPolicyResolver.Status.NO_EFFECTIVE_VALUE));
            when(event.getBlock()).thenReturn(source);
            when(event.getToBlock()).thenReturn(target);
            when(delegate.getOriginalEvent()).thenReturn(event);
            listener = new BlockPolicyListener(resolver, warzone, (location, player) -> false);
        }
    }

    private final BlockPolicy policy = new BlockPolicy(
            "warzone-fluids",
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB)),
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB)),
            new BlockPolicy.BucketRule(Set.of(Material.WATER, Material.LAVA),
                    Set.of(Material.WATER, Material.LAVA)),
            new BlockPolicy.LiquidRule(true, true),
            false);

    @Test void allowedBucketEmptyPreAllowsWorldGuardPlaceDelegate() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, null, (location, player) -> false);
        Location location = mock(Location.class);
        Block target = block(Material.AIR, location);
        PlayerBucketEmptyEvent original = mock(PlayerBucketEmptyEvent.class);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(original.getBlock()).thenReturn(target);
        when(original.getBucket()).thenReturn(Material.LAVA_BUCKET);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(resolver.resolve(location)).thenReturn(resolved(WARZONE_SCOPE));

        listener.onWorldGuardPolicyPlace(delegate);

        verify(delegate).setAllowed(true);
    }

    @Test void allowedBucketFillPreAllowsWorldGuardBreakDelegate() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, null, (location, player) -> false);
        Location location = mock(Location.class);
        Block source = block(Material.WATER, location);
        PlayerBucketFillEvent original = mock(PlayerBucketFillEvent.class);
        com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent.class);
        when(original.getBlock()).thenReturn(source);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(resolver.resolve(location)).thenReturn(resolved(WARZONE_SCOPE));

        listener.onWorldGuardPolicyBreak(delegate);

        verify(delegate).setAllowed(true);
    }

    @Test void allowedLiquidFlowInsideExactPolicyScopePreAllowsWorldGuardDelegate() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, null, (location, player) -> false);
        Location sourceLocation = mock(Location.class);
        Location targetLocation = mock(Location.class);
        Block source = block(Material.WATER, sourceLocation);
        Block target = block(Material.AIR, targetLocation);
        Block adjacent = block(Material.AIR, mock(Location.class));
        when(target.getRelative(any(BlockFace.class))).thenReturn(adjacent);
        BlockFromToEvent original = mock(BlockFromToEvent.class);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(original.getBlock()).thenReturn(source);
        when(original.getToBlock()).thenReturn(target);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(resolver.resolve(sourceLocation)).thenReturn(resolved(WARZONE_SCOPE));
        when(resolver.resolve(targetLocation)).thenReturn(resolved(WARZONE_SCOPE));

        listener.onWorldGuardPolicyPlace(delegate);

        verify(delegate).setAllowed(true);
    }

    @Test void confinedLiquidFlowAcrossPolicyScopeDoesNotGainWorldGuardGrant() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, null, (location, player) -> false);
        Location sourceLocation = mock(Location.class);
        Location targetLocation = mock(Location.class);
        Block source = block(Material.WATER, sourceLocation);
        Block target = block(Material.AIR, targetLocation);
        Block adjacent = block(Material.AIR, mock(Location.class));
        when(target.getRelative(any(BlockFace.class))).thenReturn(adjacent);
        BlockFromToEvent original = mock(BlockFromToEvent.class);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(original.getBlock()).thenReturn(source);
        when(original.getToBlock()).thenReturn(target);
        when(original.isCancelled()).thenReturn(false);
        when(delegate.getOriginalEvent()).thenReturn(original);
        when(resolver.resolve(sourceLocation)).thenReturn(resolved("warzone-a"));
        when(resolver.resolve(targetLocation)).thenReturn(resolved("warzone-b"));

        listener.onWorldGuardPolicyPlace(delegate);

        verify(delegate, never()).setAllowed(true);
    }

    @Test void cartsFlintIgnitionReopensOnlyWorldGuardGlobalLighterCancellation() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        WarzoneModule warzone = activeCartsWarzone();
        BlockPolicyListener listener = new BlockPolicyListener(resolver, warzone,
                (location, player) -> true);
        Location location = mock(Location.class);
        Block block = block(Material.AIR, location);
        Player player = mock(Player.class);
        BlockIgniteEvent event = mock(BlockIgniteEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        when(event.getCause()).thenReturn(BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL);
        when(event.isCancelled()).thenReturn(false, true);

        listener.onCartIgniteBeforeWorldGuard(event);
        listener.onCartIgniteWorldGuardBypass(event);

        verify(event).setCancelled(false);
        verify(event, never()).setCancelled(true);
    }

    @Test void preCancelledCartIgnitionIsNeverReopened() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        WarzoneModule warzone = activeCartsWarzone();
        BlockPolicyListener listener = new BlockPolicyListener(resolver, warzone,
                (location, player) -> true);
        Location location = mock(Location.class);
        Block block = block(Material.AIR, location);
        Player player = mock(Player.class);
        BlockIgniteEvent event = mock(BlockIgniteEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        when(event.getCause()).thenReturn(BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL);
        when(event.isCancelled()).thenReturn(true);

        listener.onCartIgniteBeforeWorldGuard(event);
        listener.onCartIgniteWorldGuardBypass(event);

        verify(event, never()).setCancelled(false);
    }

    private WarzoneModule activeCartsWarzone() {
        WarzoneModule warzone = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                List.of("carts"), "Carts", "Carts",
                Set.of(WarzoneConfig.Effect.CARTS), Map.of());
        when(warzone.runtime()).thenReturn(runtime);
        when(runtime.appliesAt(any(Location.class))).thenReturn(true);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.active()).thenReturn(active);
        return warzone;
    }

    private Block block(Material material, Location location) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getLocation()).thenReturn(location);
        return block;
    }

    private BlockPolicyResolver.Resolution resolved(String scope) {
        return new BlockPolicyResolver.Resolution(scope, policy.name(), policy, true,
                "direct", false, BlockPolicyResolver.Status.ACTIVE);
    }
}
