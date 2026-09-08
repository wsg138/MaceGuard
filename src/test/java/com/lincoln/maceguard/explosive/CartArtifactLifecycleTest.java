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
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartArtifactLifecycleTest {
    @Test
    void disablingCartsRestoresTrackedRailAndRemovesTrackedMinecart() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        Server server = mock(Server.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        PluginRuntime pluginRuntime = mock(PluginRuntime.class);
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        World world = mock(World.class);
        Block rail = mock(Block.class);
        BlockState replaced = mock(BlockState.class);
        BlockData originalData = mock(BlockData.class);
        Entity cart = mock(Entity.class);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        UUID worldId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

        WarzoneConfig.ActiveSet carts = new WarzoneConfig.ActiveSet(
                List.of("carts"), "Carts", "Carts", Set.of(WarzoneConfig.Effect.CARTS), Map.of());
        WarzoneConfig.ActiveSet ordinary = new WarzoneConfig.ActiveSet(
                List.of("ordinary"), "Ordinary", "Ordinary", Set.of(), Map.of());
        AtomicReference<WarzoneConfig.ActiveSet> active = new AtomicReference<>(carts);

        when(plugin.isFeatureEnabled()).thenReturn(true);
        when(plugin.runtime()).thenReturn(pluginRuntime);
        when(plugin.getServer()).thenReturn(server);
        when(pluginRuntime.warzone()).thenReturn(module);
        when(module.runtime()).thenReturn(runtime);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.active()).thenAnswer(ignored -> active.get());
        when(runtime.appliesAt(location)).thenReturn(true);

        when(world.getUID()).thenReturn(worldId);
        when(rail.getType()).thenReturn(Material.RAIL);
        when(rail.getLocation()).thenReturn(location);
        when(rail.getWorld()).thenReturn(world);
        when(rail.getX()).thenReturn(4);
        when(rail.getY()).thenReturn(65);
        when(rail.getZ()).thenReturn(7);
        when(replaced.getBlockData()).thenReturn(originalData);
        when(originalData.getAsString(true)).thenReturn("minecraft:air");
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getBlockAt(4, 65, 7)).thenReturn(rail);
        when(server.createBlockData("minecraft:air")).thenReturn(originalData);

        BlockPlaceEvent railPlace = mock(BlockPlaceEvent.class);
        when(railPlace.getBlockPlaced()).thenReturn(rail);
        when(railPlace.getBlockReplacedState()).thenReturn(replaced);

        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(location);
        when(cart.getUniqueId()).thenReturn(cartId);
        when(server.getEntity(cartId)).thenReturn(cart);
        EntityPlaceEvent cartPlace = mock(EntityPlaceEvent.class);
        when(cartPlace.getEntityType()).thenReturn(EntityType.TNT_MINECART);
        when(cartPlace.getEntity()).thenReturn(cart);
        when(cartPlace.getPlayer()).thenReturn(player);

        ExplosiveControlListener listener = new ExplosiveControlListener(
                plugin, worldGuard, ignored -> false);
        listener.onCartRailPlace(railPlace);
        listener.onAcceptedCartPlace(cartPlace);

        active.set(ordinary);
        listener.reconcileCartArtifacts();

        verify(rail).setBlockData(originalData, false);
        verify(cart).remove();
    }
}
