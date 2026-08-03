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
                ? "resolved; effective scope active"
                : "unresolved; effective scope inactive";
        return new ResolvedState(settings, world, outer, Map.copyOf(exclusions),
                Map.copyOf(exclusionStatuses), outerStatus, resolutionStatus,
                fullyResolved);
    }

    private void publish(ResolvedState next, String previousStatus) {
        reportStatusChange(previousStatus, next);
        state = next;
    }

    /**
     * Returns true only for the exact configured WorldGuard scope. Missing outer or exclusion
     * regions make the effective scope inactive rather than broadening restrictions to the world.
     */
    public boolean contains(Location location) {
        return containsResolved(location);
    }

    public boolean containsResolved(Location location) {
        ResolvedState live = state;
        if (!live.fullyResolved() || !inConfiguredWorld(location, live)) return false;
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        boolean insideOuter = live.outer().contains(x, y, z);
        boolean insideExcluded = live.exclusions().values().stream()
                .anyMatch(excluded -> excluded.contains(x, y, z));
        return EffectiveScopeDecision.contains(true, true, true, insideOuter, insideExcluded);
    }

    public boolean insideOuterResolved(Location location) {
        ResolvedState live = state;
        return inConfiguredWorld(location, live) && live.outer() != null
                && live.outer().contains(location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
    }

    public String exclusionAt(Location location) {
        ResolvedState live = state;
        if (!live.fullyResolved() || !inConfiguredWorld(location, live)) return null;
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

    private void reportStatusChange(String previousStatus, ResolvedState next) {
        if (logger == null || Objects.equals(next.resolutionStatus(), previousStatus)) return;
        if (next.fullyResolved()) {
            if (!"not checked".equals(previousStatus))
                logger.info("Warzone effective scope resolved again. Exact WorldGuard gameplay "
                        + "scope is active.");
            return;
        }
        String unresolvedExclusions = next.exclusionStatuses().entrySet().stream()
                .filter(entry -> !"resolved".equals(entry.getValue()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        logger.warning("Warzone effective scope is inactive: world='"
                + next.settings().world() + "', outer='" + next.settings().id() + "'="
                + next.outerStatus() + (unresolvedExclusions.isEmpty() ? ""
                : ", exclusions={" + unresolvedExclusions + "}")
                + ". No restrictions or positive warzone effects are active, and no world-wide "
                + "fallback is being applied.");
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
