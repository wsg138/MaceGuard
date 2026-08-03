package com.lincoln.maceguard.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;

public final class WorldGuardQueryService {
    private final MaceGuardFlags flags;

    public WorldGuardQueryService(MaceGuardFlags flags) { this.flags = flags; }

    public boolean durabilityAllowed(Location location, Player player) {
        return state(location, player, flags.durability());
    }

    public boolean cobwebsAllowed(Location location, Player player) {
        return state(location, player, flags.cobwebs());
    }

    public boolean warzoneCobwebsAllowed(Location location) {
        if (flags.warzoneCobwebs() == null || location.getWorld() == null) return false;
        StateFlag.State value = query().queryState(BukkitAdapter.adapt(location), null,
                flags.warzoneCobwebs());
        return value != StateFlag.State.DENY;
    }

    public boolean explosivesDenied(Location location, Player player) {
        if (flags.explosives() == null || location.getWorld() == null) return false;
        StateFlag.State value = query().queryState(BukkitAdapter.adapt(location),
                player == null ? null : WorldGuardPlugin.inst().wrapPlayer(player),
                flags.explosives());
        return value == StateFlag.State.DENY;
    }

    public boolean buildAllowed(Location location, Player player) {
        if (location.getWorld() == null) return false;
        return query().testBuild(BukkitAdapter.adapt(location),
                player == null ? null : WorldGuardPlugin.inst().wrapPlayer(player));
    }

    public String effectiveResetProfile(Location location) {
        if (flags.resetProfile() == null || location.getWorld() == null) return null;
        return query().queryValue(BukkitAdapter.adapt(location), null, flags.resetProfile());
    }

    public String effectiveBlockPolicy(Location location) {
        if (flags.blockPolicy() == null || location.getWorld() == null) return null;
        return query().queryValue(BukkitAdapter.adapt(location), null, flags.blockPolicy());
    }

    public List<ProtectedRegion> applicableRegions(Location location) {
        if (location.getWorld() == null) return List.of();
        return query().getApplicableRegions(BukkitAdapter.adapt(location)).getRegions().stream()
                .sorted(Comparator.comparingInt(ProtectedRegion::getPriority).reversed()
                        .thenComparing(ProtectedRegion::getId)).toList();
    }

    private boolean state(Location location, Player player, StateFlag flag) {
        if (flag == null || location.getWorld() == null) return false;
        StateFlag.State value = query().queryState(BukkitAdapter.adapt(location),
                player == null ? null : WorldGuardPlugin.inst().wrapPlayer(player), flag);
        return value == StateFlag.State.ALLOW;
    }

    private RegionQuery query() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }
}
