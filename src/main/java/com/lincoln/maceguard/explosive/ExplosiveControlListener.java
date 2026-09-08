package com.lincoln.maceguard.explosive;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.temporary.TemporaryBlock;
import com.lincoln.maceguard.temporary.TemporaryBlockAdmissionJournal;
import com.lincoln.maceguard.temporary.TemporaryBlockService;
import com.lincoln.maceguard.warzone.runtime.WarzoneModule;
import com.lincoln.maceguard.warzone.runtime.WarzoneRuntime;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Enforces explosive controls and the narrowly-scoped Warzone carts grant. */
public final class ExplosiveControlListener implements Listener {
    private static final Set<Material> CART_RAILS = EnumSet.of(
            Material.RAIL,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL);

    private final MaceGuardPlugin plugin;
    private final WorldGuardQueryService worldGuard;
    private final Predicate<Entity> windBurstSource;
    private final WarzoneModule warzone;
    private final TemporaryBlockService temporary;
    private final TemporaryBlockAdmissionJournal admissions;
    private final CartArtifactGenerationStore cartGenerations;
    private final NamespacedKey cartGenerationKey;
    private final Set<UUID> cartPrimeClearBeforeWorldGuard = new HashSet<>();
    private final Set<UUID> cartExplosionClearBeforeWorldGuard = new HashSet<>();
    private boolean lifecycleActive;

