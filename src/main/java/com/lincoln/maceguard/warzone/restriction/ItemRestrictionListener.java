package com.lincoln.maceguard.warzone.restriction;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ItemRestrictionListener implements Listener {
    private static final int LUNGE_TARGET_RANGE = 6;
    private static final Set<Material> PROJECTILE_USES = Set.of(
            Material.ENDER_PEARL, Material.WIND_CHARGE, Material.SNOWBALL, Material.EGG,
            Material.SPLASH_POTION, Material.LINGERING_POTION, Material.EXPERIENCE_BOTTLE,
            Material.FIREWORK_ROCKET, Material.TRIDENT, Material.BOW, Material.CROSSBOW);

    private final RestrictionService restrictions;
    private final WarzoneRegionService region;
    private final WarzoneMessageService messages;
    private final LungeAttemptTracker lungeAttempts;
    private final Map<UUID, RestrictionDecision> successfulLunge = new HashMap<>();
    private final Map<UUID, PendingProjectile> pendingProjectiles = new HashMap<>();

    public ItemRestrictionListener(RestrictionService restrictions, WarzoneRegionService region,
                                   WarzoneMessageService messages, LungeAttemptTracker lungeAttempts) {
        this.restrictions = restrictions;
        this.region = region;
        this.messages = messages;
        this.lungeAttempts = lungeAttempts;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Material material = event.getItem() == null ? null : event.getItem().getType();
        if (material == null || material == Material.COBWEB || isProjectileUse(material)) return;
        RestrictionDecision decision = materialDecision(event.getPlayer(), material, false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulInteract(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Material material = event.getItem() == null ? null : event.getItem().getType();
        if (material == null || material == Material.COBWEB || isProjectileUse(material)) return;
        restrictions.success(event.getPlayer().getUniqueId(), materialDecision(event.getPlayer(), material, false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerLaunch(PlayerLaunchProjectileEvent event) {
        RestrictionDecision decision = materialDecision(event.getPlayer(), event.getItemStack().getType(), false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        event.setShouldConsume(false);
        messages.denial(event.getPlayer(), decision);
    }

    /** Records the one decision; ProjectileLaunchEvent only confirms final launch success. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedPlayerLaunch(PlayerLaunchProjectileEvent event) {
        RestrictionDecision decision = materialDecision(event.getPlayer(), event.getItemStack().getType(), false);
        if (decision.startsCooldownAfterSuccess())
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
        restrictions.success(player.getUniqueId(), materialDecision(player,
                player.getInventory().getItemInMainHand().getType(), region.contains(event.getEntity().getLocation())));
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
        if (decision.startsCooldownAfterSuccess())
            pendingProjectiles.put(event.getProjectile().getUniqueId(),
                    new PendingProjectile(player.getUniqueId(), decision, System.nanoTime() + 5_000_000_000L));
    }

    /**
     * Finalizes, but never re-decides, a projectile action. This sees cancellation from plugins
     * that act on ProjectileLaunchEvent and therefore cannot start a cooldown for a failed launch.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunchFinalized(ProjectileLaunchEvent event) {
        PendingProjectile pending = pendingProjectiles.remove(event.getEntity().getUniqueId());
        if (pending != null && !event.isCancelled())
            restrictions.success(pending.playerId(), pending.decision());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.COBWEB) return;
        RestrictionDecision decision = restrictions.material(event.getPlayer().getUniqueId(),
                event.getBlockPlaced().getType(), bypass(event.getPlayer()), region.contains(event.getBlockPlaced().getLocation()), false);
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(event.getPlayer(), decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.COBWEB) return;
        RestrictionDecision decision = restrictions.material(event.getPlayer().getUniqueId(),
                event.getBlockPlaced().getType(), bypass(event.getPlayer()), region.contains(event.getBlockPlaced().getLocation()), false);
        restrictions.success(event.getPlayer().getUniqueId(), decision);
    }

    /** Records an actual Lunge-enchanted spear swing without cancelling the swing or attack. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!isLungeSpear(player.getInventory().getItemInMainHand())) return;
        RayTraceResult hit = player.rayTraceEntities(LUNGE_TARGET_RANGE);
        boolean targetInside = hit != null && hit.getHitEntity() != null
                && region.contains(hit.getHitEntity().getLocation());
        if (!region.contains(player.getLocation()) && !targetInside) return;
        recordLungeAttempt(player, targetInside);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack()) return;
        if (!isLungeSpear(event.getPlayer().getInventory().getItemInMainHand())) return;
        boolean targetInside = region.contains(event.getAttacked().getLocation());
        if (!region.contains(event.getPlayer().getLocation()) && !targetInside) return;
        recordLungeAttempt(event.getPlayer(), targetInside);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLungeVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (!isLungeSpear(player.getInventory().getItemInMainHand())) return;
        var attempt = lungeAttempts.consumeIfLunge(player.getUniqueId(), vector(event.getVelocity())).orElse(null);
        if (attempt == null) return;
        RestrictionDecision itemDecision = materialDecision(player,
                player.getInventory().getItemInMainHand().getType(), attempt.targetInside());
        if (itemDecision.denied()) {
            event.setCancelled(true);
            successfulLunge.remove(player.getUniqueId());
            messages.denial(player, itemDecision);
            return;
        }
        RestrictionDecision decision = restrictions.lunge(player.getUniqueId(), bypass(player),
                region.contains(player.getLocation()), attempt.targetInside());
        if (decision.denied()) {
            event.setCancelled(true);
            successfulLunge.remove(player.getUniqueId());
            messages.denial(player, decision);
        } else if (decision.startsCooldownAfterSuccess()) {
            successfulLunge.put(player.getUniqueId(), decision);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulLungeVelocity(PlayerVelocityEvent event) {
        RestrictionDecision decision = successfulLunge.remove(event.getPlayer().getUniqueId());
        if (decision != null) restrictions.success(event.getPlayer().getUniqueId(), decision);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lungeAttempts.remove(event.getPlayer().getUniqueId());
        successfulLunge.remove(event.getPlayer().getUniqueId());
    }

    public void clear() {
        lungeAttempts.clear();
        successfulLunge.clear();
        pendingProjectiles.clear();
    }

    public void cleanup() {
        long now = System.nanoTime();
        pendingProjectiles.values().removeIf(pending -> pending.deadlineNanos() <= now);
    }

    private void recordLungeAttempt(Player player, boolean targetInside) {
        Vector direction = player.getLocation().getDirection();
        Vector velocity = player.getVelocity();
        lungeAttempts.record(player.getUniqueId(), vector(direction), vector(velocity), targetInside);
    }

    private RestrictionDecision materialDecision(Player player, Material material, boolean targetInside) {
        return restrictions.material(player.getUniqueId(), material, bypass(player),
                region.contains(player.getLocation()), targetInside);
    }

    private boolean bypass(Player player) { return player.hasPermission("warzonerotator.bypass"); }
    private boolean isProjectileUse(Material material) {
        return PROJECTILE_USES.contains(material) || RestrictionTarget.isSpear(material);
    }
    private boolean isLungeSpear(ItemStack item) {
        return RestrictionTarget.isSpear(item.getType()) && item.containsEnchantment(Enchantment.LUNGE);
    }
    private LungeAttemptTracker.Vec3 vector(Vector value) {
        return new LungeAttemptTracker.Vec3(value.getX(), value.getY(), value.getZ());
    }

    private record PendingProjectile(UUID playerId, RestrictionDecision decision, long deadlineNanos) { }
}
