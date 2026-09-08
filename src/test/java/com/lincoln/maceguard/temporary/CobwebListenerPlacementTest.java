package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CobwebListenerPlacementTest {
    private static final String POLICY_BYPASS_PERMISSION = "maceguard.block-policy.bypass";

    @Test
    void creativeAndSurvivalPlacementsUseTheSameGuaranteedTrackingPath() {
        Harness harness = harness(true, true);

        BlockPlaceEvent survival = event(GameMode.SURVIVAL, 1, Material.AIR);
        BlockPlaceEvent creative = event(GameMode.CREATIVE, 2, Material.AIR);
        harness.listener.onRestriction(survival);
        harness.listener.onPlace(survival);
        harness.listener.onRestriction(creative);
        harness.listener.onPlace(creative);

        verify(survival, never()).setCancelled(true);
        verify(creative, never()).setCancelled(true);
        verify(harness.temporary, org.mockito.Mockito.times(2))
                .track(any(Block.class), anyString(), anyLong(), eq(true));
        assertTrue(CobwebListener.replacementAllowed(harness.config, Material.AIR));
    }

    @Test
    void warzoneModifierPreAllowsOnlyWorldGuardRegionDeny() {
        Harness harness = harness(true, true);
        when(harness.worldGuard.buildAllowed(any(Location.class), any(Player.class)))
                .thenReturn(false);
        when(harness.worldGuard.cobwebsAllowed(any(Location.class), any(Player.class)))
                .thenReturn(false);
        when(harness.worldGuard.warzoneCobwebsAllowed(any(Location.class))).thenReturn(true);
        BlockPlaceEvent original = event(GameMode.SURVIVAL, 7, Material.AIR);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(delegate.getOriginalEvent()).thenReturn(original);

        harness.listener.onWorldGuardWarzoneCobweb(delegate);
        harness.listener.onRestriction(original);
        harness.listener.onPlace(original);

        verify(delegate).setAllowed(true);
        verify(original, never()).setCancelled(false);
        verify(original, never()).setCancelled(true);
        verify(harness.temporary).track(any(Block.class), anyString(), anyLong(), eq(true));
    }

    @Test
    void trackedWarzoneCobwebCanBeBrokenThroughWorldGuard() {
        Harness harness = harness(true, true);
        BlockPlaceEvent placement = event(GameMode.SURVIVAL, 9, Material.AIR);
        harness.listener.onRestriction(placement);
        harness.listener.onPlace(placement);

        Block cobweb = placement.getBlockPlaced();
        Player player = placement.getPlayer();
        when(harness.worldGuard.buildAllowed(cobweb.getLocation(), player)).thenReturn(false);
        BlockBreakEvent original = mock(BlockBreakEvent.class);
        when(original.getBlock()).thenReturn(cobweb);
        when(original.getPlayer()).thenReturn(player);
        com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent delegate =
                mock(com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent.class);
        when(delegate.getOriginalEvent()).thenReturn(original);

        harness.listener.onWorldGuardWarzoneCobwebBreak(delegate);

        verify(delegate).setAllowed(true);
    }

    @Test
    void trackedWarzoneCobwebAllowsWaterEscapeButNotGeneralWaterPlacement() {
        Harness harness = harness(true, true);
        BlockPlaceEvent placement = event(GameMode.SURVIVAL, 10, Material.AIR);
        harness.listener.onRestriction(placement);
        harness.listener.onPlace(placement);

        Block cobweb = placement.getBlockPlaced();
        Player player = placement.getPlayer();
        Location playerLocation = mock(Location.class);
        when(playerLocation.getBlock()).thenReturn(cobweb);
        when(player.getLocation()).thenReturn(playerLocation);

        Block clicked = mock(Block.class);
        Block target = mock(Block.class);
        Location targetLocation = mock(Location.class);
        when(clicked.getRelative(BlockFace.UP)).thenReturn(target);
        when(target.getLocation()).thenReturn(targetLocation);
        when(harness.warzone.appliesAt(targetLocation)).thenReturn(true);

        PlayerBucketEmptyEvent bucket = mock(PlayerBucketEmptyEvent.class);
        when(bucket.getBucket()).thenReturn(Material.WATER_BUCKET);
        when(bucket.getPlayer()).thenReturn(player);
        when(bucket.getBlockClicked()).thenReturn(clicked);
        when(bucket.getBlockFace()).thenReturn(BlockFace.UP);
        when(bucket.isCancelled()).thenReturn(false);

        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent escapeDelegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(escapeDelegate.getOriginalEvent()).thenReturn(bucket);
        harness.listener.onWorldGuardCobwebEscapePlace(escapeDelegate);
        verify(escapeDelegate).setAllowed(true);

        Location safeLocation = mock(Location.class);
        Block safeFeet = mock(Block.class);
        Block safeHead = mock(Block.class);
        when(safeFeet.getType()).thenReturn(Material.AIR);
        when(safeFeet.getRelative(BlockFace.UP)).thenReturn(safeHead);
        when(safeHead.getType()).thenReturn(Material.AIR);
        when(safeLocation.getBlock()).thenReturn(safeFeet);
        when(player.getLocation()).thenReturn(safeLocation);
        com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent ordinaryDelegate =
                mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        when(ordinaryDelegate.getOriginalEvent()).thenReturn(bucket);
        harness.listener.onWorldGuardCobwebEscapePlace(ordinaryDelegate);
        verify(ordinaryDelegate, never()).setAllowed(true);
    }

    @Test
    void unrelatedPreCancelledWarzoneCobwebIsNeverReopened() {
        Harness harness = harness(true, true);
        when(harness.worldGuard.buildAllowed(any(Location.class), any(Player.class)))
                .thenReturn(false);
        when(harness.worldGuard.warzoneCobwebsAllowed(any(Location.class))).thenReturn(true);
        BlockPlaceEvent event = event(GameMode.SURVIVAL, 8, Material.AIR);
        when(event.isCancelled()).thenReturn(true);

        harness.listener.onRestriction(event);

        verify(event, never()).setCancelled(false);
        verify(event, never()).setCancelled(true);
        verify(harness.temporary, never())
                .track(any(Block.class), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void rejectedTrackingCancelsAndRestoresTheOriginalBlock() {
        Harness harness = harness(false, true);
        BlockPlaceEvent event = event(GameMode.SURVIVAL, 3, Material.AIR);
        Block placed = event.getBlockPlaced();
        BlockData original = event.getBlockReplacedState().getBlockData();

        harness.listener.onRestriction(event);
        harness.listener.onPlace(event);

        verify(event).setCancelled(true);
        verify(placed).setBlockData(original, false);
        verify(harness.warzone, never()).successfulCobweb(any(Player.class),
                any(com.lincoln.maceguard.warzone.restriction.RestrictionDecision.class));
    }

    @Test
    void disallowedReplacementIsCancelledInsideManagedScope() {
        Harness harness = harness(true, true);
        BlockPlaceEvent event = event(GameMode.SURVIVAL, 4, Material.STONE);

        harness.listener.onRestriction(event);

        verify(event).setCancelled(true);
        verify(harness.temporary, never())
                .track(any(Block.class), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void replacementConfigurationDoesNotAffectCobwebsOutsideManagedScope() {
        Harness harness = harness(true, false);
        BlockPlaceEvent event = event(GameMode.SURVIVAL, 5, Material.STONE);
        Block placed = event.getBlockPlaced();

        harness.listener.onRestriction(event);
        harness.listener.onPlace(event);

        verify(event, never()).setCancelled(true);
        verify(placed, never()).setBlockData(any(BlockData.class), eq(false));
        verify(harness.temporary, never())
                .track(any(Block.class), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void dedicatedPolicyBypassCobwebIsSilentAndNotCancelled() {
        Harness harness = harness(true, true);
        BlockPlaceEvent event = event(GameMode.SURVIVAL, 6, Material.AIR);
        when(event.getPlayer().hasPermission(POLICY_BYPASS_PERMISSION)).thenReturn(true);
        when(harness.policies.resolve(any(Location.class))).thenReturn(
                new BlockPolicyResolver.Resolution("scope", "missing", null, true,
                        "region", false,
                        BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING));

        harness.listener.onRestriction(event);

        verify(event, never()).setCancelled(true);
        verify(harness.warzone, never()).sendBlockPlaceDenied(any(Player.class),
                any(Material.class));
        verify(harness.warzone, never()).sendCobwebDenial(any(Player.class),
                any(WarzoneRuntime.CobwebDecision.class));
    }

    private Harness harness(boolean trackResult, boolean warzoneApplies) {
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        WarzoneModule warzone = mock(WarzoneModule.class);
        TemporaryBlockService temporary = mock(TemporaryBlockService.class);
        BlockPolicyResolver policies = mock(BlockPolicyResolver.class);
        MaceGuardConfig config = new MaceGuardConfig(true, true, false, 0,
                new MaceGuardConfig.TemporarySettings(60, Set.of("AIR"), 10_000),
                new MaceGuardConfig.PerformanceSettings(1, 1, 1), Map.of(), Map.of(),
                mock(EndIslandSettings.class), Set.of());
        CobwebListener listener = new CobwebListener(worldGuard, warzone, temporary,
                config, policies);

        when(policies.resolve(any(Location.class))).thenReturn(
                BlockPolicyResolver.Resolution.none(
                        BlockPolicyResolver.Status.NO_EFFECTIVE_VALUE));
        when(warzone.appliesAt(any(Location.class))).thenReturn(warzoneApplies);
        when(warzone.cobwebDecision(any(Player.class), any(Location.class)))
                .thenReturn(WarzoneRuntime.CobwebDecision.permit());
        when(warzone.cobwebLifetime(any(Duration.class), any(Location.class)))
                .thenReturn(Duration.ofSeconds(60));
        when(worldGuard.buildAllowed(any(Location.class), any(Player.class))).thenReturn(true);
        when(worldGuard.cobwebsAllowed(any(Location.class), any(Player.class))).thenReturn(true);
        when(worldGuard.warzoneCobwebsAllowed(any(Location.class))).thenReturn(true);
        when(temporary.track(any(Block.class), anyString(), anyLong(), eq(true)))
                .thenReturn(trackResult);
        return new Harness(listener, warzone, temporary, config, policies, worldGuard);
    }

    private BlockPlaceEvent event(GameMode mode, int x, Material originalMaterial) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Location location = new Location(world, x, 64, 0);
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(mode);

        BlockData cobwebData = data(Material.COBWEB, "minecraft:cobweb");
        Block placed = mock(Block.class);
        when(placed.getType()).thenReturn(Material.COBWEB);
        when(placed.getLocation()).thenReturn(location);
        when(placed.getWorld()).thenReturn(world);
        when(placed.getX()).thenReturn(x);
        when(placed.getY()).thenReturn(64);
        when(placed.getZ()).thenReturn(0);
        when(placed.getBlockData()).thenReturn(cobwebData);

        BlockData originalData = data(originalMaterial,
                "minecraft:" + originalMaterial.name().toLowerCase(java.util.Locale.ROOT));
        BlockState replaced = mock(BlockState.class);
        when(replaced.getType()).thenReturn(originalMaterial);
        when(replaced.getBlockData()).thenReturn(originalData);

        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockPlaced()).thenReturn(placed);
        when(event.getBlockReplacedState()).thenReturn(replaced);
        return event;
    }

    private BlockData data(Material material, String serialized) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAsString(true)).thenReturn(serialized);
        return data;
    }

    private record Harness(CobwebListener listener, WarzoneModule warzone,
                           TemporaryBlockService temporary, MaceGuardConfig config,
                           BlockPolicyResolver policies, WorldGuardQueryService worldGuard) { }
}
