package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.bukkit.Material;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class RestrictionService {
    private final Supplier<WarzoneConfig.ActiveSet> activeSet;
    private final CooldownService cooldowns;
    private final Predicate<UUID> carryoverEligible;

    public RestrictionService(Supplier<WarzoneConfig.ActiveSet> activeSet, CooldownService cooldowns) {
        this(activeSet, cooldowns, ignored -> false);
    }

    public RestrictionService(Supplier<WarzoneConfig.ActiveSet> activeSet, CooldownService cooldowns,
                              Predicate<UUID> carryoverEligible) {
        this.activeSet = activeSet;
        this.cooldowns = cooldowns;
        this.carryoverEligible = carryoverEligible;
    }

    public RestrictionDecision material(UUID playerId, Material material, boolean bypass,
                                        boolean actorInside, boolean targetInside) {
        return material(playerId, material, bypass, actorInside, targetInside, false);
    }

    public RestrictionDecision material(UUID playerId, Material material, boolean bypass,
                                        boolean actorInside, boolean targetInside,
                                        boolean actorExcluded) {
        if (bypass) return RestrictionDecision.unrestricted();
        Map<RestrictionTarget, WarzoneConfig.Restriction> active = restrictionsFor(
                playerId, actorInside, targetInside, actorExcluded);
        WarzoneConfig.Restriction restriction = active.values().stream()
                .filter(value -> !value.target().effectOnly() && value.target().matches(material))
                .sorted(Comparator.comparingInt(value ->
                        value.target().kind() == RestrictionTarget.Kind.MATERIAL ? 0 : 1))
                .findFirst().orElse(null);
        return decide(playerId, restriction);
    }

    public RestrictionDecision materialDisableOnly(UUID playerId, Material material, boolean bypass,
                                                   boolean actorInside, boolean targetInside) {
        return materialDisableOnly(playerId, material, bypass, actorInside, targetInside, false);
    }

    public RestrictionDecision materialDisableOnly(UUID playerId, Material material, boolean bypass,
                                                   boolean actorInside, boolean targetInside,
                                                   boolean actorExcluded) {
        RestrictionDecision decision = material(playerId, material, bypass, actorInside,
                targetInside, actorExcluded);
        return decision.result() == RestrictionDecision.Result.DISABLED
                ? decision : RestrictionDecision.unrestricted();
    }

    /**
     * Evaluates the whole-spear group without requiring a concrete spear material. This is used
     * when the damage source proves a spear attack but an attribute swap has already replaced the
     * visible main-hand item.
     */
    public RestrictionDecision spear(UUID playerId, boolean bypass,
                                     boolean actorInside, boolean targetInside) {
        return spear(playerId, bypass, actorInside, targetInside, false);
    }

    public RestrictionDecision spear(UUID playerId, boolean bypass,
                                     boolean actorInside, boolean targetInside,
                                     boolean actorExcluded) {
        if (bypass) return RestrictionDecision.unrestricted();
        return decide(playerId, restrictionsFor(playerId, actorInside, targetInside, actorExcluded)
                .get(RestrictionTarget.SPEAR));
    }

    public RestrictionDecision spearDamage(UUID playerId, boolean bypass,
                                           boolean actorInside, boolean targetInside) {
        return spearDamage(playerId, bypass, actorInside, targetInside, false);
    }

    public RestrictionDecision spearDamage(UUID playerId, boolean bypass,
                                           boolean actorInside, boolean targetInside,
                                           boolean actorExcluded) {
        if (bypass) return RestrictionDecision.unrestricted();
        return decide(playerId, restrictionsFor(playerId, actorInside, targetInside, actorExcluded)
                .get(RestrictionTarget.SPEAR_DAMAGE));
    }

    public RestrictionDecision lunge(UUID playerId, boolean bypass,
                                     boolean actorInside, boolean targetInside) {
        return lunge(playerId, bypass, actorInside, targetInside, false);
    }

    public RestrictionDecision lunge(UUID playerId, boolean bypass,
                                     boolean actorInside, boolean targetInside,
                                     boolean actorExcluded) {
        if (bypass) return RestrictionDecision.unrestricted();
        return decide(playerId, restrictionsFor(playerId, actorInside, targetInside, actorExcluded)
                .get(RestrictionTarget.SPEAR_LUNGE));
    }

    public void success(UUID playerId, RestrictionDecision decision) {
        success(playerId, decision, null);
    }

    public void success(UUID playerId, RestrictionDecision decision, Material concreteMaterial) {
        if (decision.startsCooldownAfterSuccess())
            cooldowns.start(playerId, decision.target(), decision.restriction().cooldown(),
                    concreteMaterial);
    }

    private Map<RestrictionTarget, WarzoneConfig.Restriction> restrictionsFor(
            UUID playerId, boolean actorInside, boolean targetInside, boolean actorExcluded) {
        WarzoneConfig.ActiveSet current = activeSet.get();
        if (actorInside || targetInside) return current.restrictions();
        if (actorExcluded) return Map.of();
        return carryoverEligible.test(playerId) ? current.carriedRestrictions() : Map.of();
    }

    private RestrictionDecision decide(UUID playerId, WarzoneConfig.Restriction restriction) {
        if (restriction == null) return RestrictionDecision.unrestricted();
        if (restriction.mode() == RestrictionMode.DISABLED)
            return new RestrictionDecision(RestrictionDecision.Result.DISABLED,
                    restriction.target(), restriction, Duration.ZERO);
        Duration remaining = cooldowns.remaining(playerId, restriction.target());
        return new RestrictionDecision(remaining.isZero() ? RestrictionDecision.Result.COOLDOWN_READY
                : RestrictionDecision.Result.COOLDOWN_ACTIVE, restriction.target(), restriction, remaining);
    }
}
