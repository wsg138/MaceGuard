package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CobwebBypassLifecycleTest {
    private static final String POLICY_BYPASS = "maceguard.block-policy.bypass";
    private static final String TEMPORARY_BYPASS = "maceguard.temporary-cobweb.bypass";

    @Test void policyBypassPlacementIsStillTrackedAndTemporary() {
        Harness harness = harness();
        BlockPlaceEvent event = event();
        when(event.getPlayer().hasPermission(POLICY_BYPASS)).thenReturn(true);
        when(harness.policies.resolve(any(Location.class))).thenReturn(missingPolicy());
        when(harness.temporary.track(any(Block.class), anyString(), anyLong(), eq(false)))
                .thenReturn(true);

        harness.listener.onRestriction(event);
        harness.listener.onPlace(event);

        verify(event, never()).setCancelled(true);
        verify(harness.temporary).track(eq(event.getBlockPlaced()), eq("minecraft:air"),
                anyLong(), eq(false));
        verify(harness.warzone, never()).successfulCobweb(any(), any());
    }

    @Test void temporaryCobwebBypassCannotBypassBlockPolicy() {
        Harness harness = harness();
        BlockPlaceEvent event = event();
        when(event.getPlayer().hasPermission(TEMPORARY_BYPASS)).thenReturn(true);
        when(harness.policies.resolve(any(Location.class))).thenReturn(missingPolicy());

        harness.listener.onRestriction(event);

        verify(event).setCancelled(true);
        verify(harness.warzone).sendBlockPlaceDenied(event.getPlayer(), Material.COBWEB);
        verify(harness.temporary, never()).track(any(), anyString(), anyLong(), anyBoolean());
    }

    @Test void temporaryCobwebBypassStillTracksWarzonePlacementWithoutStartingCooldown() {
        Harness harness = harness();
        BlockPlaceEvent event = event();
        when(event.getPlayer().hasPermission(TEMPORARY_BYPASS)).thenReturn(true);
        when(harness.warzone.cobwebDecision(any(), any()))
                .thenReturn(WarzoneRuntime.CobwebDecision.unavailable());
        when(harness.temporary.track(any(Block.class), anyString(), anyLong(), eq(true)))
                .thenReturn(true);

        harness.listener.onRestriction(event);
        harness.listener.onPlace(event);

        verify(event, never()).setCancelled(true);
        verify(harness.temporary).track(eq(event.getBlockPlaced()), eq("minecraft:air"),
                anyLong(), eq(true));
        verify(harness.warzone, never()).successfulCobweb(any(), any());
    }

    private Harness harness() {
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
        when(warzone.appliesAt(any(Location.class))).thenReturn(true);
        when(warzone.cobwebDecision(any(Player.class), any(Location.class)))
                .thenReturn(WarzoneRuntime.CobwebDecision.permit());
        when(warzone.cobwebLifetime(any(Duration.class), any(Location.class)))
                .thenReturn(Duration.ofSeconds(60));
        when(worldGuard.buildAllowed(any(Location.class), any(Player.class))).thenReturn(true);
        when(worldGuard.cobwebsAllowed(any(Location.class), any(Player.class))).thenReturn(true);
        when(worldGuard.warzoneCobwebsAllowed(any(Location.class))).thenReturn(true);
        return new Harness(listener, warzone, temporary, policies);
    }

    private BlockPlaceEvent event() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Location location = new Location(world, 1, 64, 0);
        Player player = mock(Player.class);
        BlockData cobweb = data(Material.COBWEB, "minecraft:cobweb");
        Block placed = mock(Block.class);
        when(placed.getType()).thenReturn(Material.COBWEB);
        when(placed.getLocation()).thenReturn(location);
        when(placed.getWorld()).thenReturn(world);
        when(placed.getX()).thenReturn(1);
        when(placed.getY()).thenReturn(64);
        when(placed.getZ()).thenReturn(0);
        when(placed.getBlockData()).thenReturn(cobweb);
        BlockData original = data(Material.AIR, "minecraft:air");
        BlockState replaced = mock(BlockState.class);
        when(replaced.getType()).thenReturn(Material.AIR);
        when(replaced.getBlockData()).thenReturn(original);
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

    private BlockPolicyResolver.Resolution missingPolicy() {
        return new BlockPolicyResolver.Resolution("scope", "missing", null, true,
                "region", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING);
    }

    private record Harness(CobwebListener listener, WarzoneModule warzone,
                           TemporaryBlockService temporary, BlockPolicyResolver policies) { }
}
