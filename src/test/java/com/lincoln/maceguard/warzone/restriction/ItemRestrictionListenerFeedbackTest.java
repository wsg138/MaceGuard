package com.lincoln.maceguard.warzone.restriction;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.lincoln.maceguard.warzone.combat.CombatScopeService;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemRestrictionListenerFeedbackTest {
    private static final String PEARL_TARGET = "ENDER_PEARL";
    private static final String MACE_TARGET = "MACE";

    @Test void successfulPearlLaunchStartsCooldownOverlayAndOneMessage() {
        Harness harness = harness(target(PEARL_TARGET), RestrictionMode.COOLDOWN);
        Launch launch = launch(harness.player, Material.ENDER_PEARL);

        harness.listener.onAcceptedPlayerLaunch(launch.accepted);
        harness.listener.onProjectileLaunchFinalized(launch.finalized(false));

        assertTrue(harness.cooldowns.active(harness.playerId, target(PEARL_TARGET)));
        verify(harness.messages, times(1)).cooldownStarted(eq(harness.player),
                any(RestrictionDecision.class), eq(Material.ENDER_PEARL));
        verify(harness.visuals, times(1)).apply(eq(harness.player),
                any(RestrictionDecision.class), eq(Material.ENDER_PEARL));
    }

    @Test void anotherPluginCancellationStartsNothingAndSendsNoStartMessage() {
        Harness harness = harness(target(PEARL_TARGET), RestrictionMode.COOLDOWN);
        Launch launch = launch(harness.player, Material.ENDER_PEARL);
        when(launch.projectile.getPersistentDataContainer())
                .thenReturn(mock(PersistentDataContainer.class));

        harness.listener.onAcceptedPlayerLaunch(launch.accepted);
        harness.listener.onProjectileLaunchFinalized(launch.finalized(true));

        assertFalse(harness.cooldowns.active(harness.playerId, target(PEARL_TARGET)));
        verify(harness.messages, never()).cooldownStarted(any(), any(), any());
        verify(harness.visuals, never()).apply(any(), any(), any());
    }

    @Test void activePearlCooldownPreservesItemAndReportsRemainingWithoutRestarting() {
        Harness harness = harness(target(PEARL_TARGET), RestrictionMode.COOLDOWN);
        RestrictionDecision ready = harness.restrictions.material(harness.playerId,
                Material.ENDER_PEARL, false, true, false);
        harness.restrictions.success(harness.playerId, ready, Material.ENDER_PEARL);
        Duration before = harness.cooldowns.remaining(harness.playerId, target(PEARL_TARGET));
        PlayerLaunchProjectileEvent event = mock(PlayerLaunchProjectileEvent.class);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.ENDER_PEARL);
        when(event.getPlayer()).thenReturn(harness.player);
        when(event.getItemStack()).thenReturn(item);

        harness.listener.onPlayerLaunch(event);

        verify(event).setCancelled(true);
        verify(event).setShouldConsume(false);
        verify(harness.messages).denial(eq(harness.player),
                argThat(RestrictionDecision::denied), eq(Material.ENDER_PEARL));
        assertEquals(before, harness.cooldowns.remaining(harness.playerId,
                target(PEARL_TARGET)));
    }

    @Test void activeWindChargeCooldownDeniesItemUseBeforeAnyProjectileExists() {
        Harness harness = harness(target("WIND_CHARGE"), RestrictionMode.COOLDOWN);
        RestrictionDecision ready = harness.restrictions.material(harness.playerId,
                Material.WIND_CHARGE, false, true, false);
        harness.restrictions.success(harness.playerId, ready, Material.WIND_CHARGE);
        Duration before = harness.cooldowns.remaining(harness.playerId, target("WIND_CHARGE"));

        PlayerInteractEvent interact = windChargeInteract(harness.player);
        harness.listener.onInteract(interact);

        verify(interact).setUseItemInHand(Event.Result.DENY);
        verify(interact, never()).setCancelled(true);
        verify(harness.messages).denial(eq(harness.player),
                argThat(RestrictionDecision::denied), eq(Material.WIND_CHARGE));
        assertEquals(before, harness.cooldowns.remaining(harness.playerId, target("WIND_CHARGE")));

        PlayerLaunchProjectileEvent launch = launch(harness.player, Material.WIND_CHARGE).accepted;
        harness.listener.onPlayerLaunch(launch);
        verify(launch, never()).setCancelled(true);
        verify(launch, never()).setShouldConsume(false);
    }

    @Test void disabledWindChargeAlsoDeniesOnlyThePlayerItemUse() {
        Harness harness = harness(target("WIND_CHARGE"), RestrictionMode.DISABLED);
        PlayerInteractEvent interact = windChargeInteract(harness.player);

        harness.listener.onInteract(interact);

        verify(interact).setUseItemInHand(Event.Result.DENY);
        verify(interact, never()).setCancelled(true);
        verify(harness.messages).denial(eq(harness.player),
                argThat(decision -> decision.result() == RestrictionDecision.Result.DISABLED),
                eq(Material.WIND_CHARGE));
    }

    @Test void successfulWindChargeLaunchHasNoInteractionLaunchDuplicate() {
        Harness harness = harness(target("WIND_CHARGE"), RestrictionMode.COOLDOWN);
        Launch launch = launch(harness.player, Material.WIND_CHARGE);
        harness.listener.onAcceptedPlayerLaunch(launch.accepted);
        harness.listener.onProjectileLaunchFinalized(launch.finalized(false));
        harness.listener.onProjectileLaunchFinalized(launch.finalized(false));
        verify(harness.messages, times(1)).cooldownStarted(eq(harness.player),
                any(RestrictionDecision.class), eq(Material.WIND_CHARGE));
    }

    @Test void noModifierProducesNoProjectileFeedbackOrOverlay() {
        Harness harness = harness(Map.of());
        Launch launch = launch(harness.player, Material.ENDER_PEARL);
        harness.listener.onAcceptedPlayerLaunch(launch.accepted);
        harness.listener.onProjectileLaunchFinalized(launch.finalized(false));
        verifyNoInteractions(harness.messages, harness.visuals);
        assertEquals(0, harness.cooldowns.size());
    }

    @Test void positiveMaceDamageStartsCooldownButZeroDamageDoesNot() {
        Harness positive = harness(target(MACE_TARGET), RestrictionMode.COOLDOWN);
        damageItem(positive.player, Material.MACE);
        EntityDamageByEntityEvent hit = damageEvent(positive.player, 4.0D);
        positive.listener.onDirectDamage(hit);
        positive.listener.onSuccessfulDirectDamage(hit);
        verify(positive.messages).cooldownStarted(eq(positive.player),
                any(RestrictionDecision.class), eq(Material.MACE));
        assertTrue(positive.cooldowns.active(positive.playerId, target(MACE_TARGET)));

        Harness zero = harness(target(MACE_TARGET), RestrictionMode.COOLDOWN);
        damageItem(zero.player, Material.MACE);
        EntityDamageByEntityEvent zeroHit = damageEvent(zero.player, 0.0D);
        zero.listener.onSuccessfulDirectDamage(zeroHit);
        verify(zero.messages, never()).cooldownStarted(any(), any(), any());
        assertFalse(zero.cooldowns.active(zero.playerId, target(MACE_TARGET)));
    }

    @Test void disabledMaceReportsDisabledInsteadOfCountdown() {
        Harness harness = harness(target(MACE_TARGET), RestrictionMode.DISABLED);
        damageItem(harness.player, Material.MACE);
        EntityDamageByEntityEvent event = damageEvent(harness.player, 4.0D);
        harness.listener.onDirectDamage(event);
        verify(event).setCancelled(true);
        verify(harness.messages).denial(eq(harness.player),
                argThat(decision -> decision.result() == RestrictionDecision.Result.DISABLED),
                eq(Material.MACE));
        verify(harness.messages, never()).cooldownStarted(any(), any(), any());
    }

    @Test void wholeSpearMeleeUsesConcreteMaterialAndSpearDamageRemainsIndependent() {
        Harness whole = harness(RestrictionTarget.SPEAR, RestrictionMode.COOLDOWN);
        damageItem(whole.player, Material.IRON_SPEAR);
        EntityDamageByEntityEvent wholeHit = damageEvent(whole.player, 4.0D);
        whole.listener.onSuccessfulDirectDamage(wholeHit);
        assertEquals(Material.IRON_SPEAR,
                whole.cooldowns.concreteMaterial(whole.playerId, RestrictionTarget.SPEAR));
        verify(whole.messages).cooldownStarted(eq(whole.player), any(),
                eq(Material.IRON_SPEAR));

        Harness damage = harness(RestrictionTarget.SPEAR_DAMAGE, RestrictionMode.COOLDOWN);
        damageItem(damage.player, Material.IRON_SPEAR);
        EntityDamageByEntityEvent damageHit = damageEvent(damage.player, 4.0D);
        damage.listener.onSuccessfulDirectDamage(damageHit);
        assertTrue(damage.cooldowns.active(damage.playerId, RestrictionTarget.SPEAR_DAMAGE));
        assertTrue(damage.cooldowns.activeVisualsFor(damage.playerId).isEmpty());
        verify(damage.messages).cooldownStarted(eq(damage.player),
                argThat(decision -> decision.target() == RestrictionTarget.SPEAR_DAMAGE), isNull());
    }

    @Test void elytraAndActualBoostDenialsMessageWithoutFakeCooldown() {
        Harness harness = harness(Map.of());
        when(harness.combatScopes.combatBound(harness.player)).thenReturn(true);
        EntityToggleGlideEvent glide = mock(EntityToggleGlideEvent.class);
        when(glide.getEntity()).thenReturn(harness.player);
        when(glide.isGliding()).thenReturn(true);
        harness.listener.onGlideStart(glide);
        verify(glide).setCancelled(true);
        verify(harness.messages).elytraUnavailable(harness.player);

        PlayerElytraBoostEvent boost = mock(PlayerElytraBoostEvent.class);
        when(boost.getPlayer()).thenReturn(harness.player);
        harness.listener.onElytraBoost(boost);
        verify(boost).setCancelled(true);
        verify(boost).setShouldConsume(false);
        verify(harness.messages).rocketUnavailable(harness.player);
        verifyNoInteractions(harness.visuals);
    }

    private Harness harness(RestrictionTarget target, RestrictionMode mode) {
        Duration cooldown = mode == RestrictionMode.COOLDOWN ? Duration.ofSeconds(10) : Duration.ZERO;
        return harness(Map.of(target, new WarzoneConfig.Restriction(target, mode, cooldown)));
    }

    private Harness harness(Map<RestrictionTarget, WarzoneConfig.Restriction> restrictionsMap) {
        AtomicLong clock = new AtomicLong(1_000L);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(player.hasPermission("warzonerotator.bypass")).thenReturn(false);
        WarzoneConfig.ActiveSet active = new WarzoneConfig.ActiveSet(List.of("test"),
                "Test", "Test", Set.of(), restrictionsMap);
        CooldownService cooldowns = new CooldownService(clock::get);
        RestrictionService restrictions = new RestrictionService(() -> active, cooldowns);
        CombatScopeService combatScopes = mock(CombatScopeService.class);
        when(combatScopes.latch(any(UUID.class))).thenReturn(java.util.Optional.empty());
        VisualCooldownService visuals = mock(VisualCooldownService.class);
        WarzoneRegionService region = mock(WarzoneRegionService.class);
        when(region.contains(any(Location.class))).thenReturn(true);
        WarzoneMessageService messages = mock(WarzoneMessageService.class);
        Plugin plugin = mock(Plugin.class);
        PluginMeta pluginMeta = mock(PluginMeta.class);
        when(pluginMeta.getName()).thenReturn("MaceGuard");
        when(pluginMeta.namespace()).thenReturn("maceguard");
        when(plugin.getName()).thenReturn("MaceGuard");
        when(plugin.namespace()).thenReturn("maceguard");
        when(plugin.getPluginMeta()).thenReturn(pluginMeta);
        ItemRestrictionListener listener = new ItemRestrictionListener(plugin, restrictions,
                combatScopes, cooldowns, visuals, region, messages, () -> active,
                new LungeVelocityGate(System::nanoTime, Duration.ofMillis(250)));
        return new Harness(playerId, player, restrictions, cooldowns, combatScopes,
                visuals, messages, listener);
    }

    private PlayerInteractEvent windChargeInteract(Player player) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.WIND_CHARGE);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    private Launch launch(Player player, Material material) {
        PlayerLaunchProjectileEvent accepted = mock(PlayerLaunchProjectileEvent.class);
        ItemStack item = mock(ItemStack.class);
        Projectile projectile = mock(Projectile.class);
        Server server = mock(Server.class);
        when(item.getType()).thenReturn(material);
        when(accepted.getPlayer()).thenReturn(player);
        when(accepted.getItemStack()).thenReturn(item);
        when(accepted.getProjectile()).thenReturn(projectile);
        when(projectile.getUniqueId()).thenReturn(UUID.randomUUID());
        when(projectile.getServer()).thenReturn(server);
        when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        return new Launch(accepted, projectile);
    }

    private void damageItem(Player player, Material material) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(inventory.getItemInMainHand()).thenReturn(item);
        when(player.getInventory()).thenReturn(inventory);
    }

    private EntityDamageByEntityEvent damageEvent(Player player, double damage) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Entity victim = mock(Entity.class);
        when(victim.getLocation()).thenReturn(mock(Location.class));
        when(event.getDamager()).thenReturn(player);
        when(event.getEntity()).thenReturn(victim);
        when(event.getFinalDamage()).thenReturn(damage);
        return event;
    }

    private static RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }

    private record Harness(UUID playerId, Player player, RestrictionService restrictions,
                           CooldownService cooldowns, CombatScopeService combatScopes,
                           VisualCooldownService visuals, WarzoneMessageService messages,
                           ItemRestrictionListener listener) { }

    private record Launch(PlayerLaunchProjectileEvent accepted, Projectile projectile) {
        ProjectileLaunchEvent finalized(boolean cancelled) {
            ProjectileLaunchEvent event = mock(ProjectileLaunchEvent.class);
            when(event.getEntity()).thenReturn(projectile);
            when(event.isCancelled()).thenReturn(cancelled);
            return event;
        }
    }
}
