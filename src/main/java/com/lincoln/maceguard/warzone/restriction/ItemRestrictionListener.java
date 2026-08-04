package com.lincoln.maceguard.warzone.restriction;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.message.WarzoneMessageService;
import com.lincoln.maceguard.warzone.region.WarzoneRegionService;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class ItemRestrictionListener implements Listener {
    private final RestrictionService restrictions;
    private final CooldownService cooldowns;
    private final VisualCooldownService visualCooldowns;
    private final WarzoneRegionService region;
    private final WarzoneMessageService messages;
    private final Supplier<WarzoneConfig.ActiveSet> activeSet;
    private final LungeVelocityGate lungeGate;
    private final ProjectileLaunchTracker projectileLaunches =
            new ProjectileLaunchTracker();
    private final AutomatedProjectileLaunchTracker automatedLaunches =
            new AutomatedProjectileLaunchTracker();
    private final Map<UUID, RestrictionDecision> acceptedLunges = new HashMap<>();
    private final Map<UUID, Boolean> visualInsideState = new HashMap<>();

    public ItemRestrictionListener(RestrictionService restrictions, CooldownService cooldowns,
                                   VisualCooldownService visualCooldowns, WarzoneRegionService region,
                                   WarzoneMessageService messages,
                                   Supplier<WarzoneConfig.ActiveSet> activeSet,
                                   LungeVelocityGate lungeGate) {
        this.restrictions = restrictions;
        this.cooldowns = cooldowns;
        this.visualCooldowns = visualCooldowns;
        this.region = region;
        this.messages = messages;
        this.activeSet = activeSet;
        this.lungeGate = lungeGate;
    }

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedPlayerLaunch(PlayerLaunchProjectileEvent event) {
        RestrictionDecision decision = materialDecision(event.getPlayer(), event.getItemStack().getType(), false);
        if (decision.startsCooldownAfterSuccess() && supports(decision.target(), CooldownCapability.PROJECTILE))
            projectileLaunches.record(event.getProjectile().getUniqueId(),
                    event.getPlayer().getUniqueId(), decision,
                    System.nanoTime() + 5_000_000_000L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWindChargeDispense(BlockDispenseEvent event) {
        if (event.getItem().getType() != Material.WIND_CHARGE
                || !AutomatedProjectileRestriction.windChargeDisabled(activeSet.get())) return;
        Location source = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (region.contains(source)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWindChargeDispenseFinalized(BlockDispenseEvent event) {
        if (event.isCancelled() || event.getItem().getType() != Material.WIND_CHARGE
                || !AutomatedProjectileRestriction.windChargeDisabled(activeSet.get())) return;
        Location source = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        automatedLaunches.record(event.getBlock().getWorld().getUID(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ(),
                org.bukkit.Bukkit.getCurrentTick(),
                region.contains(source), automatedVec(event.getVelocity()),
                System.nanoTime() + 250_000_000L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutomatedWindChargeLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getType() != EntityType.WIND_CHARGE
                || !AutomatedProjectileRestriction.windChargeDisabled(activeSet.get())) return;

        Location launch = projectile.getLocation();
        if (launch.getWorld() == null) return;
        long tick = projectile.getServer().getCurrentTick();
        long now = System.nanoTime();
        Object shooter = projectile.getShooter();

        if (shooter instanceof BlockProjectileSource source) {
            handleAssignedBlockSource(event, source, launch, tick, now);
            return;
        }

        boolean defaultSpawnReason = projectile.getEntitySpawnReason()
                == CreatureSpawnEvent.SpawnReason.DEFAULT;
        if (!AutomatedProjectileRestriction.canCorrelatePending(
                shooter == null, defaultSpawnReason)) return;
        handlePendingBlockSource(event, projectile, launch, tick, now);
    }

    private void handleAssignedBlockSource(ProjectileLaunchEvent event,
                                           BlockProjectileSource source,
                                           Location launch, long tick,
                                           long now) {
        automatedLaunches.consumeExactSource(source.getBlock().getWorld().getUID(),
                source.getBlock().getX(), source.getBlock().getY(),
                source.getBlock().getZ(), tick, now);
        Location sourceLocation = source.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (AutomatedProjectileRestriction.blocksWindCharge(activeSet.get(),
                region.contains(sourceLocation), region.contains(launch)))
            event.setCancelled(true);
    }

    private void handlePendingBlockSource(ProjectileLaunchEvent event,
                                          Projectile projectile,
                                          Location launch, long tick,
                                          long now) {
        var match = automatedLaunches.match(launch.getWorld().getUID(), tick,
                automatedVec(launch), automatedVec(projectile.getVelocity()), now);
        if (match.isEmpty()) return;
        boolean blocked = AutomatedProjectileRestriction.blocksWindCharge(
                activeSet.get(), match.orElseThrow().sourceInside(),
                region.contains(launch));
        if (blocked) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDirectDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        RestrictionDecision decision = materialDecision(player,
                player.getInventory().getItemInMainHand().getType(),
                region.contains(event.getEntity().getLocation()));
        if (!decision.denied()) return;
        event.setCancelled(true);
        messages.denial(player, decision);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSuccessfulDirectDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || event.getFinalDamage() <= 0) return;
        RestrictionDecision decision = materialDecision(player,
                player.getInventory().getItemInMainHand().getType(),
                region.contains(event.getEntity().getLocation()));
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
            projectileLaunches.record(event.getProjectile().getUniqueId(),
                    player.getUniqueId(), decision,
                    System.nanoTime() + 5_000_000_000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunchFinalized(ProjectileLaunchEvent event) {
        projectileLaunches.finalizeLaunch(event.getEntity().getUniqueId(),
                        event.isCancelled())
                .ifPresent(completion -> completeSuccess(completion.playerId(),
                        event.getEntity().getServer().getPlayer(completion.playerId()),
                        completion.decision()));
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlideStart(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || !event.isGliding()) return;
        if (bypass(player) || !region.contains(player.getLocation())) return;
        if (activeSet.get().elytraGlidingAllowed()) return;
        event.setCancelled(true);
        messages.elytraUnavailable(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();
        if (bypass(player) || !region.contains(player.getLocation())) return;
        if (!activeSet.get().fireworkBoostBlocked()) return;
        event.setCancelled(true);
        event.setShouldConsume(false);
        messages.rocketUnavailable(player);
    }

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
        projectileLaunches.clear();
        automatedLaunches.clear();
        visualInsideState.clear();
    }

    public void clear() { clearTransientState(); }

    public void cleanup() {
        long now = System.nanoTime();
        projectileLaunches.cleanup(now);
        automatedLaunches.cleanup(org.bukkit.Bukkit.getCurrentTick(), now);
        lungeGate.cleanup();
        visualCooldowns.cleanup();
    }

    private void completeSuccess(UUID playerId, Player player, RestrictionDecision decision) {
        restrictions.success(playerId, decision);
        if (player != null && region.contains(player.getLocation()))
            visualCooldowns.apply(player, decision);
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
        return RestrictionTarget.isSpear(item.getType())
                && item.containsEnchantment(Enchantment.LUNGE);
    }

    private LungeVelocityGate.Vec3 vec(Vector vector) {
        return new LungeVelocityGate.Vec3(vector.getX(), vector.getY(), vector.getZ());
    }

    private AutomatedProjectileLaunchTracker.Vec3 automatedVec(Vector vector) {
        return new AutomatedProjectileLaunchTracker.Vec3(
                vector.getX(), vector.getY(), vector.getZ());
    }

    private AutomatedProjectileLaunchTracker.Vec3 automatedVec(Location location) {
        return new AutomatedProjectileLaunchTracker.Vec3(
                location.getX(), location.getY(), location.getZ());
    }

    private boolean sameBlock(Location from, Location to) {
        return from.getWorld() != null && to.getWorld() != null
                && from.getWorld().getUID().equals(to.getWorld().getUID())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
