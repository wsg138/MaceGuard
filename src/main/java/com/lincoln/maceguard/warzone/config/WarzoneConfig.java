package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record WarzoneConfig(
        int version,
        boolean enabled,
        Region region,
        Schedule schedule,
        Selection selection,
        Map<String, SpecialRule> specialRules,
        List<Duration> warningTimes,
        Messages messages,
        Combat combat,
        Cobwebs cobwebs,
        Map<RestrictionTarget, TargetPolicy> targetPolicies,
        Map<String, Modifier> modifiers,
        Map<String, Set<String>> conflictGroups
) {
    public WarzoneConfig {
        specialRules = Map.copyOf(specialRules);
        warningTimes = List.copyOf(warningTimes);
        targetPolicies = Map.copyOf(targetPolicies);
        modifiers = Map.copyOf(modifiers);
        conflictGroups = conflictGroups.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    public WarzoneConfig(int version, boolean enabled, Region region, Schedule schedule,
                         Selection selection, Map<String, SpecialRule> specialRules,
                         List<Duration> warningTimes, Messages messages, Cobwebs cobwebs,
                         Map<RestrictionTarget, TargetPolicy> targetPolicies,
                         Map<String, Modifier> modifiers, Map<String, Set<String>> conflictGroups) {
        this(version, enabled, region, schedule, selection, specialRules, warningTimes, messages,
                new Combat(new Stasis(Duration.ofSeconds(60))), cobwebs, targetPolicies, modifiers,
                conflictGroups);
    }

    public record Region(String world, String id, List<String> excludedRegionIds) {
        public Region {
            excludedRegionIds = List.copyOf(excludedRegionIds);
        }
    }

    public record Schedule(DayOfWeek day, LocalTime time, ZoneId timezone) { }

    public record Selection(Mode mode, int minimum, int maximum, boolean preventIdenticalRepeat,
                            Map<Integer, Integer> countWeights) {
        public Selection {
            countWeights = Map.copyOf(countWeights);
        }

        public enum Mode { WEIGHTED_RANDOM_MODIFIERS }
    }

    public record SpecialRule(int weeklyInclusionChancePercent,
                              int unrestrictedMaceChancePercent) { }

    public record Messages(Duration blockedMessageCooldown, Audience warningAudience, Audience transitionAudience) { }

    public record Combat(Stasis stasis) { }

    public record Stasis(Duration minimumAge) { }

    public record Cobwebs(Duration clearAfter, boolean clearOnMetaChange, boolean clearOnDisable) { }

    public record TargetPolicy(boolean canDisable, boolean canCooldown, Duration maximumCooldown) { }

    public record Restriction(RestrictionTarget target, RestrictionMode mode, Duration cooldown) { }

    public record Modifier(
            String id,
            boolean enabled,
            int weight,
            boolean combatCarryover,
            String displayName,
            String description,
            Set<Effect> effects,
            Map<RestrictionTarget, Restriction> restrictions,
            String startMessage,
            String endMessage,
            String warningMessage
    ) {
        public Modifier {
            effects = Set.copyOf(effects);
            restrictions = Map.copyOf(restrictions);
        }

        public Modifier(String id, boolean enabled, int weight, String displayName,
                        String description, Set<Effect> effects,
                        Map<RestrictionTarget, Restriction> restrictions,
                        String startMessage, String endMessage, String warningMessage) {
            this(id, enabled, weight, false, displayName, description, effects, restrictions,
                    startMessage, endMessage, warningMessage);
        }
    }

    public record ActiveSet(
            List<String> modifierIds,
            String displayName,
            String description,
            Set<Effect> effects,
            Map<RestrictionTarget, Restriction> restrictions,
            Set<Effect> carriedEffects,
            Map<RestrictionTarget, Restriction> carriedRestrictions
    ) {
        public ActiveSet {
            modifierIds = List.copyOf(modifierIds);
            effects = Set.copyOf(effects);
            restrictions = Map.copyOf(restrictions);
            carriedEffects = Set.copyOf(carriedEffects);
            carriedRestrictions = Map.copyOf(carriedRestrictions);
        }

        public ActiveSet(List<String> modifierIds, String displayName, String description,
                         Set<Effect> effects, Map<RestrictionTarget, Restriction> restrictions) {
            this(modifierIds, displayName, description, effects, restrictions, Set.of(), Map.of());
        }

        public String id() { return String.join("+", modifierIds); }
        public boolean cobwebsAllowed() { return effects.contains(Effect.COBWEBS); }
        public boolean cartsAllowed() { return modifierIds.contains("carts"); }
        public boolean elytraGlidingAllowed() { return effects.contains(Effect.ELYTRA_NO_ROCKETS); }
        public boolean fireworkBoostBlocked() { return effects.contains(Effect.ELYTRA_NO_ROCKETS); }
        public boolean carriedElytraGlidingAllowed() { return carriedEffects.contains(Effect.ELYTRA_NO_ROCKETS); }
    }

    public enum Effect {
        COBWEBS,
        ELYTRA_NO_ROCKETS
    }

    public enum Audience { GLOBAL, WARZONE }
}
