package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlockPolicyListenerFeedbackTest {
    private static final String BYPASS_PERMISSION = "maceguard.block-policy.bypass";

    @Test void bypassSkipsEveryPlayerPolicyCancellationAndMessage() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        WarzoneModule warzone = mock(WarzoneModule.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, warzone);
        Player player = mock(Player.class);
        when(player.hasPermission(BYPASS_PERMISSION)).thenReturn(true);

        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getPlayer()).thenReturn(player);
        BlockBreakEvent breakEvent = mock(BlockBreakEvent.class);
        when(breakEvent.getPlayer()).thenReturn(player);
        PlayerBucketEmptyEvent empty = mock(PlayerBucketEmptyEvent.class);
        when(empty.getPlayer()).thenReturn(player);
        PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
        when(fill.getPlayer()).thenReturn(player);

        listener.onPlace(place);
        listener.onBreak(breakEvent);
        listener.onBucketEmpty(empty);
        listener.onBucketFill(fill);

        verify(place, never()).setCancelled(true);
        verify(breakEvent, never()).setCancelled(true);
        verify(empty, never()).setCancelled(true);
        verify(fill, never()).setCancelled(true);
        verifyNoInteractions(resolver, warzone);
    }

    @Test void deniedPlayerPoliciesCancelAndSendOneActionSpecificMessage() {
        BlockPolicyResolver resolver = mock(BlockPolicyResolver.class);
        WarzoneModule warzone = mock(WarzoneModule.class);
        BlockPolicyListener listener = new BlockPolicyListener(resolver, warzone);
        Player player = mock(Player.class);
        when(player.hasPermission(BYPASS_PERMISSION)).thenReturn(false);
        when(resolver.resolve(any(Location.class))).thenReturn(denied());

        Block placed = block(Material.STONE);
        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getPlayer()).thenReturn(player);
        when(place.getBlockPlaced()).thenReturn(placed);

        Block broken = block(Material.DIAMOND_BLOCK);
        BlockBreakEvent breakEvent = mock(BlockBreakEvent.class);
        when(breakEvent.getPlayer()).thenReturn(player);
        when(breakEvent.getBlock()).thenReturn(broken);

        Block emptyTarget = block(Material.AIR);
        Block emptyClicked = mock(Block.class);
        when(emptyClicked.getRelative(BlockFace.UP)).thenReturn(emptyTarget);
        PlayerBucketEmptyEvent empty = mock(PlayerBucketEmptyEvent.class);
        when(empty.getPlayer()).thenReturn(player);
        when(empty.getBlockClicked()).thenReturn(emptyClicked);
        when(empty.getBlockFace()).thenReturn(BlockFace.UP);
        when(empty.getBucket()).thenReturn(Material.WATER_BUCKET);

        Block fillSource = block(Material.WATER);
        PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
        when(fill.getPlayer()).thenReturn(player);
        when(fill.getBlockClicked()).thenReturn(fillSource);

        listener.onPlace(place);
        listener.onBreak(breakEvent);
        listener.onBucketEmpty(empty);
        listener.onBucketFill(fill);

        verify(place).setCancelled(true);
        verify(breakEvent).setCancelled(true);
        verify(empty).setCancelled(true);
        verify(fill).setCancelled(true);
        verify(warzone).sendBlockPlaceDenied(player, Material.STONE);
        verify(warzone).sendBlockBreakDenied(player, Material.DIAMOND_BLOCK);
        verify(warzone).sendBucketEmptyDenied(player, Material.WATER);
        verify(warzone).sendBucketFillDenied(player, Material.WATER);
    }

    private Block block(Material material) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getLocation()).thenReturn(mock(Location.class));
        return block;
    }

    private BlockPolicyResolver.Resolution denied() {
        return new BlockPolicyResolver.Resolution("scope", "missing", null, true,
                "region", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING);
    }
}
