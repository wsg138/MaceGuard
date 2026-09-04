package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.warzone.combat.CombatScopeService;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LungeJabEnforcementTest {
    @Test
    void validJabStartsConfiguredCooldownWithoutVelocityCallback() {
        Harness harness = harness(RestrictionMode.COOLDOWN, Duration.ofSeconds(5));

        harness.listener.onArmSwing(harness.swing);

        assertFalse(harness.cooldowns.active(harness.playerId, RestrictionTarget.SPEAR_LUNGE));
        Runnable fallback = harness.nextTick.get();
        assertNotNull(fallback);
        fallback.run();

        assertTrue(harness.cooldowns.active(harness.playerId, RestrictionTarget.SPEAR_LUNGE));
        verify(harness.messages).cooldownStarted(eq(harness.player),
                org.mockito.ArgumentMatchers.argThat(decision ->
                        decision.target() == RestrictionTarget.SPEAR_LUNGE
                                && decision.restriction() != null
                                && Duration.ofSeconds(5).equals(decision.restriction().cooldown())),
                eq(null));
    }

    @Test
    void disabledLungeIsSuppressedAtTheJabBoundary() {
        Harness harness = harness(RestrictionMode.DISABLED, Duration.ZERO);

        harness.listener.onArmSwing(harness.swing);

        verify(harness.itemMeta).removeEnchant(Enchantment.LUNGE);
        verify(harness.inventory).setItem(0, harness.spear);
        verify(harness.messages).denial(eq(harness.player),
                org.mockito.ArgumentMatchers.argThat(decision ->
                        decision.target() == RestrictionTarget.SPEAR_LUNGE && decision.denied()),
                eq(null));
        verify(harness.scheduler, never()).runTask(eq(harness.plugin), any(Runnable.class));
    }

    private Harness harness(RestrictionMode mode, Duration duration) {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        PluginRuntime pluginRuntime = mock(PluginRuntime.class);
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        WarzoneRegionService region = mock(WarzoneRegionService.class);
        RotationManager rotations = mock(RotationManager.class);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        CombatScopeService combatScopes = mock(CombatScopeService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask repeatingTask = mock(BukkitTask.class);
        BukkitTask nextTask = mock(BukkitTask.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack spear = mock(ItemStack.class);
        ItemMeta itemMeta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        Location location = mock(Location.class);
        PlayerArmSwingEvent swing = mock(PlayerArmSwingEvent.class);
        AtomicReference<Runnable> nextTick = new AtomicReference<>();
        AtomicLong clock = new AtomicLong(1_000L);
        CooldownService cooldowns = new CooldownService(clock::get);
        UUID playerId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(repeatingTask);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            nextTick.set(invocation.getArgument(1));
            return nextTask;
        });
        when(plugin.runtime()).thenReturn(pluginRuntime);
        when(pluginRuntime.warzone()).thenReturn(module);
        when(module.runtime()).thenReturn(runtime);
        when(runtime.region()).thenReturn(region);
        when(runtime.rotations()).thenReturn(rotations);
        when(runtime.messages()).thenReturn(messages);
        when(runtime.cooldowns()).thenReturn(cooldowns);
        when(runtime.combatScopes()).thenReturn(combatScopes);

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenReturn(location);
        when(player.getFoodLevel()).thenReturn(20);
        when(player.isInWater()).thenReturn(false);
        when(player.isGliding()).thenReturn(false);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("warzonerotator.bypass")).thenReturn(false);
        when(player.getCooledAttackStrength(0.0F)).thenReturn(1.0F);

        when(inventory.getItemInMainHand()).thenReturn(spear);
        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getSize()).thenReturn(1);
        when(inventory.getItem(anyInt())).thenReturn(spear);
        when(spear.getType()).thenReturn(Material.IRON_SPEAR);
        when(spear.getEnchantmentLevel(Enchantment.LUNGE)).thenReturn(1);
        when(spear.getDataOrDefault(DataComponentTypes.MINIMUM_ATTACK_CHARGE, 0.0F)).thenReturn(0.0F);
        when(spear.getItemMeta()).thenReturn(itemMeta);
        when(spear.setItemMeta(itemMeta)).thenReturn(true);
        when(itemMeta.getPersistentDataContainer()).thenReturn(data);
        when(itemMeta.getEnchantLevel(Enchantment.LUNGE)).thenReturn(1);

        when(region.contains(any(Location.class))).thenReturn(true);
        WarzoneConfig.Restriction restriction = new WarzoneConfig.Restriction(
                RestrictionTarget.SPEAR_LUNGE, mode, duration);
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                List.of("lunge-test"), "<white>Lunge Test", "lunge-test", Set.of(),
                Map.of(RestrictionTarget.SPEAR_LUNGE, restriction));
        when(rotations.active()).thenReturn(active);

        when(swing.getHand()).thenReturn(EquipmentSlot.HAND);
        when(swing.getPlayer()).thenReturn(player);

        return new Harness(plugin, scheduler, new AttributeSwapRestrictionListener(plugin, module),
                player, inventory, spear, itemMeta, messages, cooldowns, playerId, swing, nextTick);
    }

    private record Harness(MaceGuardPlugin plugin, BukkitScheduler scheduler,
                           AttributeSwapRestrictionListener listener, Player player,
                           PlayerInventory inventory, ItemStack spear, ItemMeta itemMeta,
                           WarzoneMessageService messages, CooldownService cooldowns,
                           UUID playerId, PlayerArmSwingEvent swing,
                           AtomicReference<Runnable> nextTick) { }
}
