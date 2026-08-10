package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.bootstrap.PluginRuntime;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import com.lincoln.maceguard.warzone.rotation.RotationManager;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttributeSwapRestrictionListenerTest {
    @Test void swappingAwayDisabledMaceStillCancelsImmediateAttack() {
        Harness harness = harness(restricted(RestrictionTarget.parse("MACE").orElseThrow(),
                RestrictionMode.DISABLED, Duration.ZERO));
        harness.swap(Material.MACE, Material.DIAMOND_SWORD);

        PrePlayerAttackEntityEvent attack = harness.preAttack();
        harness.listener.onPreAttack(attack);

        verify(attack).setCancelled(true);
        verify(harness.messages).denial(eq(harness.player),
                argThat(decision -> decision.target() != null
                        && "MACE".equals(decision.target().id()) && decision.denied()),
                eq(Material.MACE));
    }

    @Test void swappingAwayDisabledSpearStillCancelsImmediateReachAttack() {
        Harness harness = harness(restricted(RestrictionTarget.SPEAR,
                RestrictionMode.DISABLED, Duration.ZERO));
        harness.swap(Material.IRON_SPEAR, Material.DIAMOND_AXE);

        PrePlayerAttackEntityEvent attack = harness.preAttack();
        harness.listener.onPreAttack(attack);

        verify(attack).setCancelled(true);
        verify(harness.messages).denial(eq(harness.player),
                argThat(decision -> decision.target() == RestrictionTarget.SPEAR && decision.denied()),
                eq(Material.IRON_SPEAR));
    }

    @Test void acceptedSwappedMaceHitStartsAuthoritativeCooldown() {
        Duration duration = Duration.ofSeconds(5);
        Harness harness = harness(restricted(RestrictionTarget.parse("MACE").orElseThrow(),
                RestrictionMode.COOLDOWN, duration));
        harness.swap(Material.MACE, Material.DIAMOND_SWORD);

        PrePlayerAttackEntityEvent attack = harness.preAttack();
        harness.listener.onPreAttack(attack);
        verify(attack, never()).setCancelled(true);

        EntityDamageByEntityEvent damage = mock(EntityDamageByEntityEvent.class);
        when(damage.getDamager()).thenReturn(harness.player);
        when(damage.getEntity()).thenReturn(harness.target);
        when(damage.getFinalDamage()).thenReturn(8.0D);
        harness.listener.onDirectDamage(damage);
        harness.listener.onSuccessfulDirectDamage(damage);

        assertTrue(harness.cooldowns.active(harness.playerId,
                RestrictionTarget.parse("MACE").orElseThrow()));
        verify(harness.messages).cooldownStarted(eq(harness.player),
                argThat(RestrictionDecision::startsCooldownAfterSuccess), eq(Material.MACE));
        verify(harness.player).setCooldown(eq(Material.MACE), intThat(ticks -> ticks >= 100));
    }

    private Harness harness(WarzoneConfig.Restriction restriction) {
        MaceGuardPlugin plugin = mock(MaceGuardPlugin.class);
        PluginRuntime pluginRuntime = mock(PluginRuntime.class);
        WarzoneModule module = mock(WarzoneModule.class);
        WarzoneRuntime runtime = mock(WarzoneRuntime.class);
        WarzoneRegionService region = mock(WarzoneRegionService.class);
        RotationManager rotations = mock(RotationManager.class);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        AtomicLong clock = new AtomicLong(1_000L);
        CooldownService cooldowns = new CooldownService(clock::get);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Entity target = mock(Entity.class);
        Location playerLocation = mock(Location.class);
        Location targetLocation = mock(Location.class);
        UUID playerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(plugin.runtime()).thenReturn(pluginRuntime);
        when(pluginRuntime.warzone()).thenReturn(module);
        when(module.runtime()).thenReturn(runtime);
        when(runtime.region()).thenReturn(region);
        when(runtime.rotations()).thenReturn(rotations);
        when(runtime.messages()).thenReturn(messages);
        when(runtime.cooldowns()).thenReturn(cooldowns);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenReturn(playerLocation);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getLocation()).thenReturn(targetLocation);
        when(region.contains(any(Location.class))).thenReturn(true);
        when(player.getCooldown(any(Material.class))).thenReturn(0);

        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(
                List.of("test"), "<white>Test", "test", Set.of(),
                Map.of(restriction.target(), restriction));
        when(rotations.active()).thenReturn(active);

        return new Harness(new AttributeSwapRestrictionListener(plugin, module), player,
                inventory, target, messages, cooldowns, playerId);
    }

    private WarzoneConfig.Restriction restricted(RestrictionTarget target,
                                                  RestrictionMode mode,
                                                  Duration duration) {
        return new WarzoneConfig.Restriction(target, mode, duration);
    }

    private static ItemStack item(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        return stack;
    }

    private static final class Harness {
        private final AttributeSwapRestrictionListener listener;
        private final Player player;
        private final PlayerInventory inventory;
        private final Entity target;
        private final WarzoneMessageService messages;
        private final CooldownService cooldowns;
        private final UUID playerId;

        private Harness(AttributeSwapRestrictionListener listener, Player player,
                        PlayerInventory inventory, Entity target,
                        WarzoneMessageService messages, CooldownService cooldowns,
                        UUID playerId) {
            this.listener = listener;
            this.player = player;
            this.inventory = inventory;
            this.target = target;
            this.messages = messages;
            this.cooldowns = cooldowns;
            this.playerId = playerId;
        }

        private void swap(Material previous, Material current) {
            ItemStack previousItem = item(previous);
            ItemStack currentItem = item(current);
            when(inventory.getItem(0)).thenReturn(previousItem);
            when(inventory.getItem(1)).thenReturn(currentItem);
            when(inventory.getItemInMainHand()).thenReturn(currentItem);
            PlayerItemHeldEvent held = mock(PlayerItemHeldEvent.class);
            when(held.getPlayer()).thenReturn(player);
            when(held.getPreviousSlot()).thenReturn(0);
            when(held.getNewSlot()).thenReturn(1);
            listener.onHeldItemChange(held);
        }

        private PrePlayerAttackEntityEvent preAttack() {
            PrePlayerAttackEntityEvent attack = mock(PrePlayerAttackEntityEvent.class);
            when(attack.getPlayer()).thenReturn(player);
            when(attack.getAttacked()).thenReturn(target);
            when(attack.willAttack()).thenReturn(true);
            return attack;
        }
    }
}
