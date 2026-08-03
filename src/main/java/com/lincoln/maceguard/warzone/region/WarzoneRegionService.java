package com.lincoln.maceguard.warzone.region;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public final class WarzoneRegionService {
    private WarzoneConfig.Region settings;
    private final Logger logger;
    private World cachedWorld;
    private ProtectedRegion outer;
    private final Map<String, ProtectedRegion> exclusions = new LinkedHashMap<>();
    private final Map<String, String> exclusionStatuses = new LinkedHashMap<>();
    private String outerStatus = "not checked";
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

    public boolean refresh() {
        World world = Bukkit.getWorld(settings.world());
        ProtectedRegion resolvedOuter = null;
        Map<String, ProtectedRegion> resolvedExclusions = new LinkedHashMap<>();
        Map<String, String> nextExclusionStatuses = new LinkedHashMap<>();
        String nextOuterStatus;

        if (world == null) {
            nextOuterStatus = "world not loaded";
            for (String id : settings.excludedRegionIds())
                nextExclusionStatuses.put(id, "world not loaded");
        } else {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            if (manager == null) {
                nextOuterStatus = "WorldGuard region manager unavailable";
                for (String id : settings.excludedRegionIds())
                    nextExclusionStatuses.put(id, "WorldGuard region manager unavailable");
            } else {
                resolvedOuter = manager.getRegion(settings.id());
                nextOuterStatus = resolvedOuter == null ? "region not found" : "resolved";
                for (String id : settings.excludedRegionIds()) {
                    ProtectedRegion excluded = manager.getRegion(id);
                    if (excluded == null) nextExclusionStatuses.put(id, "region not found");
                    else {
                        resolvedExclusions.put(id, excluded);
                        nextExclusionStatuses.put(id, "resolved");
                    }
                }
            }
        }

        cachedWorld = world;
        outer = resolvedOuter;
        exclusions.clear();
        exclusions.putAll(resolvedExclusions);
        exclusionStatuses.clear();
        exclusionStatuses.putAll(nextExclusionStatuses);
        outerStatus = nextOuterStatus;
        String nextStatus = fullyResolved(nextOuterStatus, nextExclusionStatuses)
                ? "resolved" : "unresolved; effective scope disabled";
        reportStatusChange(nextStatus);
        resolutionStatus = nextStatus;
        return fullyResolved();
    }

    private boolean fullyResolved(String candidateOuter, Map<String, String> candidateExclusions) {
        return "resolved".equals(candidateOuter)
                && candidateExclusions.values().stream().allMatch("resolved"::equals);
    }

    /**
     * Returns true only for the exact configured WorldGuard scope. Missing outer or exclusion
     * regions disable the scope rather than broadening restrictions to the world.
     */
    public boolean contains(Location location) {
        return containsResolved(location);
    }

    public boolean containsResolved(Location location) {
        boolean configuredWorld = inConfiguredWorld(location);
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        boolean outerResolved = outer != null;
        boolean exclusionsResolved = settings.excludedRegionIds().stream().allMatch(exclusions::containsKey);
        boolean insideOuter = outerResolved && outer.contains(x, y, z);
        boolean insideExcluded = exclusions.values().stream()
                .anyMatch(excluded -> excluded.contains(x, y, z));
        return EffectiveScopeDecision.contains(configuredWorld, outerResolved, exclusionsResolved,
                insideOuter, insideExcluded);
    }

    public boolean insideOuterResolved(Location location) {
        return inConfiguredWorld(location) && outer != null
                && outer.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public String exclusionAt(Location location) {
        if (!inConfiguredWorld(location)) return null;
        for (Map.Entry<String, ProtectedRegion> entry : exclusions.entrySet())
            if (entry.getValue().contains(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                return entry.getKey();
        return null;
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
            if (!"not checked".equals(resolutionStatus))
                logger.info("Warzone effective scope is resolved again.");
        } else {
            logger.warning("Warzone effective scope is unresolved; restrictions and positive behavior are disabled "
                    + "rather than broadened beyond WorldGuard geometry.");
        }
    }

    public boolean worldLoaded() { return cachedWorld != null; }
    public boolean regionResolved() { return outer != null; }
    public boolean fullyResolved() {
        return outer != null && settings.excludedRegionIds().stream().allMatch(exclusions::containsKey);
    }
    public String resolutionStatus() { return resolutionStatus; }
    public String outerResolutionStatus() { return outerStatus; }
    public Map<String, String> exclusionResolutionStatuses() { return Map.copyOf(exclusionStatuses); }
    public String worldName() { return settings.world(); }
    public String regionId() { return settings.id(); }
    public java.util.List<String> excludedRegionIds() { return settings.excludedRegionIds(); }
}
