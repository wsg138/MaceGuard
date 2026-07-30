package com.lincoln.maceguard.warzone.restriction;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ItemRestrictionListener implements Listener {
    private final RestrictionService restrictions;
    private final CooldownService cooldowns;
    private final VisualCooldownService visualCooldowns;
    private final WarzoneRegionService region;
    private final WarzoneMessageService messages;
    private final LungeVelocityGate lungeGate;
    private final Map<UUID, RestrictionDecision> acceptedLunges = new HashMap<>();
    private final Map<UUID, PendingProjectile> pendingProjectiles = new HashMap<>();
    private final Map<UUID, Boolean> visualInsideState = new HashMap<>();

    public ItemRestrictionListener(RestrictionService restrictions, CooldownService cooldowns,
                                   VisualCooldownService visualCooldowns, WarzoneRegionService region,
                                   WarzoneMessageService messages, LungeVelocityGate lungeGate) {
        this.restrictions = restrictions;
        this.cooldowns = cooldowns;
        this.visualCooldowns = visualCooldowns;
        this.region = region;
        this.messages = messages;
        this.lungeGate = lungeGate;
    }

    /** Blocks disabled non-projectile item use. Cooldowns never start from this event. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Material material = event.getItem() == null ? null : event.getItem().getType();
        if (material == null || material == Material.COBWEB || supports(material, CooldownCapability.PROJECTILE)) return;
        RestrictionDecision decision = materialDecision(event.getPlayer(), material, false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerLaunch(PlayerLaunchProjectileEvent event) {
        RestrictionDecision decision = materialDecision(event.getPlayer(), event.getItemStack().getType(), false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        event.setShouldConsume(false);
        messages.denial(event.getPlayer(), decision);
    }

    /** Records one accepted decision; ProjectileLaunchEvent confirms final launch success. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedPlayerLaunch(PlayerLaunchProjectileEvent event) {
        RestrictionDecision decision = materialDecision(event.getPlayer(), event.getItemStack().getType(), false);
        if (decision.startsCooldownAfterSuccess() && supports(decision.target(), CooldownCapability.PROJECTILE))
            pendingProjectiles.put(event.getProjectile().getUniqueId(),
                    new PendingProjectile(event.getPlayer().getUniqueId(), decision,
                            System.nanoTime() + 5_000_000_000L));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDirectDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        RestrictionDecision decision = materialDecision(player, player.getInventory().getItemInMainHand().getType(),
                region.contains(event.getEntity().getLocation()));
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(player, decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulDirectDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || event.getFinalDamage() <= 0) return;
        RestrictionDecision decision = materialDecision(player,
                player.getInventory().getItemInMainHand().getType(), region.contains(event.getEntity().getLocation()));
        if (decision.startsCooldownAfterSuccess() && supports(decision.target(), CooldownCapability.DIRECT_ATTACK))
            completeSuccess(player.getUniqueId(), player, decision);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getBow() == null) return;
        RestrictionDecision decision = materialDecision(player, event.getBow().getType(), false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(player, decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getBow() == null) return;
        RestrictionDecision decision = materialDecision(player, event.getBow().getType(), false);
        if (decision.startsCooldownAfterSuccess() && supports(decision.target(), CooldownCapability.PROJECTILE))
            pendingProjectiles.put(event.getProjectile().getUniqueId(),
                    new PendingProjectile(player.getUniqueId(), decision, System.nanoTime() + 5_000_000_000L));
    }

    /** Finalizes, but never re-decides, a projectile action after all cancellation handlers ran. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunchFinalized(ProjectileLaunchEvent event) {
        PendingProjectile pending = pendingProjectiles.remove(event.getEntity().getUniqueId());
        if (pending == null || event.isCancelled()) return;
        completeSuccess(pending.playerId(), event.getEntity().getServer().getPlayer(pending.playerId()), pending.decision());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.COBWEB) return;
        RestrictionDecision decision = restrictions.material(event.getPlayer().getUniqueId(),
                event.getBlockPlaced().getType(), bypass(event.getPlayer()),
                region.contains(event.getBlockPlaced().getLocation()), false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.COBWEB) return;
        RestrictionDecision decision = restrictions.material(event.getPlayer().getUniqueId(),
                event.getBlockPlaced().getType(), bypass(event.getPlayer()),
                region.contains(event.getBlockPlaced().getLocation()), false);
        if (decision.startsCooldownAfterSuccess() && supports(decision.target(), CooldownCapability.BLOCK_PLACE))
            completeSuccess(event.getPlayer().getUniqueId(), event.getPlayer(), decision);
    }

    /**
     * Paper 1.21.11 has no dedicated Lunge event. Arm the compatibility gate only for a real
     * attackable target and preserve both sides of the warzone boundary decision.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!event.willAttack() || !isLungeSpear(item)) return;

        boolean actorInside = region.contains(player.getLocation());
        boolean targetInside = region.contains(event.getAttacked().getLocation());
        RestrictionDecision itemDecision = restrictions.material(player.getUniqueId(), item.getType(),
                bypass(player), actorInside, targetInside);
        Vector look = player.getLocation().getDirection();
        Vector velocity = player.getVelocity();
        lungeGate.record(player.getUniqueId(), item.getType().name(), vec(look), vec(velocity),
                actorInside, targetInside, itemDecision);
    }

    /**
     * Suppresses only the immediate, forward Lunge velocity associated with the recorded spear hit.
     * Ordinary attacks, damage, holding, swapping, throwing, knockback, and unrelated velocity are untouched.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLungeVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        var attempt = lungeGate.consumeIfLunge(player.getUniqueId(), vec(event.getVelocity()));
        if (attempt.isEmpty()) return;

        RestrictionDecision itemDecision = attempt.get().itemDecision();
        if (itemDecision.denied()) {
            event.setCancelled(true);
            acceptedLunges.remove(player.getUniqueId());
            messages.denial(player, itemDecision);
            return;
        }

        boolean actorInside = attempt.get().actorInside() || region.contains(player.getLocation());
        RestrictionDecision decision = restrictions.lunge(player.getUniqueId(), bypass(player),
                actorInside, attempt.get().targetInside());
        if (decision.denied()) {
            event.setCancelled(true);
            acceptedLunges.remove(player.getUniqueId());
            messages.denial(player, decision);
        } else if (decision.startsCooldownAfterSuccess()) {
            acceptedLunges.put(player.getUniqueId(), decision);
        }
    }

    /** Starts the effect-only cooldown only if the correlated velocity event remained uncancelled. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLungeVelocityFinalized(PlayerVelocityEvent event) {
        RestrictionDecision decision = acceptedLunges.remove(event.getPlayer().getUniqueId());
        if (decision != null && !event.isCancelled())
            completeSuccess(event.getPlayer().getUniqueId(), event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        visualInsideState.remove(event.getPlayer().getUniqueId());
        reconcileVisualCooldowns(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        reconcileVisualCooldowns(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lungeGate.remove(playerId);
        acceptedLunges.remove(playerId);
        visualCooldowns.clearOwned(event.getPlayer());
        visualInsideState.remove(playerId);
    }

    /** Rechecks online players after a WorldGuard region refresh or replacement. */
    public void reconcileVisualCooldowns(Iterable<? extends Player> players) {
        for (Player player : players) reconcileVisualCooldowns(player);
    }

    private void reconcileVisualCooldowns(Player player) {
        UUID playerId = player.getUniqueId();
        boolean inside = region.contains(player.getLocation());
        Boolean previous = visualInsideState.put(playerId, inside);
        if (inside && !Boolean.TRUE.equals(previous)) {
            visualCooldowns.reapply(player, cooldowns.activeFor(playerId));
        } else if (!inside && !Boolean.FALSE.equals(previous)) {
            visualCooldowns.clearOwned(player);
        }
    }

    public void clearTransientState() {
        lungeGate.clear();
        acceptedLunges.clear();
        pendingProjectiles.clear();
        visualInsideState.clear();
    }

    public void clear() { clearTransientState(); }

    public void cleanup() {
        long now = System.nanoTime();
        pendingProjectiles.values().removeIf(pending -> pending.deadlineNanos() <= now);
        lungeGate.cleanup();
        visualCooldowns.cleanup();
    }

    private void completeSuccess(UUID playerId, Player player, RestrictionDecision decision) {
        restrictions.success(playerId, decision);
        if (player != null && region.contains(player.getLocation())) visualCooldowns.apply(player, decision);
    }

    private RestrictionDecision materialDecision(Player player, Material material, boolean targetInside) {
        return restrictions.material(player.getUniqueId(), material, bypass(player),
                region.contains(player.getLocation()), targetInside);
    }

    private boolean bypass(Player player) { return player.hasPermission("warzonerotator.bypass"); }
    private boolean supports(Material material, CooldownCapability capability) {
        return RestrictionTarget.parse(material.name()).orElseThrow().supports(capability);
    }
    private boolean supports(RestrictionTarget target, CooldownCapability capability) {
        return target != null && target.supports(capability);
    }
    private boolean isLungeSpear(ItemStack item) {
        return RestrictionTarget.isSpear(item.getType()) && item.containsEnchantment(Enchantment.LUNGE);
    }
    private LungeVelocityGate.Vec3 vec(Vector vector) {
        return new LungeVelocityGate.Vec3(vector.getX(), vector.getY(), vector.getZ());
    }
    private boolean sameBlock(Location from, Location to) {
        return from.getWorld() != null && to.getWorld() != null
                && from.getWorld().getUID().equals(to.getWorld().getUID())
                && from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private record PendingProjectile(UUID playerId, RestrictionDecision decision, long deadlineNanos) { }
}
