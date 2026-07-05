package com.lincoln.maceguard.core.service;

import com.lincoln.maceguard.config.PluginSettings;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.ProtectedRegion;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ZoneRegistry {
    private final Set<String> allowedWorlds;
    private final Map<String, WorldZoneIndex<GameplayZone>> gameplayByWorld;
    private final Map<String, WorldZoneIndex<ProtectedRegion>> protectedByWorld;
    private final Map<String, WorldZoneIndex<GameplayZone>> confinedLiquidByWorld;
    private final PerformanceCounters counters;

    public ZoneRegistry(PluginSettings settings, PerformanceCounters counters) {
        this.allowedWorlds = settings.allowedWorlds();
        this.counters = counters;
        this.gameplayByWorld = buildGameplayIndex(settings.gameplayZones());
        this.protectedByWorld = buildProtectedIndex(settings.protectedRegions());
        this.confinedLiquidByWorld = buildGameplayIndex(settings.gameplayZones().stream().filter(GameplayZone::confineLiquids).toList());
    }

    public boolean isWorldAllowed(String worldName) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(worldName);
    }

    public boolean isProtected(Location location) {
        counters.protectedCheck();
        if (location == null || location.getWorld() == null || !isWorldAllowed(location.getWorld().getName())) {
            return false;
        }
        return !protectedRegionsAt(location).isEmpty();
    }

    public List<ProtectedRegion> protectedRegionsAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return List.of();
        }
        WorldZoneIndex<ProtectedRegion> index = protectedByWorld.get(location.getWorld().getName());
        if (index == null) {
            return List.of();
        }
        return index.query(location);
    }

    public List<GameplayZone> highestPriorityZonesAt(Location location) {
        counters.zoneQuery();
        if (location == null || location.getWorld() == null) {
            return List.of();
        }
        WorldZoneIndex<GameplayZone> index = gameplayByWorld.get(location.getWorld().getName());
        if (index == null) {
            return List.of();
        }
        List<GameplayZone> matches = index.query(location);
        if (matches.isEmpty()) {
            return matches;
        }
        int maxPriority = matches.stream().mapToInt(GameplayZone::priority).max().orElse(Integer.MIN_VALUE);
        List<GameplayZone> highest = new ArrayList<>();
        for (GameplayZone zone : matches) {
            if (zone.priority() == maxPriority) {
                highest.add(zone);
            }
        }
        return highest;
    }

    public boolean isExternallyManaged(Location location) {
        return highestPriorityZonesAt(location).stream().anyMatch(GameplayZone::externallyManaged);
    }

    public ZoneQuery query(Location location) {
        counters.zoneQuery();
        if (location == null || location.getWorld() == null) {
            return ZoneQuery.EMPTY;
        }
        String worldName = location.getWorld().getName();
        List<GameplayZone> highest = highestPriorityZonesAtWithoutCount(location);
        boolean externallyManaged = false;
        for (GameplayZone zone : highest) {
            if (zone.externallyManaged()) {
                externallyManaged = true;
                break;
            }
        }
        boolean protectedRegion = false;
        if (isWorldAllowed(worldName)) {
            WorldZoneIndex<ProtectedRegion> protectedIndex = protectedByWorld.get(worldName);
            protectedRegion = protectedIndex != null && !protectedIndex.query(location).isEmpty();
        }
        return new ZoneQuery(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ(), highest, protectedRegion, externallyManaged);
    }

    public List<GameplayZone> confinedLiquidZonesAt(Location location) {
        counters.zoneQuery();
        if (location == null || location.getWorld() == null) {
            return List.of();
        }
        WorldZoneIndex<GameplayZone> index = confinedLiquidByWorld.get(location.getWorld().getName());
        if (index == null) {
            return List.of();
        }
        return index.query(location);
    }

    public GameplayZone findZone(String zoneName) {
        for (WorldZoneIndex<GameplayZone> index : gameplayByWorld.values()) {
            for (GameplayZone zone : index.allZones()) {
                if (zone.name().equalsIgnoreCase(zoneName)) {
                    return zone;
                }
            }
        }
        return null;
    }

    public Collection<GameplayZone> allGameplayZones() {
        Set<GameplayZone> zones = new HashSet<>();
        for (WorldZoneIndex<GameplayZone> index : gameplayByWorld.values()) {
            zones.addAll(index.allZones());
        }
        return Collections.unmodifiableSet(zones);
    }

    private List<GameplayZone> highestPriorityZonesAtWithoutCount(Location location) {
        WorldZoneIndex<GameplayZone> index = gameplayByWorld.get(location.getWorld().getName());
        if (index == null) {
            return List.of();
        }
        List<GameplayZone> matches = index.query(location);
        if (matches.isEmpty()) {
            return matches;
        }
        int maxPriority = Integer.MIN_VALUE;
        for (GameplayZone zone : matches) {
            maxPriority = Math.max(maxPriority, zone.priority());
        }
        List<GameplayZone> highest = new ArrayList<>();
        for (GameplayZone zone : matches) {
            if (zone.priority() == maxPriority) {
                highest.add(zone);
            }
        }
        return highest;
    }

    public record ZoneQuery(
            String worldName,
            int x,
            int y,
            int z,
            List<GameplayZone> highestZones,
            boolean protectedRegion,
            boolean externallyManaged
    ) {
        private static final ZoneQuery EMPTY = new ZoneQuery("", 0, 0, 0, List.of(), false, false);

        public boolean hasGameplayZones() {
            return !highestZones.isEmpty();
        }
    }

    private Map<String, WorldZoneIndex<GameplayZone>> buildGameplayIndex(List<GameplayZone> zones) {
        Map<String, List<GameplayZone>> perWorld = new ConcurrentHashMap<>();
        for (GameplayZone zone : zones) {
            perWorld.computeIfAbsent(zone.region().worldName(), ignored -> new ArrayList<>()).add(zone);
        }

        Map<String, WorldZoneIndex<GameplayZone>> index = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<GameplayZone>> entry : perWorld.entrySet()) {
            index.put(entry.getKey(), WorldZoneIndex.gameplay(entry.getValue()));
        }
        return index;
    }

    private Map<String, WorldZoneIndex<ProtectedRegion>> buildProtectedIndex(List<ProtectedRegion> zones) {
        Map<String, List<ProtectedRegion>> perWorld = new ConcurrentHashMap<>();
        for (ProtectedRegion zone : zones) {
            perWorld.computeIfAbsent(zone.region().worldName(), ignored -> new ArrayList<>()).add(zone);
        }
        Map<String, WorldZoneIndex<ProtectedRegion>> index = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<ProtectedRegion>> entry : perWorld.entrySet()) {
            index.put(entry.getKey(), WorldZoneIndex.protectedRegions(entry.getValue()));
        }
        return index;
    }

    private static final class WorldZoneIndex<T> {
        private final Map<Long, List<T>> byChunk;
        private final List<T> indexedZones;
        private final RegionAccessors<T> accessors;

        private WorldZoneIndex(List<T> zones, RegionAccessors<T> accessors) {
            this.accessors = accessors;
            this.indexedZones = List.copyOf(zones);
            this.byChunk = new ConcurrentHashMap<>();
            for (T zone : zones) {
                for (int chunkX = accessors.minChunkX(zone); chunkX <= accessors.maxChunkX(zone); chunkX++) {
                    for (int chunkZ = accessors.minChunkZ(zone); chunkZ <= accessors.maxChunkZ(zone); chunkZ++) {
                        byChunk.computeIfAbsent(packChunk(chunkX, chunkZ), ignored -> new ArrayList<>()).add(zone);
                    }
                }
            }
        }

        static WorldZoneIndex<GameplayZone> gameplay(List<GameplayZone> zones) {
            return new WorldZoneIndex<>(zones, new RegionAccessors<>() {
                @Override
                public int minChunkX(GameplayZone zone) {
                    return zone.region().minChunkX();
                }

                @Override
                public int maxChunkX(GameplayZone zone) {
                    return zone.region().maxChunkX();
                }

                @Override
                public int minChunkZ(GameplayZone zone) {
                    return zone.region().minChunkZ();
                }

                @Override
                public int maxChunkZ(GameplayZone zone) {
                    return zone.region().maxChunkZ();
                }

                @Override
                public boolean contains(GameplayZone zone, Location location) {
                    return zone.region().contains(location);
                }
            });
        }

        static WorldZoneIndex<ProtectedRegion> protectedRegions(List<ProtectedRegion> zones) {
            return new WorldZoneIndex<>(zones, new RegionAccessors<>() {
                @Override
                public int minChunkX(ProtectedRegion zone) {
                    return zone.region().minChunkX();
                }

                @Override
                public int maxChunkX(ProtectedRegion zone) {
                    return zone.region().maxChunkX();
                }

                @Override
                public int minChunkZ(ProtectedRegion zone) {
                    return zone.region().minChunkZ();
                }

                @Override
                public int maxChunkZ(ProtectedRegion zone) {
                    return zone.region().maxChunkZ();
                }

                @Override
                public boolean contains(ProtectedRegion zone, Location location) {
                    return zone.region().contains(location);
                }
            });
        }

        List<T> query(Location location) {
            List<T> candidates = byChunk.get(packChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4));
            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }
            List<T> matches = new ArrayList<>();
            for (T candidate : candidates) {
                if (accessors.contains(candidate, location)) {
                    matches.add(candidate);
                }
            }
            return matches;
        }

        List<T> allZones() {
            return indexedZones;
        }

        private static long packChunk(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }

    private interface RegionAccessors<T> {
        int minChunkX(T zone);

        int maxChunkX(T zone);

        int minChunkZ(T zone);

        int maxChunkZ(T zone);

        boolean contains(T zone, Location location);
    }
}
