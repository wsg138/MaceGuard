package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes the short vanilla/Paper equipment-attribute carryover window for Warzone Mace/Spear
 * restrictions. A just-swapped-away restricted weapon remains authoritative for the immediately
 * following melee attempt, and damage-source identity is used as a second line of defense.
 */
public final class AttributeSwapRestrictionListener implements Listener {
    private static final Duration SWAP_WINDOW = Duration.ofMillis(250);
    private static final String MACE_SMASH = "mace_smash";
    private static final String SPEAR_DAMAGE = "spear";

    private final MaceGuardPlugin plugin;
    private final WarzoneModule module;
    private final AttributeSwapTracker swaps = new AttributeSwapTracker(System::nanoTime, SWAP_WINDOW);

    public AttributeSwapRestrictionListener(MaceGuardPlugin plugin, WarzoneModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        var inventory = event.getPlayer().getInventory();
        swaps.recordTransition(event.getPlayer().getUniqueId(),
                type(inventory.getItem(event.getPreviousSlot())),
                type(inventory.getItem(event.getNewSlot())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Event values are post-swap destinations: the item switched to offhand was the old main hand.
        swaps.recordTransition(event.getPlayer().getUniqueId(),
                type(event.getOffHandItem()), type(event.getMainHandItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        if (event.getSlot() != event.getPlayer().getInventory().getHeldItemSlot()) return;
        swaps.recordTransition(event.getPlayer().getUniqueId(),
                type(event.getOldItemStack()), type(event.getNewItemStack()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack()) return;
        Player player = event.getPlayer();
        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) return;

        Material current = player.getInventory().getItemInMainHand().getType();
        Optional<Material> recent = recentSwappedWeapon(player, current);
        if (recent.isEmpty()) return;

        AttackDecisions decisions = decisions(runtime, player, recent.orElseThrow(),
                event.getAttacked().getLocation());
        RestrictionDecision denial = decisions.denial();
        if (denial != null) {
            event.setCancelled(true);
            runtime.messages().denial(player, denial, recent.orElseThrow());
            return;
        }
        if (decisions.startsCooldown()) {
            swaps.recordAttack(player.getUniqueId(), event.getAttacked().getUniqueId(),
                    recent.orElseThrow(), decisions.item(), decisions.spearDamage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDirectDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) return;

        UUID targetId = event.getEntity().getUniqueId();
        if (swaps.findAttack(player.getUniqueId(), targetId).isPresent()) return;

        Material current = player.getInventory().getItemInMainHand().getType();
        AttackSource source = sourceFor(event, player, current);
        if (source == null) return;

        AttackDecisions decisions = source.spear()
                ? spearDecisions(runtime, player, source.material(), event.getEntity().getLocation())
                : decisions(runtime, player, Material.MACE, event.getEntity().getLocation());
        RestrictionDecision denial = decisions.denial();
        if (denial != null) {
            event.setCancelled(true);
            runtime.messages().denial(player, denial, source.material());
            return;
        }
        if (decisions.startsCooldown()) {
            swaps.recordAttack(player.getUniqueId(), targetId, source.material(),
                    decisions.item(), decisions.spearDamage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulDirectDamage(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0 || !(event.getDamager() instanceof Player player)) return;
        AttributeSwapTracker.AttackAttempt attempt = swaps.consumeAttack(
                player.getUniqueId(), event.getEntity().getUniqueId()).orElse(null);
        if (attempt == null) return;
        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) return;

        startCooldown(runtime, player, attempt.itemDecision(), attempt.material());
        startCooldown(runtime, player, attempt.spearDamageDecision(), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        swaps.clearPlayer(event.getPlayer().getUniqueId());
    }

    private AttackSource sourceFor(EntityDamageByEntityEvent event, Player player, Material current) {
        Material recent = recentSwappedWeapon(player, current).orElse(null);
        if (recent != null) return new AttackSource(recent, RestrictionTarget.isSpear(recent));
        if (current != Material.MACE && damageType(event, MACE_SMASH))
            return new AttackSource(Material.MACE, false);
        if (!RestrictionTarget.isSpear(current) && damageType(event, SPEAR_DAMAGE))
            return new AttackSource(null, true);
        return null;
    }

    private Optional<Material> recentSwappedWeapon(Player player, Material current) {
        return swaps.recent(player.getUniqueId()).filter(material -> material != current);
    }

    private AttackDecisions decisions(WarzoneRuntime runtime, Player player, Material material,
                                      org.bukkit.Location targetLocation) {
        if (RestrictionTarget.isSpear(material)) {
            return spearDecisions(runtime, player, material, targetLocation);
        }
        RestrictionDecision item = decide(runtime, player, RestrictionTarget.parse("MACE").orElseThrow(),
                targetLocation);
        return new AttackDecisions(item, RestrictionDecision.unrestricted());
    }

    private AttackDecisions spearDecisions(WarzoneRuntime runtime, Player player, Material material,
                                           org.bukkit.Location targetLocation) {
        RestrictionDecision item = decide(runtime, player, RestrictionTarget.SPEAR, targetLocation);
        RestrictionDecision damage = decide(runtime, player, RestrictionTarget.SPEAR_DAMAGE, targetLocation);
        return new AttackDecisions(item, damage);
    }

    private RestrictionDecision decide(WarzoneRuntime runtime, Player player,
                                       RestrictionTarget target,
                                       org.bukkit.Location targetLocation) {
        if (player.hasPermission("warzonerotator.bypass")) return RestrictionDecision.unrestricted();
        boolean actorInside = runtime.region().contains(player.getLocation());
        boolean targetInside = runtime.region().contains(targetLocation);
        WarzoneConfig.ActiveSet active = runtime.rotations().active();
        Map<RestrictionTarget, WarzoneConfig.Restriction> effective;
        if (actorInside || targetInside) effective = active.restrictions();
        else if (runtime.combatScopes().carryoverEligible(player)) effective = active.carriedRestrictions();
        else effective = Map.of();

        WarzoneConfig.Restriction restriction = effective.get(target);
        if (restriction == null) return RestrictionDecision.unrestricted();
        if (restriction.mode() == RestrictionMode.DISABLED) {
            return new RestrictionDecision(RestrictionDecision.Result.DISABLED,
                    target, restriction, Duration.ZERO);
        }
        Duration remaining = runtime.cooldowns().remaining(player.getUniqueId(), target);
        return new RestrictionDecision(remaining.isZero()
                ? RestrictionDecision.Result.COOLDOWN_READY
                : RestrictionDecision.Result.COOLDOWN_ACTIVE,
                target, restriction, remaining);
    }

    private void startCooldown(WarzoneRuntime runtime, Player player,
                               RestrictionDecision decision, Material concreteMaterial) {
        if (decision == null || !decision.startsCooldownAfterSuccess()
                || decision.target() == null || decision.restriction() == null) return;
        Duration duration = decision.restriction().cooldown();
        runtime.cooldowns().start(player.getUniqueId(), decision.target(), duration, concreteMaterial);
        runtime.messages().cooldownStarted(player, decision, concreteMaterial);

        // The authoritative expiry above is the source of truth. For a concrete whole-item target,
        // mirror it to Bukkit without shortening a longer vanilla/third-party cooldown.
        if (concreteMaterial != null && decision.target() != RestrictionTarget.SPEAR_DAMAGE) {
            int requested = VisualCooldownService.toTicks(duration);
            if (requested > player.getCooldown(concreteMaterial)) {
                player.setCooldown(concreteMaterial, requested);
            }
        }
    }

    private boolean damageType(EntityDamageByEntityEvent event, String key) {
        if (event.getDamageSource() == null || event.getDamageSource().getDamageType() == null) return false;
        NamespacedKey damageKey = event.getDamageSource().getDamageType().getKey();
        return NamespacedKey.MINECRAFT.equals(damageKey.getNamespace())
                && key.equals(damageKey.getKey());
    }

    private WarzoneRuntime authoritativeRuntime() {
        var current = plugin.runtime();
        return current != null && current.warzone() == module ? module.runtime() : null;
    }

    private Material type(ItemStack item) {
        return item == null ? Material.AIR : item.getType();
    }

    private record AttackSource(Material material, boolean spear) { }

    private record AttackDecisions(RestrictionDecision item,
                                   RestrictionDecision spearDamage) {
        RestrictionDecision denial() {
            if (item != null && item.denied()) return item;
            return spearDamage != null && spearDamage.denied() ? spearDamage : null;
        }

        boolean startsCooldown() {
            return item != null && item.startsCooldownAfterSuccess()
                    || spearDamage != null && spearDamage.startsCooldownAfterSuccess();
        }
    }
}
