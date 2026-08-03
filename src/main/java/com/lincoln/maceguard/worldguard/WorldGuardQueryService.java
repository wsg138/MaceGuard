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
        BlockPolicyReference reference = effectiveBlockPolicyReference(location);
        return reference == null ? null : reference.policyName();
    }

    /**
     * Returns both the effective string value and the region/parent that supplied it. Liquid
     * confinement uses the source region ID so two distinct boxes with the same named policy do
     * not become one shared liquid scope.
     */
    public BlockPolicyReference effectiveBlockPolicyReference(Location location) {
        if (flags.blockPolicy() == null || location.getWorld() == null) return null;
        String effective = query().queryValue(BukkitAdapter.adapt(location), null, flags.blockPolicy());
        if (effective == null || effective.isBlank()) return null;

        for (ProtectedRegion region : applicableRegions(location)) {
            for (ProtectedRegion source = region; source != null; source = source.getParent()) {
                String direct = source.getFlag(flags.blockPolicy());
                if (effective.equals(direct))
                    return new BlockPolicyReference(source.getId(), effective);
            }
        }

        // QueryState remains authoritative. This fallback is deliberately location-specific and
        // avoids treating an unresolved source as globally shared with another region.
        return new BlockPolicyReference("effective@" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ(), effective);
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

    public record BlockPolicyReference(String regionId, String policyName) { }
}
