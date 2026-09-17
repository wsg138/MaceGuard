package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockExplodeEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WindBurstBlockExplosionTest {
    @Test
    void triggerBlockExplosionKeepsLifecycleAliveWhenOriginIsDenied() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard, entity -> false);
        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        Block source = mock(Block.class);
        Location sourceLocation = mock(Location.class);
        List<Block> affected = new ArrayList<>();
        affected.add(mock(Block.class));

        when(plugin.isFeatureEnabled()).thenReturn(true);
        when(event.getExplosionResult()).thenReturn(ExplosionResult.TRIGGER_BLOCK);
        when(event.getBlock()).thenReturn(source);
        when(source.getLocation()).thenReturn(sourceLocation);
        when(event.blockList()).thenReturn(affected);
        when(worldGuard.explosivesDenied(sourceLocation, null)).thenReturn(true);

        listener.onTriggerBlockExplosionPrepare(event);
        listener.onBlockExplosion(event);

        assertTrue(affected.isEmpty());
        verify(event, never()).setCancelled(true);
    }

    @Test
    void destructiveBlockExplosionStillCancelsAtDeniedOrigin() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard, entity -> false);
        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        Block source = mock(Block.class);
        Location sourceLocation = mock(Location.class);

        when(plugin.isFeatureEnabled()).thenReturn(true);
        when(event.getExplosionResult()).thenReturn(ExplosionResult.DESTROY);
        when(event.getBlock()).thenReturn(source);
        when(source.getLocation()).thenReturn(sourceLocation);
        when(worldGuard.explosivesDenied(sourceLocation, null)).thenReturn(true);

        listener.onBlockExplosion(event);

        verify(event).setCancelled(true);
    }

    @Test
    void triggerBlockExplosionFiltersOnlyDeniedTargetBlocks() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard, entity -> false);
        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        Block source = mock(Block.class);
        Block allowed = mock(Block.class);
        Block denied = mock(Block.class);
        Location sourceLocation = mock(Location.class);
        Location allowedLocation = mock(Location.class);
        Location deniedLocation = mock(Location.class);
        List<Block> affected = new ArrayList<>(List.of(allowed, denied));

        when(plugin.isFeatureEnabled()).thenReturn(true);
        when(event.getExplosionResult()).thenReturn(ExplosionResult.TRIGGER_BLOCK);
        when(event.getBlock()).thenReturn(source);
        when(source.getLocation()).thenReturn(sourceLocation);
        when(allowed.getLocation()).thenReturn(allowedLocation);
        when(denied.getLocation()).thenReturn(deniedLocation);
        when(event.blockList()).thenReturn(affected);
        when(worldGuard.explosivesDenied(sourceLocation, null)).thenReturn(false);
        when(worldGuard.explosivesDenied(allowedLocation, null)).thenReturn(false);
        when(worldGuard.explosivesDenied(deniedLocation, null)).thenReturn(true);

        listener.onTriggerBlockExplosionPrepare(event);
        listener.onBlockExplosion(event);

        assertEquals(List.of(allowed), affected);
        verify(event, never()).setCancelled(true);
    }
}