    public ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard,
                                    WarzoneModule warzone, TemporaryBlockService temporary,
                                    TemporaryBlockAdmissionJournal admissions) {
        this(plugin, worldGuard, ExplosiveControlListener::isWindBurstSource, warzone, temporary,
                admissions,
                new CartArtifactGenerationStore(plugin.getDataFolder().toPath().resolve("state")
                        .resolve("warzone-cart-generation.txt"), plugin.getLogger()),
                new NamespacedKey(plugin, "warzone-cart-generation"));
    }

    /** Lightweight constructor retained for isolated unit tests of non-lifecycle behavior. */
    ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard,
                             Predicate<Entity> windBurstSource) {
        this(plugin, worldGuard, windBurstSource, null, null, null, null, null);
    }

    ExplosiveControlListener(MaceGuardPlugin plugin, WorldGuardQueryService worldGuard,
                             Predicate<Entity> windBurstSource, WarzoneModule warzone,
                             TemporaryBlockService temporary,
                             TemporaryBlockAdmissionJournal admissions,
                             CartArtifactGenerationStore cartGenerations,
                             NamespacedKey cartGenerationKey) {
        this.plugin = plugin;
        this.worldGuard = worldGuard;
        this.windBurstSource = windBurstSource;
        this.warzone = warzone;
        this.temporary = temporary;
        this.admissions = admissions;
        this.cartGenerations = cartGenerations;
        this.cartGenerationKey = cartGenerationKey;
    }

    /**
     * Called only after this runtime becomes authoritative. It fences carts from any retired
     * runtime/restart, binds exact CARTS-removal cleanup, and clears stale loaded artifacts.
     */
    public void activateLifecycle() {
        if (lifecycleActive || cartGenerations == null) return;
        lifecycleActive = true;
        if (warzone != null) warzone.bindCartCleanup(this::onCartsEnded);
        cartGenerations.advance();
        cleanupLoadedTaggedCarts(true);
        if (!cartsEffectActive()) clearTrackedRails();
    }

    /**
     * WorldGuard represents region decisions as delegate events. Pre-allowing those delegates is
     * deliberately narrower than uncancelling the original Bukkit event: unrelated Bukkit
     * protection plugins retain their own cancellation authority.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartBlockPlace(
            com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent event) {
        if (event.getOriginalEvent() instanceof BlockPlaceEvent original) {
            if (original.isCancelled()) return;
            Location location = original.getBlockPlaced().getLocation();
            if (isCartRail(original.getBlockPlaced().getType()) && cartModifierActive(location)
                    && !worldGuard.blockPlaceAllowed(location, original.getPlayer())) {
                event.setAllowed(true);
            }
            return;
        }
        if (event.getOriginalEvent() instanceof BlockIgniteEvent original) {
            if (original.isCancelled()
                    || original.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                    || original.getPlayer() == null) return;
            Location location = original.getBlock().getLocation();
            if (cartModifierActive(location)
                    && !worldGuard.lighterAllowed(location, original.getPlayer())) {
                event.setAllowed(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartBlockBreak(
            com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent event) {
        if (!(event.getOriginalEvent() instanceof BlockBreakEvent original)) return;
        if (!isCartRail(original.getBlock().getType())) return;
        if (isTrackedCartRail(original.getBlock())) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartUseBlock(
            com.sk89q.worldguard.bukkit.event.block.UseBlockEvent event) {
        if (event.getOriginalEvent() instanceof PlayerInteractEvent original)
            preAllowWorldGuardCartInteraction(event, original);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartUseItem(
            com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent event) {
        if (event.getOriginalEvent() instanceof PlayerInteractEvent original)
            preAllowWorldGuardCartInteraction(event, original);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartSpawn(
            com.sk89q.worldguard.bukkit.event.entity.SpawnEntityEvent event) {
        if (event.getEffectiveType() != EntityType.TNT_MINECART) return;
        Player player = event.getCause().getFirstPlayer();
        if (player == null || !cartModifierActive(event.getTarget()) || !cartPlacementReady()) return;
        if (!worldGuard.vehiclePlaceAllowed(event.getTarget(), player)) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartUseEntity(
            com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent event) {
        if (event.getEntity().getType() != EntityType.TNT_MINECART
                || !(event.getOriginalEvent() instanceof PlayerInteractEntityEvent original)
                || original.isCancelled() || !cartModifierActive(event.getTarget())) return;
        EquipmentSlot hand = original.getHand();
        ItemStack item = hand == EquipmentSlot.HAND
                ? original.getPlayer().getInventory().getItemInMainHand()
                : original.getPlayer().getInventory().getItemInOffHand();
        if (item.getType() == Material.FLINT_AND_STEEL
                && !worldGuard.lighterAllowed(event.getTarget(), original.getPlayer())) {
            event.setAllowed(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartDamageEntity(
            com.sk89q.worldguard.bukkit.event.entity.DamageEntityEvent event) {
        if (event.getEntity().getType() != EntityType.TNT_MINECART
                || !(event.getOriginalEvent() instanceof VehicleDamageEvent original)
                || original.isCancelled()
                || !(original.getAttacker() instanceof Player player)
                || !cartModifierActive(event.getTarget())) return;
        if (!worldGuard.vehicleDestroyAllowed(event.getTarget(), player)) event.setAllowed(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWorldGuardCartDestroyEntity(
            com.sk89q.worldguard.bukkit.event.entity.DestroyEntityEvent event) {
        if (event.getEntity().getType() != EntityType.TNT_MINECART
                || !(event.getOriginalEvent() instanceof VehicleDestroyEvent original)
                || original.isCancelled()
                || !(original.getAttacker() instanceof Player player)
                || !cartModifierActive(event.getTarget())) return;
        if (!worldGuard.vehicleDestroyAllowed(event.getTarget(), player)) event.setAllowed(true);
    }

    private void preAllowWorldGuardCartInteraction(
            com.sk89q.worldguard.bukkit.event.DelegateEvent delegate,
            PlayerInteractEvent original) {
        if (original.isCancelled()) return;
        ItemStack item = original.getItem();
        Block clicked = original.getClickedBlock();
        if (item == null || clicked == null) return;

        if (item.getType() == Material.TNT_MINECART && isCartRail(clicked.getType())
                && cartModifierActive(clicked.getLocation()) && cartPlacementReady()
                && !worldGuard.vehiclePlaceAllowed(clicked.getLocation(), original.getPlayer())) {
            delegate.setAllowed(true);
            return;
        }

        Location fire = clicked.getRelative(original.getBlockFace()).getLocation();
        if (item.getType() == Material.FLINT_AND_STEEL && cartModifierActive(fire)
                && !worldGuard.lighterAllowed(fire, original.getPlayer())) {
            delegate.setAllowed(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.TNT
                && denied(event.getBlockPlaced().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Rails granted by CARTS must be durably owned before the placement is allowed to remain. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCartRailPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (!isCartRail(placed.getType()) || !cartModifierActive(placed.getLocation())) return;
        if (temporary == null) return;
        String original = event.getBlockReplacedState().getBlockData().getAsString(true);
        boolean tracked = admissions == null
                ? temporary.track(placed, original, Long.MAX_VALUE, true)
                : admissions.track(temporary, placed, original, Long.MAX_VALUE, true);
        if (tracked) return;
        event.setCancelled(true);
        placed.setBlockData(event.getBlockReplacedState().getBlockData(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCartRailBreak(BlockBreakEvent event) {
        if (!isCartRail(event.getBlock().getType()) || temporary == null) return;
        temporary.discardMatching(entry -> entry.isKind(TemporaryBlock.Kind.CART_RAIL)
                && sameCoordinate(entry, event.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        EntityType type = event.getEntityType();
        if (type == EntityType.TNT_MINECART && event.getPlayer() != null
                && cartModifierActive(event.getEntity().getLocation())) {
            if (!cartPlacementReady()) event.setCancelled(true);
            return;
        }
        if (event.isCancelled()) return;
        if ((type == EntityType.END_CRYSTAL || type == EntityType.TNT_MINECART)
                && denied(event.getEntity().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntMinecartCreate(VehicleCreateEvent event) {
        if (event.getVehicle().getType() != EntityType.TNT_MINECART) return;
        if (cartModifierActive(event.getVehicle().getLocation())) {
            if (!cartPlacementReady()) event.setCancelled(true);
            return;
        }
        if (!event.isCancelled() && denied(event.getVehicle().getLocation(), null)) event.setCancelled(true);
    }

    /** Tag every accepted TNT minecart created while CARTS is active, not only player placements. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedTntMinecartCreate(VehicleCreateEvent event) {
        Entity cart = event.getVehicle();
        if (cart.getType() != EntityType.TNT_MINECART || !cartModifierActive(cart.getLocation())) return;
        if (!cartPlacementReady() || cartGenerationKey == null) {
            cart.remove();
            return;
        }
        cart.getPersistentDataContainer().set(cartGenerationKey, PersistentDataType.LONG,
                cartGenerations.generation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCartFireSpread(BlockSpreadEvent event) {
        Material source = event.getSource().getType();
        if ((source == Material.FIRE || source == Material.SOUL_FIRE)
                && (cartModifierActive(event.getSource().getLocation())
                || cartModifierActive(event.getBlock().getLocation()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCartFireBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (cartModifierActive(event.getBlock().getLocation())
                || source != null && cartModifierActive(source.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnchorUse(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR
                && denied(event.getClickedBlock().getLocation(), event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (event.getEntityType() == EntityType.END_CRYSTAL
                && denied(event.getEntity().getLocation(), player(event.getDamager()))) event.setCancelled(true);
    }

    /** Capture cancellations that already exist before WorldGuard's HIGH-priority global TNT hook. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onCartPrimeBeforeWorldGuard(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() != EntityType.TNT_MINECART || !cartModifierActive(entity.getLocation())) return;
        rememberClear(cartPrimeClearBeforeWorldGuard, entity.getUniqueId(), !event.isCancelled());
    }

    /**
     * WorldGuard is a hard dependency and registers before MaceGuard. Its 7.0.17 global TNT hook is
     * also HIGH priority, so this HIGH handler runs after it while still leaving HIGHEST/MONITOR
     * protection listeners able to veto the detonation. Never reopen an event already cancelled at
     * NORMAL priority.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onCartPrimeWorldGuardBypass(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() != EntityType.TNT_MINECART || !cartModifierActive(entity.getLocation())) return;
        boolean wasClear = cartPrimeClearBeforeWorldGuard.remove(entity.getUniqueId());
        if (event.isCancelled() && wasClear
                && worldGuard.tntExplosionsGloballyBlocked(entity.getLocation())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrime(ExplosionPrimeEvent event) {
        Location location = event.getEntity().getLocation();
        if (isCartExplosion(event.getEntity(), location)) {
            cartPrimeClearBeforeWorldGuard.remove(event.getEntity().getUniqueId());
            return;
        }
        if (event.isCancelled()) return;
        if (isWindCharge(event.getEntityType()) || windBurstSource.test(event.getEntity())) return;
        if (denied(location, null)) event.setCancelled(true);
    }

    /**
     * Empty cart terrain damage before WorldGuard converts the block list into bulk block-break
     * delegate checks. Entity/player damage is unchanged.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCartExplosionPrepare(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!isCartExplosion(entity, event.getLocation())) return;
        if (!event.isCancelled()) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onCartExplosionBeforeWorldGuard(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!isCartExplosion(entity, event.getLocation())) return;
        rememberClear(cartExplosionClearBeforeWorldGuard, entity.getUniqueId(), !event.isCancelled());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onCartExplosionWorldGuardBypass(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!isCartExplosion(entity, event.getLocation())) return;
        boolean wasClear = cartExplosionClearBeforeWorldGuard.remove(entity.getUniqueId());
        if (event.isCancelled() && wasClear
                && worldGuard.tntExplosionsGloballyBlocked(event.getLocation())) {
            event.setCancelled(false);
        }
        if (!event.isCancelled()) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (isCartExplosion(event.getEntity(), event.getLocation())) {
            cartExplosionClearBeforeWorldGuard.remove(event.getEntity().getUniqueId());
            if (!event.isCancelled()) event.blockList().clear();
            return;
        }
        if (event.isCancelled()) return;
        if (isWindCharge(event.getEntityType()) || windBurstSource.test(event.getEntity())) return;
        if (denied(event.getLocation(), null)) event.setCancelled(true);
        else event.blockList().removeIf(block -> denied(block.getLocation(), null));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (denied(event.getBlock().getLocation(), null)) event.setCancelled(true);
        else event.blockList().removeIf(block -> denied(block.getLocation(), null));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        Entity direct = event.getDamageSource().getDirectEntity();
        if (isWindCharge(direct) || windBurstSource.test(direct) || cartExplosionSource(direct)) return;
        if ((event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)
                && denied(event.getEntity().getLocation(), null)) event.setCancelled(true);
    }

    /** Remove stale tagged carts as their previously unloaded chunks become available. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!lifecycleActive || cartGenerations == null || cartGenerationKey == null) return;
        boolean cartsActive = cartsEffectActive();
        for (Entity entity : event.getChunk().getEntities())
            removeIfStaleTaggedCart(entity, cartsActive);
    }

    /** Exact callback from WarzoneRuntime for a CARTS true -> false transition. */
    void onCartsEnded() {
        if (cartGenerations != null) cartGenerations.advance();
        clearTrackedRails();
        cleanupLoadedTaggedCarts(true);
    }

    private void clearTrackedRails() {
        if (temporary == null) return;
        temporary.clearMatching(entry -> entry.warzoneOwned()
                && entry.isKind(TemporaryBlock.Kind.CART_RAIL));
    }

    private void cleanupLoadedTaggedCarts(boolean force) {
        if (cartGenerations == null || cartGenerationKey == null) return;
        java.util.List<World> worlds = plugin.getServer().getWorlds();
        if (worlds == null) return;
        boolean cartsActive = cartsEffectActive();
        for (World world : worlds) {
            for (Entity entity : world.getEntities()) {
                if (force) removeTaggedCart(entity);
                else removeIfStaleTaggedCart(entity, cartsActive);
            }
        }
    }

    private void removeIfStaleTaggedCart(Entity entity, boolean cartsActive) {
        if (entity.getType() != EntityType.TNT_MINECART) return;
        Long generation = taggedGeneration(entity);
        if (generation == null) return;
        if (!cartGenerations.healthy() || !cartsActive
                || generation.longValue() != cartGenerations.generation()) entity.remove();
    }

    private void removeTaggedCart(Entity entity) {
        if (entity.getType() == EntityType.TNT_MINECART && taggedGeneration(entity) != null)
            entity.remove();
    }

    private Long taggedGeneration(Entity entity) {
        if (cartGenerationKey == null) return null;
        return entity.getPersistentDataContainer().get(cartGenerationKey, PersistentDataType.LONG);
    }

    private boolean cartPlacementReady() {
        return cartGenerations == null || cartGenerations.healthy() || cartGenerations.ensurePersisted();
    }

    private boolean isTrackedCartRail(Block block) {
        return temporary != null && temporary.countMatching(entry -> entry.warzoneOwned()
                && entry.isKind(TemporaryBlock.Kind.CART_RAIL)
                && sameCoordinate(entry, block)) > 0;
    }

    private static boolean sameCoordinate(TemporaryBlock entry, Block block) {
        return entry.worldUuid().equals(block.getWorld().getUID().toString())
                && entry.x() == block.getX() && entry.y() == block.getY()
                && entry.z() == block.getZ();
    }

    private static void rememberClear(Set<UUID> clearEvents, UUID id, boolean clear) {
        if (clear) clearEvents.add(id);
        else clearEvents.remove(id);
    }

    static boolean isWindCharge(EntityType type) {
        return type == EntityType.WIND_CHARGE || type == EntityType.BREEZE_WIND_CHARGE;
    }

    private static boolean isWindCharge(Entity entity) {
        return entity != null && isWindCharge(entity.getType());
    }

    static boolean isWindBurstSource(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        var held = player.getInventory().getItemInMainHand();
        return isWindBurstMace(held.getType(), held.containsEnchantment(Enchantment.WIND_BURST));
    }

    static boolean isWindBurstMace(Material material, boolean hasWindBurst) {
        return material == Material.MACE && hasWindBurst;
    }

    static boolean isCartRail(Material material) {
        return CART_RAILS.contains(material);
    }

    private boolean cartExplosionSource(Entity entity) {
        return entity != null && isCartExplosion(entity, entity.getLocation());
    }

    private boolean isCartExplosion(Entity entity, Location location) {
        return entity != null && entity.getType() == EntityType.TNT_MINECART
                && cartModifierActive(location);
    }

    private boolean cartsEffectActive() {
        WarzoneRuntime runtime = warzoneRuntime();
        return runtime != null && runtime.rotations().active().cartsAllowed();
    }

    private boolean cartModifierActive(Location location) {
        WarzoneRuntime runtime = warzoneRuntime();
        return runtime != null && runtime.appliesAt(location)
                && runtime.rotations().active().cartsAllowed();
    }

    private WarzoneRuntime warzoneRuntime() {
        if (warzone != null) return warzone.runtime();
        if (!plugin.isFeatureEnabled()) return null;
        var pluginRuntime = plugin.runtime();
        if (pluginRuntime == null || pluginRuntime.warzone() == null) return null;
        return pluginRuntime.warzone().runtime();
    }

    private boolean denied(Location location, Player player) {
        return plugin.isFeatureEnabled() && worldGuard.explosivesDenied(location, player);
    }

    private Player player(Entity entity) {
        return entity instanceof Player value ? value : null;
    }
}
