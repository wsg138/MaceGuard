package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.temporary.TemporaryBlock;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartArtifactLifecycleTest {
    @Test
    @SuppressWarnings("unchecked")
    void cartsEndRestoresOnlyOwnedRailsAndRemovesTaggedLoadedCarts() {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        Server server = mock(Server.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        TemporaryBlockService temporary = mock(TemporaryBlockService.class);
        CartArtifactGenerationStore generations = mock(CartArtifactGenerationStore.class);
        World world = mock(World.class);
        Entity cart = mock(Entity.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        NamespacedKey key = new NamespacedKey("maceguard", "test-cart-generation");

        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of(world));
        // Activation clears inherited tags; the second scan is the actual CARTS-end cleanup.
        when(world.getEntities()).thenReturn(List.of(), List.of(cart));
        when(module.runtime()).thenReturn(runtime);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.active()).thenReturn(carts());
        when(generations.advance()).thenReturn(true);
        when(generations.healthy()).thenReturn(true);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(key, PersistentDataType.LONG)).thenReturn(41L);

        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard,
                ignored -> false, module, temporary, null, generations, key);
        listener.activateLifecycle();
        listener.onCartsEnded();

        verify(generations, times(2)).advance();
        verify(module).bindCartCleanup(any(Runnable.class));
        verify(cart).remove();

        ArgumentCaptor<Predicate<TemporaryBlock>> selected =
                (ArgumentCaptor<Predicate<TemporaryBlock>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Predicate.class);
        verify(temporary).clearMatching(selected.capture());
        Predicate<TemporaryBlock> predicate = selected.getValue();
        TemporaryBlock ownedRail = block(true, TemporaryBlock.Kind.CART_RAIL);
        TemporaryBlock nonOwnedRail = block(false, TemporaryBlock.Kind.CART_RAIL);
        TemporaryBlock ownedCobweb = block(true, TemporaryBlock.Kind.COBWEB);
        assertTrue(predicate.test(ownedRail));
        assertFalse(predicate.test(nonOwnedRail));
        assertFalse(predicate.test(ownedCobweb));
    }

    @Test
    void generationFenceFailureRejectsNewPlayerCart() {
        LifecycleHarness harness = lifecycleHarness(false, 77L);
        Entity cart = mock(Entity.class);
        Player player = mock(Player.class);
        EntityPlaceEvent event = mock(EntityPlaceEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(event.getEntityType()).thenReturn(EntityType.TNT_MINECART);
        when(event.getEntity()).thenReturn(cart);
        when(event.getPlayer()).thenReturn(player);
        when(harness.generations.ensurePersisted()).thenReturn(false);

        harness.listener.activateLifecycle();
        harness.listener.onEntityPlace(event);

        verify(event).setCancelled(true);
        verify(cart, never()).remove();
    }

    @Test
    void acceptedPlayerCartIsTaggedWithCurrentDurableGeneration() {
        LifecycleHarness harness = lifecycleHarness(true, 83L);
        Entity cart = mock(Entity.class);
        Player player = mock(Player.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        EntityPlaceEvent event = mock(EntityPlaceEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getPersistentDataContainer()).thenReturn(pdc);
        when(event.getEntity()).thenReturn(cart);
        when(event.getPlayer()).thenReturn(player);

        harness.listener.activateLifecycle();
        harness.listener.onAcceptedCartPlace(event);

        verify(pdc).set(harness.key, PersistentDataType.LONG, 83L);
        verify(cart, never()).remove();
    }

    @Test
    void currentTaggedCartIsRemovedWhenItLeavesEffectiveWarzone() {
        LifecycleHarness harness = lifecycleHarness(true, 87L);
        Vehicle cart = mock(Vehicle.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        VehicleMoveEvent event = mock(VehicleMoveEvent.class);
        Location outside = mock(Location.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(harness.key, PersistentDataType.LONG)).thenReturn(87L);
        when(event.getVehicle()).thenReturn(cart);
        when(event.getTo()).thenReturn(outside);
        when(harness.runtime.appliesAt(outside)).thenReturn(false);

        harness.listener.activateLifecycle();
        harness.listener.onCartMove(event);

        verify(cart).remove();
    }

    @Test
    void untaggedPreexistingCartNeverReceivesWorldGuardPrimeBypass() {
        LifecycleHarness harness = lifecycleHarness(true, 91L);
        Entity cart = mock(Entity.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(cart.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(harness.key, PersistentDataType.LONG)).thenReturn(null);
        when(event.getEntity()).thenReturn(cart);
        when(event.isCancelled()).thenReturn(false, true);
        when(harness.worldGuard.tntExplosionsGloballyBlocked(harness.location)).thenReturn(true);

        harness.listener.activateLifecycle();
        harness.listener.onCartPrimeBeforeWorldGuard(event);
        harness.listener.onCartPrimeWorldGuardBypass(event);

        verify(event, never()).setCancelled(false);
        verify(harness.worldGuard, never()).tntExplosionsGloballyBlocked(harness.location);
    }

    @Test
    void currentTaggedCartReceivesOnlyTheIntendedWorldGuardPrimeBypass() {
        LifecycleHarness harness = lifecycleHarness(true, 103L);
        Entity cart = mock(Entity.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
        when(cart.getType()).thenReturn(EntityType.TNT_MINECART);
        when(cart.getLocation()).thenReturn(harness.location);
        when(cart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(cart.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(harness.key, PersistentDataType.LONG)).thenReturn(103L);
        when(event.getEntity()).thenReturn(cart);
        when(event.isCancelled()).thenReturn(false, true);
        when(harness.worldGuard.tntExplosionsGloballyBlocked(harness.location)).thenReturn(true);

        harness.listener.activateLifecycle();
        harness.listener.onCartPrimeBeforeWorldGuard(event);
        harness.listener.onCartPrimeWorldGuardBypass(event);

        verify(event).setCancelled(false);
        verify(event, never()).setCancelled(true);
    }

    private LifecycleHarness lifecycleHarness(boolean generationPersisted, long generation) {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        Server server = mock(Server.class);
        WorldGuardQueryService worldGuard = mock(WorldGuardQueryService.class);
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        RotationManager rotations = mock(RotationManager.class);
        TemporaryBlockService temporary = mock(TemporaryBlockService.class);
        CartArtifactGenerationStore generations = mock(CartArtifactGenerationStore.class);
        Location location = mock(Location.class);
        NamespacedKey key = new NamespacedKey("maceguard", "test-cart-generation");

        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of());
        when(module.runtime()).thenReturn(runtime);
        when(runtime.rotations()).thenReturn(rotations);
        when(rotations.active()).thenReturn(carts());
        when(runtime.appliesAt(location)).thenReturn(true);
        when(generations.advance()).thenReturn(generationPersisted);
        when(generations.healthy()).thenReturn(generationPersisted);
        when(generations.generation()).thenReturn(generation);

        ExplosiveControlListener listener = new ExplosiveControlListener(plugin, worldGuard,
                ignored -> false, module, temporary, null, generations, key);
        return new LifecycleHarness(listener, worldGuard, generations, runtime, location, key);
    }

    private static TemporaryBlock block(boolean owned, TemporaryBlock.Kind kind) {
        String expected = kind == TemporaryBlock.Kind.CART_RAIL
                ? "minecraft:rail" : "minecraft:cobweb";
        return new TemporaryBlock(UUID.randomUUID().toString(), 1, 64, 1, expected,
                "minecraft:air", Long.MAX_VALUE, false, owned, kind);
    }

    private static WarzoneConfig.ActiveSet carts() {
        return new WarzoneConfig.ActiveSet(List.of("carts"), "Carts", "Carts",
                Set.of(WarzoneConfig.Effect.CARTS), Map.of());
    }

    private record LifecycleHarness(ExplosiveControlListener listener,
                                    WorldGuardQueryService worldGuard,
                                    CartArtifactGenerationStore generations,
                                    WarzoneRuntime runtime, Location location,
                                    NamespacedKey key) { }
}
