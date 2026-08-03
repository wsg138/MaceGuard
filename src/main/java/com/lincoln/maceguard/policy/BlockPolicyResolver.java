package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.config.MaceGuardConfig;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Location;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Resolves the effective WorldGuard block-policy reference without allowing an invalid main
 * configuration to enforce anything. A missing named policy remains a referenced failure so the
 * intended fail-closed behavior is preserved only for an explicit valid-schema flag reference.
 */
public final class BlockPolicyResolver {
    private final MaceGuardConfig config;
    private final WorldGuardQueryService worldGuard;
    private final Logger logger;
    private final Set<String> warnedGlobalReferences = ConcurrentHashMap.newKeySet();

    public BlockPolicyResolver(MaceGuardConfig config, WorldGuardQueryService worldGuard) {
        this(config, worldGuard, null);
    }

    public BlockPolicyResolver(MaceGuardConfig config, WorldGuardQueryService worldGuard,
                               Logger logger) {
        this.config = config;
        this.worldGuard = worldGuard;
        this.logger = logger;
    }

    public Resolution resolve(Location location) {
        Resolution resolution = decide(config.enabled(), config.validSchema(),
                worldGuard.blockPolicyAvailable(),
                worldGuard.effectiveBlockPolicyReference(location), config.blockPolicies());
        if (resolution.globalSource()) warnGlobal(location, resolution);
        return resolution;
    }

    static Resolution decide(boolean enabled, boolean validSchema, boolean flagAvailable,
                             WorldGuardQueryService.BlockPolicyReference reference,
                             Map<String, BlockPolicy> policies) {
        if (!enabled) return Resolution.none(Status.MACEGUARD_DISABLED);
        if (!validSchema) return Resolution.none(Status.INVALID_SCHEMA);
        if (!flagAvailable) return Resolution.none(Status.FLAG_UNAVAILABLE);
        if (reference == null || reference.policyName() == null
                || reference.policyName().isBlank())
            return Resolution.none(Status.NO_EFFECTIVE_VALUE);

        String name = reference.policyName().trim().toLowerCase(Locale.ROOT);
        BlockPolicy policy = policies.get(name);
        Status status = policy == null ? Status.REFERENCED_POLICY_MISSING : Status.ACTIVE;
        return new Resolution(reference.regionId(), name, policy, true,
                reference.sourceKind(), reference.globalSource(), status);
    }

    private void warnGlobal(Location location, Resolution resolution) {
        if (logger == null) return;
        String world = location.getWorld() == null ? "unknown" : location.getWorld().getName();
        String key = world + ':' + resolution.name();
        if (!warnedGlobalReferences.add(key)) return;
        logger.warning("WorldGuard __global__ effectively supplies maceguard-block-policy='"
                + resolution.name() + "' in world '" + world + "'. This can intentionally apply "
                + "the material policy throughout that world. MaceGuard did not change or remove "
                + "the flag; review /rg flags __global__.");
    }

    public enum Status {
        ACTIVE,
        REFERENCED_POLICY_MISSING,
        MACEGUARD_DISABLED,
        INVALID_SCHEMA,
        FLAG_UNAVAILABLE,
        NO_EFFECTIVE_VALUE
    }

    public record Resolution(String scopeId, String name, BlockPolicy policy, boolean referenced,
                             String sourceKind, boolean globalSource, Status status) {
        public static Resolution none(Status status) {
            return new Resolution("", "", null, false, "none", false, status);
        }

        public boolean namedPolicyExists() { return policy != null; }

        public boolean schemaAllowsEnforcement() {
            return status == Status.ACTIVE || status == Status.REFERENCED_POLICY_MISSING;
        }

        public String finalResult() {
            return switch (status) {
                case ACTIVE -> "enforce policy '" + name + "'";
                case REFERENCED_POLICY_MISSING ->
                        "fail closed: referenced policy '" + name + "' is missing";
                case MACEGUARD_DISABLED -> "inactive: MaceGuard is disabled";
                case INVALID_SCHEMA -> "inactive: main configuration schema is invalid";
                case FLAG_UNAVAILABLE -> "inactive: custom WorldGuard flag is unavailable";
                case NO_EFFECTIVE_VALUE -> "inactive: no effective policy flag value";
            };
        }
    }
}
