package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.mockito.Mockito.*;

class WarzoneWaterBucketTest {
    @Test void initialRightClickPlacementGrantPrecedesBucketEvent() {
        Fixture f = fixture();
        var interaction = mock(org.bukkit.event.player.PlayerInteractEvent.class);
        var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(Material.WATER_BUCKET);
        when(interaction.getItem()).thenReturn(item);
        when(interaction.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK);
        when(interaction.getPlayer()).thenReturn(f.player);
        when(interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
        when(interaction.getClickedBlock()).thenReturn(f.target);
        when(f.place.getOriginalEvent()).thenReturn(interaction);
        when(f.place.getEffectiveMaterial()).thenReturn(Material.WATER);
        when(f.place.getBlocks()).thenReturn(List.of(f.target));
        f.listener.onWorldGuardCobwebEscapePlace(f.place);
        verify(f.place).setAllowed(true);
        for (Material protectedMaterial : List.of(Material.TORCH, Material.RAIL, Material.STONE,
                Material.OAK_SLAB, Material.LAVA)) {
            clearInvocations(f.place);
            when(f.target.getType()).thenReturn(protectedMaterial);
            f.listener.onWorldGuardCobwebEscapePlace(f.place);
            verify(f.place, never()).setAllowed(true);
        }
        when(f.target.getType()).thenReturn(Material.AIR);
        when(interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY);
        f.listener.onWorldGuardCobwebEscapePlace(f.place);
        verify(f.place, never()).setAllowed(true);
        when(interaction.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DEFAULT);
        when(f.warzone.appliesAt(f.target.getLocation())).thenReturn(false);
        f.listener.onWorldGuardCobwebEscapePlace(f.place);
        verify(f.place, never()).setAllowed(true);
        when(f.warzone.appliesAt(f.target.getLocation())).thenReturn(true);
        when(f.warzone.runtime().rotations().active()).thenReturn(active(false));
        f.listener.onWorldGuardCobwebEscapePlace(f.place);
        verify(f.place, never()).setAllowed(true);
        when(f.warzone.runtime().rotations().active()).thenReturn(active(true));
        when(f.policies.resolve(f.target.getLocation())).thenReturn(
                new BlockPolicyResolver.Resolution("scope", "missing", null, true,
                        "region", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING));
        f.listener.onWorldGuardCobwebEscapePlace(f.place);
        verify(f.place, never()).setAllowed(true);
    }
    @Test void bucketPlacementDoesNotGrantDestructionOfMapBlocks() {
        for (Material material : List.of(Material.SHORT_GRASS, Material.TORCH,
                Material.REDSTONE_WIRE, Material.RAIL, Material.WHEAT, Material.LAVA)) {
            Fixture f = fixture();
            when(f.target.getType()).thenReturn(material);
            f.assertDenied();
        }
    }
    @Test void ordinaryWaterPlacementAllowsBothWorldGuardDelegates() {
        Fixture f = fixture();
        f.performPlacement();
        verify(f.place).setAllowed(true);
        verify(f.use).setAllowed(true);
        verifyNoInteractions(f.temporary);
    }
    @Test void disabledCobwebsDoNotGrantPlacement() {
        Fixture f = fixture();
        when(f.warzone.runtime().rotations().active()).thenReturn(active(false));
        f.assertDenied();
    }
    @Test void safeZoneDestinationDoesNotGrantPlacement() {
        Fixture f = fixture();
        when(f.warzone.appliesAt(f.target.getLocation())).thenReturn(false);
        f.assertDenied();
    }
    @Test void playerOutsideWarzoneCannotPlaceAcrossBoundary() {
        Fixture f = fixture();
        when(f.warzone.appliesAt(f.player.getLocation())).thenReturn(false);
        f.assertDenied();
    }
    @Test void lavaNeverGetsWaterException() {
        Fixture f = fixture();
        when(f.bucket.getBucket()).thenReturn(Material.LAVA_BUCKET);
        f.assertDenied();
    }
    @Test void otherPluginCancellationIsNotReopened() {
        Fixture f = fixture();
        when(f.bucket.isCancelled()).thenReturn(true);
        f.assertDenied();
    }
    @Test void explicitMissingPolicyRemainsFailClosed() {
        Fixture f = fixture();
        when(f.policies.resolve(f.target.getLocation())).thenReturn(
                new BlockPolicyResolver.Resolution("scope", "missing", null, true,
                        "region", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING));
        f.assertDenied();
    }
    @Test void waterPickupAllowsBreakAndUseDelegates() {
        Fixture f = fixture();
        when(f.target.getType()).thenReturn(Material.WATER);
        PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
        when(fill.getBlock()).thenReturn(f.target);
        when(fill.getPlayer()).thenReturn(f.player);
        var breaking = mock(com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent.class);
        var use = mock(com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent.class);
        when(breaking.getOriginalEvent()).thenReturn(fill);
        when(use.getOriginalEvent()).thenReturn(fill);
        f.listener.onWorldGuardWarzoneWaterPickup(breaking);
        f.listener.onWorldGuardCobwebEscapeItem(use);
        verify(breaking).setAllowed(true);
        verify(use).setAllowed(true);
        when(fill.isCancelled()).thenReturn(true);
        clearInvocations(breaking, use);
        f.listener.onWorldGuardWarzoneWaterPickup(breaking);
        f.listener.onWorldGuardCobwebEscapeItem(use);
        verify(breaking, never()).setAllowed(true);
        verify(use, never()).setAllowed(true);
    }
    private WarzoneConfig.ActiveSet active(boolean enabled) {
        return new WarzoneConfig.ActiveSet(List.of(), "", "", enabled
                ? Set.of(WarzoneConfig.Effect.COBWEBS) : Set.of(), Map.of());
    }
    private Fixture fixture() {
        World world = mock(World.class);
        Location actor = new Location(world, 10, 64, 10);
        Location destination = new Location(world, 12, 64, 10);
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(actor);
        Block clicked = mock(Block.class), target = mock(Block.class);
        when(clicked.getRelative(BlockFace.UP)).thenReturn(target);
        when(target.getLocation()).thenReturn(destination);
        when(target.getType()).thenReturn(Material.AIR);
        BlockPolicyResolver policies = mock(BlockPolicyResolver.class);
        when(policies.resolve(destination)).thenReturn(BlockPolicyResolver.Resolution.none(
                BlockPolicyResolver.Status.NO_EFFECTIVE_VALUE));
        WarzoneModule warzone = mock(WarzoneModule.class, RETURNS_DEEP_STUBS);
        when(warzone.appliesAt(actor)).thenReturn(true);
        when(warzone.appliesAt(destination)).thenReturn(true);
        when(warzone.runtime().rotations().active()).thenReturn(active(true));
        TemporaryBlockService temporary = mock(TemporaryBlockService.class);
        MaceGuardConfig config = new MaceGuardConfig(true, true, false, 0,
                new MaceGuardConfig.TemporarySettings(60, Set.of("AIR"), 10_000),
                new MaceGuardConfig.PerformanceSettings(1, 1, 1), Map.of(), Map.of(),
                mock(EndIslandSettings.class), Set.of());
        CobwebListener listener = new CobwebListener(mock(WorldGuardQueryService.class),
                warzone, temporary, config, policies);
        PlayerBucketEmptyEvent bucket = mock(PlayerBucketEmptyEvent.class);
        when(bucket.getBucket()).thenReturn(Material.WATER_BUCKET);
        when(bucket.getPlayer()).thenReturn(player);
        when(bucket.getBlockClicked()).thenReturn(clicked);
        when(bucket.getBlock()).thenReturn(target);
        when(bucket.getBlockFace()).thenReturn(BlockFace.UP);
        var place = mock(com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent.class);
        var use = mock(com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent.class);
        when(place.getOriginalEvent()).thenReturn(bucket);
        when(use.getOriginalEvent()).thenReturn(bucket);
        return new Fixture(listener, warzone, policies, temporary, player, target, bucket, place, use);
    }
    private record Fixture(CobwebListener listener, WarzoneModule warzone,
            BlockPolicyResolver policies, TemporaryBlockService temporary, Player player,
            Block target, PlayerBucketEmptyEvent bucket,
            com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent place,
            com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent use) {
        void performPlacement() {
            listener.onWorldGuardCobwebEscapePlace(place);
            listener.onWorldGuardCobwebEscapeItem(use);
        }
        void assertDenied() {
            performPlacement();
            verify(place, never()).setAllowed(true);
            verify(use, never()).setAllowed(true);
        }
    }
}
