package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.util.Compat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EndIslandListener implements Listener {

    private static final String CRYSTAL_META = "mg_player_crystal";
    private static final double TNT_BASE = 4.0D;
    private static final double CRYSTAL_BASE = 6.0D;
    private static final double ANCHOR_BASE = 5.0D;
    private static final double BED_BASE = 5.0D;
    private static final String DRAGON_META = "mg_dragon_crystal";

    private final MaceGuardPlugin plugin;
    private final Map<UUID, PendingCrystal> recentCrystals = new ConcurrentHashMap<>();
    private final Map<String, Long> dragonSpotCooldown = new ConcurrentHashMap<>();

    public EndIslandListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isMainIsland(Location loc) {
        return plugin.isOnEndIsland(loc);
    }

    // --- Mace blocking ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!plugin.isEndIslandEnabled() || !plugin.isEndIslandBlockMaces()) return;
        if (!(event.getDamager() instanceof Player p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR || !Compat.isMace(hand.getType())) return;
        if (!isMainIsland(event.getEntity().getLocation())) return;
        event.setCancelled(true);
        event.setDamage(0.0D);
    }

    // --- Track player-placed crystals ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalSpawn(EntitySpawnEvent event) {
        if (!isEnderCrystal(event.getEntityType())) return;
        if (!isMainIsland(event.getLocation())) return;
        long now = System.currentTimeMillis();
        recentCrystals.values().removeIf(pc -> now - pc.ts > 5000L);
        for (PendingCrystal pc : recentCrystals.values()) {
            if (event.getLocation().getWorld() == pc.loc.getWorld()
                    && event.getLocation().distanceSquared(pc.loc) <= 9.0D) {
                event.getEntity().setMetadata(CRYSTAL_META, new FixedMetadataValue(plugin, true));
                if (pc.dragon) {
                    event.getEntity().setMetadata(DRAGON_META, new FixedMetadataValue(plugin, true));
                }
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMainIsland(event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null)) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.END_CRYSTAL) return;
        Location clicked = event.getClickedBlock().getLocation();
        Location spawnLoc = clicked.add(0.5, 1.0, 0.5);
        boolean dragon = isDragonRespawnSpot(event.getClickedBlock());
        recentCrystals.put(event.getPlayer().getUniqueId(), new PendingCrystal(spawnLoc, System.currentTimeMillis(), dragon));
    }

    // Block placement when power is zero
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceExplosive(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMainIsland(event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null)) return;
        ItemStack item = event.getItem();
        if (item == null) return;
        String name = item.getType().name();
        if (name.equals("END_CRYSTAL")) {
            Block base = event.getClickedBlock();
            if (!isDragonRespawnSpot(base)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cEnd crystals are disabled on the main island.");
                return;
            }
            String key = spotKey(base);
            long now = System.currentTimeMillis();
            Long last = dragonSpotCooldown.get(key);
            if (last != null && now - last < 60_000L) {
                long remain = (60_000L - (now - last)) / 1000L;
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThat crystal spot is on cooldown (" + remain + "s).");
                return;
            }
            dragonSpotCooldown.put(key, now);
        } else if (name.equals("TNT_MINECART")) {
            // Fully block TNT minecart placement on the island.
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTNT minecarts are disabled on the main island.");
        } else if (name.equals("TNT")) {
            MaceGuardPlugin.ExplosiveRule rule = plugin.getExplosiveRule("tnt");
            if (rule != null && rule.isZero()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cTNT is disabled on the main island.");
            }
        }
    }

    // Block all crystal damage on the main island (prevents player punching to explode)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!isMainIsland(event.getEntity().getLocation())) return;
        if (!isEnderCrystal(event.getEntity().getType())) return;
        event.setCancelled(true);
    }

    // --- Explosion tuning (TNT, TNT minecart, end crystals) ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (!isMainIsland(entity.getLocation())) return;

        EntityType type = entity.getType();
        MaceGuardPlugin.ExplosiveRule rule = ruleFor(type);
        if (isEnderCrystal(type)) {
            event.setCancelled(true);
            entity.remove();
            return;
        }
        if (rule.isZero()) {
            event.setCancelled(true);
            entity.remove();
            return;
        }

        double base;
        if (isPrimedTnt(type) || isTntMinecart(type)) {
            base = TNT_BASE;
        } else if (isEnderCrystal(type)) {
            base = CRYSTAL_BASE;
        } else {
            base = event.getRadius();
        }
        float scaled = (float) (base * rule.clampedScale());
        event.setRadius(scaled);
        event.setFire(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) return;
        if (!isMainIsland(entity.getLocation())) return;

        EntityType type = entity.getType();
        MaceGuardPlugin.ExplosiveRule rule = ruleFor(type);

        if (isEnderCrystal(type)) {
            event.blockList().clear();
            event.setCancelled(true);
            entity.remove();
            return;
        }
        if (rule.isZero()) {
            event.blockList().clear(); // no block damage
            event.setCancelled(true);  // no entity damage
            entity.remove();
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!isMainIsland(event.getBlock().getLocation())) return;
        event.blockList().clear();
        event.setCancelled(true);
    }

    // Extra guard to stop lingering explosion damage from crystals/minecarts/TNT at 0 power
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION &&
                event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager == null) return;
        if (!isMainIsland(damager.getLocation())) return;
        EntityType type = damager.getType();
        MaceGuardPlugin.ExplosiveRule rule = ruleFor(type);
        if (isEnderCrystal(type) || rule.isZero()) {
            event.setCancelled(true);
            if (!damager.isDead()) damager.remove();
        }
    }

    private boolean isPlayerPlacedCrystal(Entity entity) {
        List<MetadataValue> list = entity.getMetadata(CRYSTAL_META);
        for (MetadataValue mv : list) {
            if (mv.asBoolean()) return true;
        }
        return false;
    }

    // --- Bed / respawn anchor overrides ---
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBedOrAnchor(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        boolean isBed = type.name().endsWith("_BED");
        boolean isAnchor = type == Material.RESPAWN_ANCHOR;
        if (!isBed && !isAnchor) return;
        if (!isMainIsland(block.getLocation())) return;

        MaceGuardPlugin.ExplosiveRule rule = plugin.getExplosiveRule(isBed ? "bed" : "respawn_anchor");
        if (rule == null) return;

        event.setCancelled(true); // prevent vanilla explosion
        if (rule.isZero()) {
            if (isBed && plugin.isEndIslandFunBedSleep()) {
                // Silly behavior: force sleep animation even if far.
                Player p = event.getPlayer();
                if (p != null) {
                    Location bedLoc = block.getLocation().add(0.5, 0.6, 0.5);
                    p.teleport(bedLoc);
                    p.sleep(bedLoc, true);
                    p.sendMessage("§dSweet dreams in the void.");
                    p.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (p.isSleeping()) p.wakeup(false);
                    }, 40L);
                }
            }
            return;
        }

        double base = isAnchor ? ANCHOR_BASE : BED_BASE;
        float power = (float) (base * rule.clampedScale());
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        block.setType(Material.AIR, false);
        loc.getWorld().createExplosion(loc, power, false, true);
    }

    private MaceGuardPlugin.ExplosiveRule ruleFor(EntityType type) {
        if (isPrimedTnt(type)) return nonNullRule(plugin.getExplosiveRule("tnt"));
        if (isTntMinecart(type)) return nonNullRule(plugin.getExplosiveRule("tnt_minecart"));
        if (isEnderCrystal(type)) return nonNullRule(plugin.getExplosiveRule("end_crystal"));
        return new MaceGuardPlugin.ExplosiveRule(100.0D, false); // default pass-through for others
    }

    private MaceGuardPlugin.ExplosiveRule nonNullRule(MaceGuardPlugin.ExplosiveRule r) {
        return r != null ? r : new MaceGuardPlugin.ExplosiveRule(0.0D, false);
    }

    private boolean isEnderCrystal(EntityType type) {
        return type != null && type.name().equalsIgnoreCase("ENDER_CRYSTAL");
    }

    private boolean isTntMinecart(EntityType type) {
        return type != null && type.name().equalsIgnoreCase("MINECART_TNT");
    }

    private boolean isPrimedTnt(EntityType type) {
        return type != null && (type.name().equalsIgnoreCase("PRIMED_TNT") || type.name().equalsIgnoreCase("TNT"));
    }

    private static final class PendingCrystal {
        final Location loc;
        final long ts;
        final boolean dragon;
        PendingCrystal(Location loc, long ts, boolean dragon) {
            this.loc = loc;
            this.ts = ts;
            this.dragon = dragon;
        }
    }

    private boolean isDragonCrystal(Entity entity) {
        if (entity == null) return false;
        List<MetadataValue> list = entity.getMetadata(DRAGON_META);
        for (MetadataValue mv : list) {
            if (mv.asBoolean()) return true;
        }
        return false;
    }

    private boolean isDragonRespawnSpot(Block base) {
        if (base == null) return false;
        if (base.getWorld() == null || base.getWorld().getEnvironment() != org.bukkit.World.Environment.THE_END) return false;
        // Near the exit portal (within radius 6) and on bedrock frame
        double dx = base.getX() + 0.5;
        double dz = base.getZ() + 0.5;
        if ((dx * dx + dz * dz) > 36.0D) return false;
        if (base.getType() != Material.BEDROCK) return false;
        // Only allow the four cardinal spots at distance 3 from origin on the bedrock frame
        int x = base.getX();
        int z = base.getZ();
        return (Math.abs(x) == 3 && z == 0) || (Math.abs(z) == 3 && x == 0);
    }

    private String spotKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }
}
