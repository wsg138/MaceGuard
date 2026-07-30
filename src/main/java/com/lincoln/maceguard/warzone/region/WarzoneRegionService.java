package com.lincoln.maceguard.warzone.region;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.logging.Logger;

public final class WarzoneRegionService {
    private WarzoneConfig.Region settings;
    private final Logger logger;
    private World cachedWorld;
    private ProtectedRegion cachedRegion;
    private String resolutionStatus = "not checked";

    public WarzoneRegionService(WarzoneConfig.Region settings) {
        this(settings, null);
    }

    public WarzoneRegionService(WarzoneConfig.Region settings, Logger logger) {
        this.logger = logger;
        apply(settings);
    }

    public void apply(WarzoneConfig.Region settings) {
        this.settings = settings;
        refresh();
    }

    /** Re-resolves by world and region ID so recreated or replaced regions are picked up. */
    public boolean refresh() {
        World world = Bukkit.getWorld(settings.world());
        ProtectedRegion region = null;
        String nextStatus;
        if (world == null) {
            nextStatus = "world not loaded";
        } else {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            if (manager == null) nextStatus = "WorldGuard region manager unavailable";
            else {
                region = manager.getRegion(settings.id());
                nextStatus = region == null ? "region not found" : "resolved";
            }
        }
        cachedWorld = world;
        cachedRegion = region;
        reportStatusChange(nextStatus);
        resolutionStatus = nextStatus;
        return cachedRegion != null;
    }

    /**
     * Fails closed in the configured loaded world while the region cannot be resolved.
     * This is for restrictive decisions only; positive region-scoped behavior must use containsResolved.
     */
    public boolean contains(Location location) {
        if (!inConfiguredWorld(location)) return false;
        return cachedRegion == null || cachedRegion.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Exact membership only; positive or destructive behavior must never broaden to the whole world. */
    public boolean containsResolved(Location location) {
        return inConfiguredWorld(location) && cachedRegion != null
                && cachedRegion.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean inConfiguredWorld(Location location) {
        World locationWorld = location.getWorld();
        if (locationWorld == null) return false;
        if (cachedWorld != null) return locationWorld.getUID().equals(cachedWorld.getUID());
        return locationWorld.getName().equals(settings.world());
    }

    private void reportStatusChange(String nextStatus) {
        if (logger == null || Objects.equals(nextStatus, resolutionStatus)) return;
        if ("resolved".equals(nextStatus)) {
            if (!"not checked".equals(resolutionStatus)) logger.info("Warzone region '" + settings.id()
                    + "' in world '" + settings.world() + "' is resolved again.");
        } else {
            logger.warning("Warzone region '" + settings.id() + "' in world '" + settings.world()
                    + "' is unresolved (" + nextStatus + "); restrictions fail closed in that world.");
        }
    }

    public boolean worldLoaded() { return cachedWorld != null; }
    public boolean regionResolved() { return cachedRegion != null; }
    public String resolutionStatus() { return resolutionStatus; }
    public String worldName() { return settings.world(); }
    public String regionId() { return settings.id(); }
}
