package com.lincoln.maceguard.adapter.bukkit.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.core.model.EndIslandExplosiveSettings;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EndIslandListener implements Listener {
    private static final double TNT_BASE = 4.0D;
    private static final double BED_BASE = 5.0D;
    private static final double ANCHOR_BASE = 5.0D;
    private static final double DISABLED_SCALE = 0.0D;
    private static final double DRAGON_SPOT_RADIUS_SQUARED = 36.0D;
    private static final long DRAGON_SPOT_COOLDOWN_MILLIS = 60_000L;

    private final MaceGuardPlugin plugin;
    private final Map<String, Long> dragonSpotCooldowns = new ConcurrentHashMap<>();

    public EndIslandListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || (!settings.blockMaces() && !settings.blockSpears())) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!isBlockedEndIslandWeapon(player, settings)) {
            return;
        }
        if (!isMainIsland(event.getEntity().getLocation(), settings)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpearProjectileDamage(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || !settings.blockSpears()) {
            return;
        }
        if (!Compat.isSpearEntity(event.getDamager().getType().name()) || !isMainIsland(event.getEntity().getLocation(), settings)) {
            return;
        }
        event.setCancelled(true);
        event.getDamager().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpearUse(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || !settings.blockSpears()) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isSpear(item)) {
            return;
        }
        Location location = event.getClickedBlock() == null ? event.getPlayer().getLocation() : event.getClickedBlock().getLocation();
        if (!isMainIsland(location, settings)) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpearLaunch(ProjectileLaunchEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || !settings.blockSpears()) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player player)) {
            return;
        }
        if (!launchedSpear(event, player)) {
            return;
        }
        if (!isMainIsland(player.getLocation(), settings) && !isMainIsland(event.getEntity().getLocation(), settings)) {
            return;
        }
        event.setCancelled(true);
        event.getEntity().remove();
    }

    private boolean launchedSpear(ProjectileLaunchEvent event, Player player) {
        return Compat.isSpearEntity(event.getEntity().getType().name()) || isHoldingSpear(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSpearLunge(PlayerRiptideEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || !settings.blockSpears() || !isSpear(event.getItem()) || !isMainIsland(event.getPlayer().getLocation(), settings)) {
            return;
        }
        event.getPlayer().setVelocity(event.getPlayer().getVelocity().zero());
        event.getPlayer().getServer().getScheduler().runTask(plugin, () -> event.getPlayer().setVelocity(event.getPlayer().getVelocity().zero()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalPlace(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || !isMainIsland(event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null, settings)) {
            return;
        }

        Material type = event.getItem().getType();
        if (type == Material.END_CRYSTAL) {
            handleCrystalPlace(event);
            return;
        }

        if (type == Material.TNT_MINECART) {
            cancelWithMessage(event, "\u00A7cTNT minecarts are disabled on the main island.");
            return;
        }

        if (type == Material.TNT && settings.explosives().tntPercent() <= DISABLED_SCALE) {
            cancelWithMessage(event, "\u00A7cTNT is disabled on the main island.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (!isEnderCrystal(event.getEntity().getType()) || !isMainIsland(event.getEntity().getLocation(), plugin.runtime().settings().endIsland())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!isMainIsland(event.getEntity().getLocation(), settings)) {
            return;
        }

        EntityType type = event.getEntity().getType();
        if (isEnderCrystal(type)) {
            event.setCancelled(true);
            event.getEntity().remove();
            return;
        }
        if (isPrimedTnt(type)) {
            applyExplosionScale(event, settings.explosives().scaleForTnt(), TNT_BASE);
            return;
        }
        if (isTntMinecart(type)) {
            applyExplosionScale(event, settings.explosives().scaleForTntMinecart(), TNT_BASE);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!isMainIsland(event.getLocation(), settings)) {
            return;
        }

        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }

        if (isEnderCrystal(entity.getType())) {
            event.blockList().clear();
            event.setCancelled(true);
            entity.remove();
            return;
        }

        if (disabledTntExplosion(entity.getType(), settings.explosives())) {
            event.blockList().clear();
            event.setCancelled(true);
            entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (isMainIsland(event.getBlock().getLocation(), plugin.runtime().settings().endIsland())) {
            event.blockList().clear();
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageByEntityEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager == null || !isMainIsland(damager.getLocation(), plugin.runtime().settings().endIsland())) {
            return;
        }

        if (isEnderCrystal(damager.getType())) {
            event.setCancelled(true);
            damager.remove();
            return;
        }

        EndIslandExplosiveSettings explosives = plugin.runtime().settings().endIsland().explosives();
        if (disabledTntExplosion(damager.getType(), explosives)) {
            event.setCancelled(true);
            damager.remove();
        }
    }

    private boolean disabledTntExplosion(EntityType type, EndIslandExplosiveSettings explosives) {
        return (isPrimedTnt(type) && explosives.tntPercent() <= DISABLED_SCALE)
                || (isTntMinecart(type) && explosives.tntMinecartPercent() <= DISABLED_SCALE);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBedOrAnchor(PlayerInteractEvent event) {
        if (!plugin.isFeatureEnabled()) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        EndIslandSettings settings = plugin.runtime().settings().endIsland();
        if (!settings.enabled() || !isMainIsland(block.getLocation(), settings)) {
            return;
        }

        Material type = block.getType();
        boolean isBed = type.name().endsWith("_BED");
        boolean isAnchor = type == Material.RESPAWN_ANCHOR;
        if (!isBed && !isAnchor) {
            return;
        }

        event.setCancelled(true);
        double scale = isAnchor ? settings.explosives().scaleForRespawnAnchor() : settings.explosives().scaleForBed();
        if (scale <= DISABLED_SCALE) {
            handleDisabledBedExplosion(event.getPlayer(), block, isBed, settings);
            return;
        }

        float power = (float) ((isAnchor ? ANCHOR_BASE : BED_BASE) * scale);
        createConfiguredExplosion(block, power);
    }

    private void handleCrystalPlace(PlayerInteractEvent event) {
        Block base = event.getClickedBlock();
        if (!isDragonRespawnSpot(base)) {
            cancelWithMessage(event, "\u00A7cEnd crystals are disabled on the main island except for dragon respawn spots.");
            return;
        }
        long remainingSeconds = dragonSpotCooldownSeconds(base);
        if (remainingSeconds > 0) {
            cancelWithMessage(event, "\u00A7cThat dragon respawn spot is on cooldown (" + remainingSeconds + "s).");
            return;
        }
        dragonSpotCooldowns.put(spotKey(base), System.currentTimeMillis());
    }

    private long dragonSpotCooldownSeconds(Block base) {
        Long lastPlaced = dragonSpotCooldowns.get(spotKey(base));
        if (lastPlaced == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - lastPlaced;
        return elapsed < DRAGON_SPOT_COOLDOWN_MILLIS ? (DRAGON_SPOT_COOLDOWN_MILLIS - elapsed) / 1000L : 0L;
    }

    private void cancelWithMessage(PlayerInteractEvent event, String message) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(message);
    }

    private void handleDisabledBedExplosion(Player player, Block block, boolean bed, EndIslandSettings settings) {
        if (bed && settings.funBedSleep()) {
            Location sleepLocation = block.getLocation().add(0.5D, 0.6D, 0.5D);
            player.teleport(sleepLocation);
            player.sleep(sleepLocation, true);
            player.sendMessage("\u00A7dSweet dreams in the void.");
            player.getServer().getScheduler().runTaskLater(plugin, () -> wakeSleepingPlayer(player), 40L);
        }
    }

    private void wakeSleepingPlayer(Player player) {
        if (player.isSleeping()) {
            player.wakeup(false);
        }
    }

    private void createConfiguredExplosion(Block block, float power) {
        Location location = block.getLocation().add(0.5D, 0.5D, 0.5D);
        block.setType(Material.AIR, false);
        location.getWorld().createExplosion(location, power, false, true);
    }

    private boolean isMainIsland(Location location, EndIslandSettings settings) {
        if (!settings.enabled() || location == null || location.getWorld() == null || location.getWorld().getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        double x = location.getX();
        double z = location.getZ();
        return (x * x) + (z * z) <= (double) settings.islandRadius() * settings.islandRadius();
    }

    private boolean isBlockedEndIslandWeapon(Player player, EndIslandSettings settings) {
        return (settings.blockMaces() && isMace(player.getInventory().getItemInMainHand()))
                || (settings.blockSpears() && isSpear(player.getInventory().getItemInMainHand()));
    }

    private boolean isHoldingSpear(Player player) {
        return isSpear(player.getInventory().getItemInMainHand()) || isSpear(player.getInventory().getItemInOffHand());
    }

    private boolean isMace(ItemStack item) {
        return item != null && item.getType() != Material.AIR && Compat.isMace(item.getType());
    }

    private boolean isSpear(ItemStack item) {
        return item != null && Compat.isSpear(item.getType());
    }

    private boolean isEnderCrystal(EntityType type) {
        return type != null && type.name().equalsIgnoreCase("ENDER_CRYSTAL");
    }

    private boolean isPrimedTnt(EntityType type) {
        return type != null && (type.name().equalsIgnoreCase("PRIMED_TNT") || type.name().equalsIgnoreCase("TNT"));
    }

    private boolean isTntMinecart(EntityType type) {
        return type != null && type.name().equalsIgnoreCase("MINECART_TNT");
    }

    private void applyExplosionScale(ExplosionPrimeEvent event, double scale, double base) {
        if (scale <= DISABLED_SCALE) {
            event.setCancelled(true);
            event.getEntity().remove();
            return;
        }
        event.setRadius((float) (base * scale));
        event.setFire(false);
    }

    private boolean isDragonRespawnSpot(Block block) {
        if (block == null || block.getWorld() == null || block.getWorld().getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        if (block.getType() != Material.BEDROCK) {
            return false;
        }
        double x = block.getX() + 0.5D;
        double z = block.getZ() + 0.5D;
        if ((x * x) + (z * z) > DRAGON_SPOT_RADIUS_SQUARED) {
            return false;
        }
        int bx = block.getX();
        int bz = block.getZ();
        return (Math.abs(bx) == 3 && bz == 0) || (Math.abs(bz) == 3 && bx == 0);
    }

    private String spotKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
}
