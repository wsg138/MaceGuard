package com.lincoln.maceguard.listener;

import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildGuardListener implements Listener {

    private final MaceGuardPlugin plugin;

    // regionName -> set of changed blocks
    private final Map<String, Set<BlockPos>> changed = new ConcurrentHashMap<>();

    record BlockPos(String world, int x, int y, int z) {}

    public BuildGuardListener(MaceGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.buildBoxes().isEnabled()) return;

        Location loc = event.getBlockPlaced().getLocation();
        String region = firstBuildBoxName(loc);
        if (region == null) return; // outside our boxes; let other plugins handle

        Material type = event.getBlockPlaced().getType();
        if (!plugin.getAllowedPlace().contains(type.name())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou can only place: §f" + plugin.getAllowedPlace());
            return;
        }

        track(region, event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.buildBoxes().isEnabled()) return;

        Location loc = event.getBlock().getLocation();
        String region = firstBuildBoxName(loc);
        if (region == null) return;

        // Only allow breaking of allowed materials to avoid grief
        if (!plugin.getAllowedPlace().contains(event.getBlock().getType().name())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou can only break blocks that are allowed to be placed here.");
            return;
        }

        track(region, event.getBlock());
    }

    private void track(String region, Block b) {
        changed.computeIfAbsent(region, k -> ConcurrentHashMap.newKeySet())
                .add(new BlockPos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    private String firstBuildBoxName(Location loc) {
        // Return the first box name that contains this location (by config order)
        return plugin.buildBoxes().getBoxes().stream()
                .filter(c -> c.contains(loc))
                .map(c -> c.name())
                .findFirst()
                .orElse(null);
    }

    /** Clear tracked blocks; if name is null, clears all boxes. Returns count cleared. */
    public int clearTracked(String name) {
        int total = 0;
        if (name != null) {
            total += clearOne(name);
        } else {
            for (String key : new ArrayList<>(changed.keySet())) {
                total += clearOne(key);
            }
        }
        if (plugin.isDebug()) plugin.getLogger().info("[BuildBoxes] Cleared " + total + " blocks.");
        return total;
    }

    private int clearOne(String name) {
        Set<BlockPos> set = changed.remove(name);
        if (set == null || set.isEmpty()) return 0;

        List<BlockPos> list = new ArrayList<>(set);
        final int batch = 500;

        new BukkitRunnable() {
            int idx = 0;
            @Override public void run() {
                int end = Math.min(idx + batch, list.size());
                for (int i = idx; i < end; i++) {
                    BlockPos pos = list.get(i);
                    var world = Bukkit.getWorld(pos.world());
                    if (world == null) continue;
                    Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    // Only clear our allowed block types (don’t touch map)
                    if (plugin.getAllowedPlace().contains(b.getType().name())) {
                        b.setType(Material.AIR, false);
                    }
                }
                idx = end;
                if (idx >= list.size()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return list.size();
    }

    /** Called by FlowListener to track water spread. */
    public void trackFlowAt(Location loc) {
        String region = firstBuildBoxName(loc);
        if (region == null) return;
        var world = loc.getWorld();
        if (world == null) return;
        Block b = world.getBlockAt(loc);
        track(region, b);
    }
}
