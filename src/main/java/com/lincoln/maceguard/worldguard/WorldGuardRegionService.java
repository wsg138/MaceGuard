package com.lincoln.maceguard.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.World;

import java.util.Locale;
import java.util.Optional;

public final class WorldGuardRegionService {
    public Optional<ProtectedRegion> region(World world, String id) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        return manager == null ? Optional.empty() : Optional.ofNullable(manager.getRegion(id.toLowerCase(Locale.ROOT)));
    }

    public Optional<RegionDescriptor> cuboid(World world, String id) {
        return region(world, id).filter(ProtectedCuboidRegion.class::isInstance).map(ProtectedCuboidRegion.class::cast)
                .map(region -> descriptor(world, region));
    }

    public RegionDescriptor descriptor(World world, ProtectedCuboidRegion region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        long x = (long) max.x() - min.x() + 1;
        long y = (long) max.y() - min.y() + 1;
        long z = (long) max.z() - min.z() + 1;
        long volume;
        try { volume = Math.multiplyExact(Math.multiplyExact(x, y), z); }
        catch (ArithmeticException ex) { volume = Long.MAX_VALUE; }
        String type = "CUBOID";
        return new RegionDescriptor(region.getId(), world.getName(), world.getUID(), type,
                min.x(), min.y(), min.z(), max.x(), max.y(), max.z(),
                RegionGeometryFingerprint.hash(region.getId(), world.getUID(), type, min.x(), min.y(), min.z(), max.x(), max.y(), max.z()), volume);
    }
}
