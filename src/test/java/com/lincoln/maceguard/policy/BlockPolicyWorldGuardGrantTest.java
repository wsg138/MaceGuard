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
        when(resolver.resolve(location)).thenReturn(resolved("warzone"));

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
        when(resolver.resolve(location)).thenReturn(resolved("warzone"));

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
        when(resolver.resolve(sourceLocation)).thenReturn(resolved("warzone"));
        when(resolver.resolve(targetLocation)).thenReturn(resolved("warzone"));

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
