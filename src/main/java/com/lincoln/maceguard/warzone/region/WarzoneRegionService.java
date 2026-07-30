package com.lincoln.maceguard.warzone.region;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class WarzoneRegionService {
    private WarzoneConfig.Region settings;
    private World cachedWorld;
    private ProtectedRegion cachedRegion;

    public WarzoneRegionService(WarzoneConfig.Region settings) {
        apply(settings);
    }

    public void apply(WarzoneConfig.Region settings) {
        this.settings = settings;
        refresh();
    }

    public boolean refresh() {
        cachedWorld = Bukkit.getWorld(settings.world());
        RegionManager manager = cachedWorld == null ? null : WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(cachedWorld));
        cachedRegion = manager == null ? null : manager.getRegion(settings.id());
        return cachedRegion != null;
    }

    public boolean contains(Location location) {
        return cachedWorld != null && cachedRegion != null && location.getWorld() != null
                && location.getWorld().getUID().equals(cachedWorld.getUID())
                && cachedRegion.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean worldLoaded() { return cachedWorld != null; }
    public boolean regionResolved() { return cachedRegion != null; }
    public String worldName() { return settings.world(); }
    public String regionId() { return settings.id(); }
}
