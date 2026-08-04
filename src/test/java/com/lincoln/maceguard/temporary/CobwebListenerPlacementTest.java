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
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CobwebListenerPlacementTest {
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
                .track(any(Block.class), anyString(), anyLong(), any(Boolean.class));
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
                .track(any(Block.class), anyString(), anyLong(), any(Boolean.class));
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
        return new Harness(listener, warzone, temporary, config);
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
                           TemporaryBlockService temporary, MaceGuardConfig config) { }
}
