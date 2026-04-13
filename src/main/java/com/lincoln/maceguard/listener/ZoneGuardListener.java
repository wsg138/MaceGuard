package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import com.lincoln.maceguard.region.Zone;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ZoneGuardListener implements Listener {

    private final MaceGuardPlugin plugin;

    // tracked edits for per-zone snapshot/AIR resets
    private final Map<String, Set<BlockPos>> changed = new ConcurrentHashMap<>();
    // TTL tasks per block (keyed by absolute block pos)
    private final Map<BlockPos, Integer> ttlTasks = new ConcurrentHashMap<>();

    record BlockPos(String world, int x, int y, int z) {}

    public ZoneGuardListener(MaceGuardPlugin plugin) { this.plugin = plugin; }

    // Use our own getter so we don't depend on a plugin.isDebug() helper
    private boolean isDebug() { return plugin.getConfig().getBoolean("debug", false); }

    private boolean creativeBypass(Player p) {
        return p != null && p.getGameMode() == GameMode.CREATIVE && p.hasPermission("maceguard.edit");
    }

    // -------------------- Placement/Break + permissioning --------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block b = event.getBlockPlaced();
        var loc = b.getLocation();

        var zonesAt = plugin.gameplay().zonesAt(loc);
        boolean inMaceZone = plugin.regions().isInAnyZone(loc);

        if (!zonesAt.isEmpty()) {
            if (isDeniedSpecial(b.getType(), zonesAt)) {
                if (!creativeBypass(player)) {
                    event.setCancelled(true);
                    msg(player, "You cannot place that here.");
                    return;
                }
            }
            if (!zonesAt.stream().allMatch(z -> z.allowAllPlace)) {
                boolean allowed = zonesAt.stream().anyMatch(z ->
                        z.allowAllPlace || z.allowedPlace.contains(b.getType().name()));
                if (!allowed && !creativeBypass(player)) {
                    event.setCancelled(true);
                    msg(player, "You cannot place that here.");
                    return;
                }
            }
        }

        if (inMaceZone && zonesAt.isEmpty() && !creativeBypass(player)) {
            event.setCancelled(true);
            msg(player, "You cannot build in the mace zone.");
            return;
        }

        // TTL now applies to any block allowed by the zone (or to all if allow_all_place=true)
        int ttl = maxTTL(zonesAt);
        if (ttl > 0 && shouldTTLClear(b.getType(), zonesAt)) {
            scheduleTTLClear(b, ttl, zonesAt);
        }

        for (Zone z : zonesAt) markChanged(z.name, b);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block b = event.getBlock();
        var loc = b.getLocation();

        var zonesAt = plugin.gameplay().zonesAt(loc);
        boolean inMaceZone = plugin.regions().isInAnyZone(loc);

        if (inMaceZone && zonesAt.isEmpty() && !creativeBypass(player)) {
            event.setCancelled(true);
            msg(player, "You cannot break blocks in the mace zone.");
            return;
        }

        boolean anyDisallow = zonesAt.stream().anyMatch(z -> !z.allowAllBreak &&
                !(z.allowAllPlace || z.allowedPlace.contains(b.getType().name())));
        if (anyDisallow && !creativeBypass(player)) {
            event.setCancelled(true);
            msg(player, "You cannot break that here.");
            return;
        }

        for (Zone z : zonesAt) markChanged(z.name, b);
    }

    // -------------------- Buckets & dispensers (target location rules) --------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        Player p = event.getPlayer();
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        var zonesAt = plugin.gameplay().zonesAt(target.getLocation());
        if (zonesAt.isEmpty()) return;

        Material placedMat = switch (event.getBucket()) {
            case WATER_BUCKET -> Material.WATER;
            case LAVA_BUCKET -> Material.LAVA;
            default -> null;
        };

        if (placedMat != null && !zonesAt.stream().allMatch(z -> z.allowAllPlace)) {
            boolean allowed = zonesAt.stream().anyMatch(z -> z.allowAllPlace || z.allowedPlace.contains(placedMat.name()));
            if (!allowed && !creativeBypass(p)) {
                event.setCancelled(true);
                msg(p, "That liquid isn’t allowed here.");
                return;
            }
        }

        int ttl = maxTTL(zonesAt);
        if (ttl > 0 && placedMat != null && shouldTTLClear(placedMat, zonesAt)) {
            // one tick later so the liquid exists
            plugin.getServer().getScheduler().runTask(plugin, () -> scheduleTTLClear(target, ttl, zonesAt));
        }

        for (Zone z : zonesAt) markChanged(z.name, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block dispenser = event.getBlock();
        BlockFace face = BlockFace.NORTH;
        if (dispenser.getBlockData() instanceof Directional dir) {
            face = dir.getFacing();
        }
        Block target = dispenser.getRelative(face);

        var zonesAt = plugin.gameplay().zonesAt(target.getLocation());
        if (zonesAt.isEmpty()) return;

        String matName = switch (event.getItem().getType()) {
            case WATER_BUCKET -> "WATER";
            case LAVA_BUCKET -> "LAVA";
            default -> event.getItem().getType().name();
        };

        if (!zonesAt.stream().allMatch(z -> z.allowAllPlace) &&
                zonesAt.stream().noneMatch(z -> z.allowAllPlace || z.allowedPlace.contains(matName))) {
            event.setCancelled(true);
            return;
        }

        int ttl = maxTTL(zonesAt);
        if (ttl > 0) {
            Material placed = switch (event.getItem().getType()) {
                case WATER_BUCKET -> Material.WATER;
                case LAVA_BUCKET -> Material.LAVA;
                default -> Material.matchMaterial(matName);
            };
            if (placed != null && shouldTTLClear(placed, zonesAt)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> scheduleTTLClear(target, ttl, zonesAt));
            }
        }

        for (Zone z : zonesAt) markChanged(z.name, target);
    }

    // -------------------- Specials (crystals/anchors, minecarts) --------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractCrystal(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null) return;

        if (item.getType() == Material.END_CRYSTAL || item.getType() == Material.RESPAWN_ANCHOR) {
            Block target = (event.getClickedBlock() != null)
                    ? event.getClickedBlock().getRelative(event.getBlockFace())
                    : event.getPlayer().getLocation().getBlock();
            var zonesAt = plugin.gameplay().zonesAt(target.getLocation());
            if (zonesAt.isEmpty()) return;

            if (isDeniedSpecial(item.getType(), zonesAt) && !creativeBypass(event.getPlayer())) {
                event.setCancelled(true);
                msg(event.getPlayer(), (item.getType() == Material.END_CRYSTAL ?
                        "End Crystals are disabled here." : "Respawn Anchors are disabled here."));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        EntityType type = event.getVehicle().getType();
        if (!isMinecart(type)) return;

        var zonesAt = plugin.gameplay().zonesAt(event.getVehicle().getLocation());
        if (zonesAt.isEmpty()) return;

        boolean denyHere = zonesAt.stream().anyMatch(z ->
                z.denyPlace.contains("MINECART") ||
                        z.denyPlace.contains("TNT_MINECART") ||
                        z.denyPlace.contains("CHEST_MINECART") ||
                        z.denyPlace.contains("FURNACE_MINECART") ||
                        z.denyPlace.contains("HOPPER_MINECART") ||
                        z.denyPlace.contains("COMMAND_BLOCK_MINECART"));

        if (denyHere) {
            event.getVehicle().remove();
        }
    }

    private boolean isMinecart(EntityType t) {
        return t == EntityType.MINECART ||
                t == EntityType.TNT_MINECART ||
                t == EntityType.CHEST_MINECART ||
                t == EntityType.FURNACE_MINECART ||
                t == EntityType.HOPPER_MINECART ||
                t == EntityType.COMMAND_BLOCK_MINECART;
    }

    // -------------------- Track ALL changes (not just player) --------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        var zonesAt = plugin.gameplay().zonesAt(event.getBlock().getLocation());
        if (zonesAt.isEmpty()) return;
        for (Zone z : zonesAt) markChanged(z.name, event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        var zonesAt = plugin.gameplay().zonesAt(event.getToBlock().getLocation());
        if (zonesAt.isEmpty()) return;
        for (Zone z : zonesAt) markChanged(z.name, event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(b -> {
            var zonesAt = plugin.gameplay().zonesAt(b.getLocation());
            for (Zone z : zonesAt) markChanged(z.name, b);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(b -> {
            var zonesAt = plugin.gameplay().zonesAt(b.getLocation());
            for (Zone z : zonesAt) markChanged(z.name, b);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        var zonesAt = plugin.gameplay().zonesAt(event.getBlock().getLocation());
        if (zonesAt.isEmpty()) return;
        for (Zone z : zonesAt) markChanged(z.name, event.getBlock());
    }

    // -------------------- TTL clearing / draining --------------------

    /** Max TTL among highest-priority zones at this location. */
    private int maxTTL(List<Zone> zonesAt) {
        int max = 0;
        for (Zone z : zonesAt) if (z.ttlSeconds > max) max = z.ttlSeconds;
        return max;
    }

    /** Should this material be auto-cleared in these zones? */
    private boolean shouldTTLClear(Material type, List<Zone> zonesAt) {
        for (Zone z : zonesAt) {
            if (z.ttlSeconds <= 0) continue;
            if (z.allowAllPlace) return true; // TTL applies to everything in "allow all" zones
            if (z.allowedPlace.contains(type.name())) return true; // TTL for listed materials
        }
        return false;
    }

    /** Schedule removal for solids OR bounded drain for water. */
    private void scheduleTTLClear(Block start, int seconds, List<Zone> zonesAt) {
        BlockPos key = keyOf(start);
        Integer existing = ttlTasks.remove(key);
        if (existing != null) Bukkit.getScheduler().cancelTask(existing);

        int id = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (start.getType() == Material.WATER) {
                // drain inside each applicable zone (bounded flood fill)
                for (Zone z : zonesAt) drainWaterInZone(start, z);
            } else {
                // generic solid removal
                if (start.getType() != Material.AIR) {
                    start.setType(Material.AIR, false);
                    for (Zone z : zonesAt) markChanged(z.name, start);
                }
            }
        }, seconds * 20L);
        ttlTasks.put(key, id);
    }

    private void drainWaterInZone(Block start, Zone z) {
        if (start.getType() != Material.WATER) return;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<Block> q = new ArrayDeque<>();
        q.add(start);
        visited.add(keyOf(start));

        final int perTick = 2000;
        new BukkitRunnable() {
            @Override public void run() {
                int processed = 0;
                while (!q.isEmpty() && processed < perTick) {
                    Block b = q.poll();
                    processed++;
                    if (b.getType() == Material.WATER) {
                        b.setType(Material.AIR, false);
                        markChanged(z.name, b);
                    }
                    for (Block nb : new Block[]{
                            b.getRelative(1,0,0), b.getRelative(-1,0,0),
                            b.getRelative(0,1,0), b.getRelative(0,-1,0),
                            b.getRelative(0,0,1), b.getRelative(0,0,-1)
                    }) {
                        if (!z.cuboid.contains(nb.getLocation())) continue;
                        BlockPos k = keyOf(nb);
                        if (!visited.add(k)) continue;
                        if (nb.getType() == Material.WATER) q.add(nb);
                    }
                }
                if (q.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // -------------------- Resets --------------------

    /** /maceguard clear uses AIR clearing on edited set; SNAPSHOT resets handled here. */
    public void resetZone(Zone z) {
        // Teleport players in zone to snapshot surface (post-reset safety)
        for (Player p : Bukkit.getOnlinePlayers()) {
            var l = p.getLocation();
            if (z.cuboid.contains(l)) {
                int yTop = plugin.snapshots().highestSnapshotY(
                        z.name, l.getWorld().getName(), l.getBlockX(), z.cuboid.minY, z.cuboid.maxY, l.getBlockZ());
                p.teleport(new Location(l.getWorld(), l.getBlockX() + 0.5, yTop + 1.1, l.getBlockZ() + 0.5));
            }
        }

        if (z.resetMode == Zone.ResetMode.AIR) {
            // Clear only edited set (AIR)
            clearTracked(z.name);
            return;
        }

        // SNAPSHOT mode:
        if (z.resetScope == Zone.ResetScope.CHANGED) {
            // Restore only edited blocks
            Set<BlockPos> set = changed.remove(z.name);
            if (set == null || set.isEmpty()) return;
            List<BlockPos> list = new ArrayList<>(set);
            final int batch = 2500;

            new BukkitRunnable() {
                int idx = 0;
                @Override public void run() {
                    int end = Math.min(idx + batch, list.size());
                    for (int i = idx; i < end; i++) {
                        BlockPos p = list.get(i);
                        var w = Bukkit.getWorld(p.world());
                        if (w == null) continue;
                        Block b = w.getBlockAt(p.x(), p.y(), p.z());
                        plugin.snapshots().applyAt(z.name, b);
                    }
                    idx = end;
                    if (idx >= list.size()) cancel();
                }
            }.runTaskTimer(plugin, 1L, 1L);

        } else {
            // FULL restore of the entire cuboid from snapshot (fast, batched, TPS-safe)
            final int minX = z.cuboid.minX, maxX = z.cuboid.maxX;
            final int minY = z.cuboid.minY, maxY = z.cuboid.maxY;
            final int minZ = z.cuboid.minZ, maxZ = z.cuboid.maxZ;
            final World w = Bukkit.getWorld(z.cuboid.worldName());
            if (w == null) return;

            new BukkitRunnable() {
                int x = minX, y = minY, z0 = minZ;
                final int perTick = 5000; // batch size
                @Override public void run() {
                    int done = 0;
                    while (x <= maxX) {
                        while (z0 <= maxZ) {
                            for (; y <= maxY; y++) {
                                Block b = w.getBlockAt(x, y, z0);
                                plugin.snapshots().applyAt(z.name, b);
                                if (++done >= perTick) { y++; return; }
                            }
                            y = minY; z0++;
                        }
                        z0 = minZ; x++;
                    }
                    cancel();
                }
            }.runTaskTimer(plugin, 1L, 1L);

            // Edited set is irrelevant after FULL restore
            changed.remove(z.name);
        }
    }

    // -------------------- helpers --------------------
    public int clearTracked(String zoneName) {
        if (zoneName != null) return clearOne(zoneName);
        int total = 0;
        for (String z : new ArrayList<>(changed.keySet())) total += clearOne(z);
        return total;
    }

    private int clearOne(String z) {
        Set<BlockPos> set = changed.remove(z);
        if (set == null || set.isEmpty()) return 0;
        List<BlockPos> list = new ArrayList<>(set);
        final int batch = 2500;

        new BukkitRunnable() {
            int idx = 0;
            @Override public void run() {
                int end = Math.min(idx + batch, list.size());
                for (int i = idx; i < end; i++) {
                    BlockPos p = list.get(i);
                    var w = Bukkit.getWorld(p.world());
                    if (w == null) continue;
                    Block b = w.getBlockAt(p.x(), p.y(), p.z());
                    if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                }
                idx = end;
                if (idx >= list.size()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return list.size();
    }

    private void markChanged(String zoneName, Block b) {
        changed.computeIfAbsent(zoneName, k -> ConcurrentHashMap.newKeySet())
                .add(keyOf(b));
    }

    private BlockPos keyOf(Block b) {
        return new BlockPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private void msg(Player p, String m) { if (p != null && isDebug()) p.sendMessage("§c" + m); }

    private boolean isDeniedSpecial(Material type, List<Zone> zonesAt) {
        String n = type.name();
        return zonesAt.stream().anyMatch(z ->
                z.denyPlace.contains(n) ||
                        n.equals("RESPAWN_ANCHOR") || n.equals("END_CRYSTAL"));
    }
}
