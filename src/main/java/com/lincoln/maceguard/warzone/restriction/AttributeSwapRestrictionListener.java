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
import org.bukkit.event.player.PlayerItemCooldownEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Closes short vanilla/Paper weapon-state gaps for Warzone Mace/Spear restrictions. A
 * just-swapped-away restricted weapon remains authoritative for the immediately following melee
 * attempt, and Paper 1.21.11 Lunge restrictions are projected onto the held Spear enchantment
 * because that server version has no cancellable Lunge event.
 */
public final class AttributeSwapRestrictionListener implements Listener {
    private static final Duration SWAP_WINDOW = Duration.ofMillis(250);
    private static final String MACE_SMASH = "mace_smash";
    private static final String SPEAR_DAMAGE = "spear";
    private static final int MIN_LUNGE_FOOD = 6;

    private final MaceGuardPlugin plugin;
    private final WarzoneModule module;
    private final AttributeSwapTracker swaps = new AttributeSwapTracker(System::nanoTime, SWAP_WINDOW);
    private final LungeEnchantmentSuppressor lungeSuppressor;
    private final Set<UUID> pendingLungeCooldowns = new HashSet<>();
    private BukkitTask lungeReconcileTask;

    public AttributeSwapRestrictionListener(MaceGuardPlugin plugin, WarzoneModule module) {
        this.plugin = plugin;
        this.module = module;
        this.lungeSuppressor = new LungeEnchantmentSuppressor(plugin);
        this.lungeReconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                this::reconcileLungeRestrictions, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        var inventory = event.getPlayer().getInventory();
        swaps.recordTransition(event.getPlayer().getUniqueId(),
                type(inventory.getItem(event.getPreviousSlot())),
                type(inventory.getItem(event.getNewSlot())));

        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) return;
        restoreSlot(inventory, event.getPreviousSlot());
        reconcileLungeSlot(runtime, event.getPlayer(), event.getNewSlot());
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
        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime != null) reconcileLunge(runtime, event.getPlayer());
    }

    /**
     * A successful 1.21.11 Spear Jab applies its material cooldown. For an eligible held Spear
     * with live Lunge, that event is the fallback success signal for open-air Lunges, which never
     * reach PrePlayerAttackEntityEvent. The one-tick delay lets the older entity-hit velocity gate
     * win first when it did observe the same Lunge, preventing duplicate cooldown starts/messages.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpearItemCooldown(PlayerItemCooldownEvent event) {
        if (event.getCooldown() <= 0 || !RestrictionTarget.isSpear(event.getType())) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != event.getType() || !lungeSuppressor.hasLiveLunge(held)
                || !vanillaLungeEligible(player)) return;

        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) return;
        RestrictionDecision decision = decide(runtime, player, RestrictionTarget.SPEAR_LUNGE,
                player.getLocation());
        if (!decision.startsCooldownAfterSuccess()
                || !pendingLungeCooldowns.add(player.getUniqueId())) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> finalizeObservedLunge(player));
    }

    private void finalizeObservedLunge(Player player) {
        pendingLungeCooldowns.remove(player.getUniqueId());
        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null || !player.isOnline()) return;

        RestrictionDecision decision = decide(runtime, player, RestrictionTarget.SPEAR_LUNGE,
                player.getLocation());
        if (decision.startsCooldownAfterSuccess())
            startCooldown(runtime, player, decision, null);
        reconcileLunge(runtime, player);
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

        Material sourceMaterial = recent.orElseThrow();
        AttackDecisions decisions = decisions(runtime, player, sourceMaterial,
                event.getAttacked().getLocation());
        RestrictionDecision denial = decisions.denial();
        if (denial != null) {
            event.setCancelled(true);
            runtime.messages().denial(player, denial, sourceMaterial);
            return;
        }
        if (decisions.startsCooldown()) {
            swaps.recordAttack(player.getUniqueId(), event.getAttacked().getUniqueId(),
                    sourceMaterial, decisions.item(), decisions.spearDamage());
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
                ? spearDecisions(runtime, player, event.getEntity().getLocation())
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDirectDamageFinalized(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        AttributeSwapTracker.AttackAttempt attempt = swaps.consumeAttack(
                player.getUniqueId(), event.getEntity().getUniqueId()).orElse(null);
        if (attempt == null || event.isCancelled() || event.getFinalDamage() <= 0) return;

        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) return;
        startCooldown(runtime, player, attempt.itemDecision(), attempt.material());
        startCooldown(runtime, player, attempt.spearDamageDecision(), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        swaps.clearPlayer(playerId);
        pendingLungeCooldowns.remove(playerId);
        restoreAllLunges(event.getPlayer());
    }

    private void reconcileLungeRestrictions() {
        WarzoneRuntime runtime = authoritativeRuntime();
        if (runtime == null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) restoreAllLunges(player);
            pendingLungeCooldowns.clear();
            BukkitTask task = lungeReconcileTask;
            if (task != null) {
                task.cancel();
                lungeReconcileTask = null;
            }
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) reconcileLunge(runtime, player);
    }

    private void reconcileLunge(WarzoneRuntime runtime, Player player) {
        var inventory = player.getInventory();
        int heldSlot = inventory.getHeldItemSlot();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot != heldSlot) restoreSlot(inventory, slot);
        }
        reconcileLungeSlot(runtime, player, heldSlot);
    }

    private void reconcileLungeSlot(WarzoneRuntime runtime, Player player, int slot) {
        var inventory = player.getInventory();
        ItemStack item = inventory.getItem(slot);
        if (item == null || !RestrictionTarget.isSpear(item.getType())) {
            restoreSlot(inventory, slot);
            return;
        }

        RestrictionDecision lungeDecision = decide(runtime, player, RestrictionTarget.SPEAR_LUNGE,
                player.getLocation());
        RestrictionDecision spearDecision = decide(runtime, player, RestrictionTarget.SPEAR,
                player.getLocation());
        boolean changed = lungeDecision.denied() || spearDecision.denied()
                ? lungeSuppressor.suppress(item)
                : lungeSuppressor.restore(item);
        if (changed) inventory.setItem(slot, item);
    }

    private void restoreAllLunges(Player player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) restoreSlot(inventory, slot);
    }

    private void restoreSlot(org.bukkit.inventory.PlayerInventory inventory, int slot) {
        ItemStack item = inventory.getItem(slot);
        if (lungeSuppressor.restore(item)) inventory.setItem(slot, item);
    }

    static boolean vanillaLungeEligible(Player player) {
        return player.getFoodLevel() >= MIN_LUNGE_FOOD && !player.isInWater() && !player.isGliding();
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
            return spearDecisions(runtime, player, targetLocation);
        }
        RestrictionDecision item = decide(runtime, player, RestrictionTarget.parse("MACE").orElseThrow(),
                targetLocation);
        return new AttackDecisions(item, RestrictionDecision.unrestricted());
    }

    private AttackDecisions spearDecisions(WarzoneRuntime runtime, Player player,
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
        boolean actorExcluded = runtime.region().exclusionAt(player.getLocation()) != null;
        boolean targetInside = runtime.region().contains(targetLocation);
        WarzoneConfig.ActiveSet active = runtime.rotations().active();
        Map<RestrictionTarget, WarzoneConfig.Restriction> effective;
        if (actorInside || targetInside) effective = active.restrictions();
        else if (!actorExcluded && runtime.combatScopes().carryoverEligible(player))
            effective = active.carriedRestrictions();
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
        runtime.cooldowns().start(player.getUniqueId(), decision.target(),
                decision.restriction().cooldown(), concreteMaterial);
        runtime.messages().cooldownStarted(player, decision, concreteMaterial);
        // Do not write an unowned Bukkit item cooldown here. The authoritative expiry above is
        // sufficient to enforce the exploit path; normal MaceGuard visual projection retains sole
        // ownership of client cooldown overlays so reload/shutdown cannot clobber another plugin.
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
