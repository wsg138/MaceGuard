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
    private final Logger logger;
    private volatile ResolvedState state;

    public WarzoneRegionService(WarzoneConfig.Region settings) {
        this(settings, null);
    }

    public WarzoneRegionService(WarzoneConfig.Region settings, Logger logger) {
        this.logger = logger;
        this.state = unchecked(settings);
        refresh();
    }

    public synchronized void apply(WarzoneConfig.Region settings) {
        ResolvedState previous = state;
        publish(resolve(settings), previous.resolutionStatus());
    }

    public synchronized boolean refresh() {
        ResolvedState previous = state;
        publish(resolve(previous.settings()), previous.resolutionStatus());
        return state.fullyResolved();
    }

    private ResolvedState resolve(WarzoneConfig.Region settings) {
        World world = Bukkit.getWorld(settings.world());
        ProtectedRegion outer = null;
        Map<String, ProtectedRegion> exclusions = new LinkedHashMap<>();
        Map<String, String> exclusionStatuses = new LinkedHashMap<>();
        String outerStatus;

        if (world == null) {
            outerStatus = "world not loaded";
            for (String id : settings.excludedRegionIds())
                exclusionStatuses.put(id, "world not loaded");
        } else {
            RegionManager manager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));
            if (manager == null) {
                outerStatus = "WorldGuard region manager unavailable";
                for (String id : settings.excludedRegionIds())
                    exclusionStatuses.put(id, "WorldGuard region manager unavailable");
            } else {
                outer = manager.getRegion(settings.id());
                outerStatus = outer == null ? "region not found" : "resolved";
                for (String id : settings.excludedRegionIds()) {
                    ProtectedRegion excluded = manager.getRegion(id);
                    if (excluded == null) {
                        exclusionStatuses.put(id, "region not found");
                    } else {
                        exclusions.put(id, excluded);
                        exclusionStatuses.put(id, "resolved");
                    }
                }
            }
        }

        boolean fullyResolved = "resolved".equals(outerStatus)
                && exclusionStatuses.values().stream().allMatch("resolved"::equals);
        String resolutionStatus = fullyResolved
                ? "resolved" : "unresolved; effective scope disabled";
        return new ResolvedState(settings, world, outer, Map.copyOf(exclusions),
                Map.copyOf(exclusionStatuses), outerStatus, resolutionStatus,
                fullyResolved);
    }

    private void publish(ResolvedState next, String previousStatus) {
        reportStatusChange(previousStatus, next.resolutionStatus());
        state = next;
    }

    /**
     * Returns true only for the exact configured WorldGuard scope. Missing outer or exclusion
     * regions disable the scope rather than broadening restrictions to the world.
     */
    public boolean contains(Location location) {
        return containsResolved(location);
    }

    public boolean containsResolved(Location location) {
        ResolvedState live = state;
        boolean configuredWorld = inConfiguredWorld(location, live);
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        boolean outerResolved = live.outer() != null;
        boolean exclusionsResolved = live.fullyResolved() && outerResolved;
        boolean insideOuter = outerResolved && live.outer().contains(x, y, z);
        boolean insideExcluded = live.exclusions().values().stream()
                .anyMatch(excluded -> excluded.contains(x, y, z));
        return EffectiveScopeDecision.contains(configuredWorld, outerResolved,
                exclusionsResolved, insideOuter, insideExcluded);
    }

    public boolean insideOuterResolved(Location location) {
        ResolvedState live = state;
        return inConfiguredWorld(location, live) && live.outer() != null
                && live.outer().contains(location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
    }

    public String exclusionAt(Location location) {
        ResolvedState live = state;
        if (!inConfiguredWorld(location, live)) return null;
        for (Map.Entry<String, ProtectedRegion> entry : live.exclusions().entrySet()) {
            if (entry.getValue().contains(location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) return entry.getKey();
        }
        return null;
    }

    public boolean inConfiguredWorld(Location location) {
        return inConfiguredWorld(location, state);
    }

    private boolean inConfiguredWorld(Location location, ResolvedState live) {
        World locationWorld = location.getWorld();
        if (locationWorld == null) return false;
        if (live.world() != null)
            return locationWorld.getUID().equals(live.world().getUID());
        return locationWorld.getName().equals(live.settings().world());
    }

    private void reportStatusChange(String previousStatus, String nextStatus) {
        if (logger == null || Objects.equals(nextStatus, previousStatus)) return;
        if ("resolved".equals(nextStatus)) {
            if (!"not checked".equals(previousStatus))
                logger.info("Warzone effective scope is resolved again.");
        } else {
            logger.warning("Warzone effective scope is unresolved; restrictions and positive "
                    + "behavior are disabled rather than broadened beyond WorldGuard geometry.");
        }
    }

    public boolean worldLoaded() { return state.world() != null; }
    public boolean regionResolved() { return state.outer() != null; }
    public boolean fullyResolved() { return state.fullyResolved(); }
    public String resolutionStatus() { return state.resolutionStatus(); }
    public String outerResolutionStatus() { return state.outerStatus(); }
    public Map<String, String> exclusionResolutionStatuses() {
        return state.exclusionStatuses();
    }
    public String worldName() { return state.settings().world(); }
    public String regionId() { return state.settings().id(); }
    public java.util.List<String> excludedRegionIds() {
        return state.settings().excludedRegionIds();
    }

    private static ResolvedState unchecked(WarzoneConfig.Region settings) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (String id : settings.excludedRegionIds()) statuses.put(id, "not checked");
        return new ResolvedState(settings, null, null, Map.of(), Map.copyOf(statuses),
                "not checked", "not checked", false);
    }

    private record ResolvedState(
            WarzoneConfig.Region settings,
            World world,
            ProtectedRegion outer,
            Map<String, ProtectedRegion> exclusions,
            Map<String, String> exclusionStatuses,
            String outerStatus,
            String resolutionStatus,
            boolean fullyResolved
    ) { }
}
