package com.lincoln.maceguard.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
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

    public boolean blockPolicyAvailable() { return flags.blockPolicy() != null; }

    public String effectiveBlockPolicy(Location location) {
        BlockPolicyReference reference = effectiveBlockPolicyReference(location);
        return reference == null ? null : reference.policyName();
    }

    /**
     * Returns the effective string value and the direct, inherited, global, or fallback source
     * that supplied it. Liquid confinement uses the stable source region ID so two distinct boxes
     * with the same named policy do not become one shared liquid scope.
     */
    public BlockPolicyReference effectiveBlockPolicyReference(Location location) {
        if (!blockPolicyAvailable() || location.getWorld() == null) return null;
        String effective = query().queryValue(BukkitAdapter.adapt(location), null,
                flags.blockPolicy());
        if (effective == null || effective.isBlank()) return null;

        List<ProtectedRegion> applicable = applicableRegions(location);
        for (ProtectedRegion region : applicable) {
            for (ProtectedRegion source = region; source != null; source = source.getParent()) {
                String direct = source.getFlag(flags.blockPolicy());
                if (effective.equals(direct)) {
                    String kind = source == region ? "direct" : "inherited";
                    return new BlockPolicyReference(source.getId(), effective, kind, false);
                }
            }
        }

        RegionManager manager = regionManager(location);
        ProtectedRegion global = manager == null ? null : manager.getRegion("__global__");
        if (global != null && effective.equals(global.getFlag(flags.blockPolicy())))
            return new BlockPolicyReference(global.getId(), effective, "global", true);

        String scopeId = applicable.isEmpty()
                ? "world@" + location.getWorld().getUID()
                : "effective@" + applicable.getFirst().getId();
        return new BlockPolicyReference(scopeId, effective, "effective-fallback", false);
    }

    public List<ProtectedRegion> applicableRegions(Location location) {
        if (location.getWorld() == null) return List.of();
        return query().getApplicableRegions(BukkitAdapter.adapt(location)).getRegions().stream()
                .sorted(Comparator.comparingInt(ProtectedRegion::getPriority).reversed()
                        .thenComparing(ProtectedRegion::getId)).toList();
    }

    private RegionManager regionManager(Location location) {
        if (location.getWorld() == null) return null;
        return WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
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

    public record BlockPolicyReference(String regionId, String policyName, String sourceKind,
                                       boolean globalSource) {
        public BlockPolicyReference(String regionId, String policyName) {
            this(regionId, policyName, "unknown", "__global__".equals(regionId));
        }
    }
}
