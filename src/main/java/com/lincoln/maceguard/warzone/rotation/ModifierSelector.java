package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class ModifierSelector {
    public static final int MAX_CONFIGURED_MODIFIERS = 24;
    public static final int MAX_VALID_COMBINATIONS = 100_000;
    private static final String ELYTRA = "elytra-no-rockets";
    private static final RestrictionTarget MACE =
            RestrictionTarget.parse("MACE").orElseThrow();

    private final RandomGenerator random;

    public ModifierSelector(RandomGenerator random) {
        this.random = random;
    }

    public SelectionResult select(WarzoneConfig config, Set<String> previous) {
        List<List<String>> combinations = randomEligibleCombinations(config);
        if (combinations.isEmpty())
            throw new IllegalStateException("No valid modifier combinations are configured.");

        WarzoneConfig.SpecialRule elytraRule = config.specialRules().get(ELYTRA);
        WarzoneConfig.Modifier elytra = config.modifiers().get(ELYTRA);
        boolean elytraAvailable = elytra != null && elytra.enabled()
                && elytraRule != null && elytraRule.weeklyInclusionChancePercent() > 0;
        boolean includeElytra = elytraAvailable
                && percent(elytraRule.weeklyInclusionChancePercent());

        List<List<String>> eligible = filterElytra(combinations, includeElytra);
        if (includeElytra && elytraRule.unrestrictedMaceChancePercent() > 0
                && percent(elytraRule.unrestrictedMaceChancePercent())) {
            List<List<String>> unrestricted = eligible.stream()
                    .filter(candidate -> !restrictsMace(config, candidate))
                    .toList();
            if (unrestricted.isEmpty())
                throw new IllegalStateException("Elytra requires an unrestricted-Mace branch, but no valid combination exists.");
            eligible = unrestricted;
        }

        if (config.selection().preventIdenticalRepeat() && eligible.size() > 1) {
            List<List<String>> alternatives = eligible.stream()
                    .filter(candidate -> !new LinkedHashSet<>(candidate).equals(previous))
                    .toList();
            if (!alternatives.isEmpty()) eligible = alternatives;
        }

        Map<Integer, List<List<String>>> byCount = new LinkedHashMap<>();
        for (List<String> candidate : eligible) {
            if (!config.selection().countWeights().containsKey(candidate.size())) continue;
            byCount.computeIfAbsent(candidate.size(), ignored -> new ArrayList<>()).add(candidate);
        }
        if (byCount.isEmpty())
            throw new IllegalStateException("No valid modifier count can be filled from the enabled outcomes.");

        int count = weightedCount(config.selection().countWeights(), byCount.keySet());
        List<List<String>> sameSize = List.copyOf(byCount.get(count));
        List<String> selected = weightedWithoutReplacement(config, sameSize, count);
        return new SelectionResult(selected, compose(config, selected), combinations.size());
    }

    public WarzoneConfig.ActiveSet compose(WarzoneConfig config, List<String> ids) {
        List<String> normalized = ids.stream().distinct().sorted().toList();
        int minimum = config.selection().minimum();
        int maximum = config.selection().maximum();
        if (normalized.size() < minimum || normalized.size() > maximum) {
            throw new IllegalArgumentException("Selected modifier count must be between "
                    + minimum + " and " + maximum + ".");
        }
        Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions =
                new LinkedHashMap<>();
        Set<WarzoneConfig.Effect> effects = new LinkedHashSet<>();
        List<String> displays = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (String id : normalized) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier == null)
                throw new IllegalArgumentException("Unknown modifier '" + id + "'.");
            if (!modifier.enabled())
                throw new IllegalArgumentException("Modifier '" + id + "' is disabled.");
            displays.add(modifier.displayName());
            descriptions.add(modifier.description());
            effects.addAll(modifier.effects());
            modifier.restrictions().forEach((target, restriction) -> {
                WarzoneConfig.Restriction previous = restrictions.putIfAbsent(target, restriction);
                if (previous != null && !previous.equals(restriction))
                    throw new IllegalArgumentException("Modifiers conflict on restriction target "
                            + target.id() + ".");
            });
        }
        if (!isConflictFree(config, normalized))
            throw new IllegalArgumentException("Selected modifiers violate a mutual-exclusion or conditional rule.");
        return new WarzoneConfig.ActiveSet(normalized,
                String.join(" <gray>+ </gray>", displays),
                String.join(" ", descriptions), effects, restrictions);
    }

    public List<List<String>> validCombinations(WarzoneConfig config) {
        List<String> ids = config.modifiers().values().stream()
                .filter(WarzoneConfig.Modifier::enabled)
                .map(WarzoneConfig.Modifier::id)
                .sorted(Comparator.naturalOrder()).toList();
        if (ids.size() > MAX_CONFIGURED_MODIFIERS) {
            throw new IllegalStateException("Modifier selection supports at most "
                    + MAX_CONFIGURED_MODIFIERS + " enabled modifiers.");
        }
        List<List<String>> result = new ArrayList<>();
        enumerate(config, ids, 0, new ArrayList<>(), result);
        return List.copyOf(result);
    }

    public List<List<String>> selectableCombinations(WarzoneConfig config) {
        validateSpecialRuleSurface(config);
        List<List<String>> combinations = validCombinations(config);
        WarzoneConfig.SpecialRule rule = config.specialRules().get(ELYTRA);
        WarzoneConfig.Modifier modifier = config.modifiers().get(ELYTRA);
        boolean enabled = modifier != null && modifier.enabled();
        int inclusion = enabled && rule != null
                ? rule.weeklyInclusionChancePercent() : 0;

        List<List<String>> withElytra = combinations.stream()
                .filter(value -> value.contains(ELYTRA)).toList();
        List<List<String>> withoutElytra = combinations.stream()
                .filter(value -> !value.contains(ELYTRA)).toList();

        if (inclusion == 0) {
            requireFeasibleBranch(config, withoutElytra,
                    "No selectable non-Elytra combination exists while Elytra inclusion is disabled.");
            return withoutElytra;
        }

        requireFeasibleBranch(config, withElytra,
                "No selectable Elytra combination exists for the configured inclusion chance.");
        if (inclusion < 100) {
            requireFeasibleBranch(config, withoutElytra,
                    "No selectable non-Elytra combination exists for the configured inclusion chance.");
        }

        List<List<String>> unrestrictedElytra = withElytra;
        if (rule.unrestrictedMaceChancePercent() > 0) {
            unrestrictedElytra = withElytra.stream()
                    .filter(value -> !restrictsMace(config, value)).toList();
            requireFeasibleBranch(config, unrestrictedElytra,
                    "No selectable Elytra combination leaves Maces unrestricted for the configured chance.");
        }
        if (rule.unrestrictedMaceChancePercent() == 100)
            withElytra = unrestrictedElytra;

        if (inclusion == 100) return withElytra;
        List<List<String>> selectable = new ArrayList<>(withoutElytra.size() + withElytra.size());
        selectable.addAll(withoutElytra);
        selectable.addAll(withElytra);
        return List.copyOf(selectable);
    }

    private List<List<String>> randomEligibleCombinations(WarzoneConfig config) {
        return selectableCombinations(config);
    }

    private List<List<String>> filterElytra(List<List<String>> combinations, boolean include) {
        List<List<String>> filtered = combinations.stream()
                .filter(candidate -> candidate.contains(ELYTRA) == include)
                .toList();
        if (!filtered.isEmpty()) return filtered;
        throw new IllegalStateException("No valid combination satisfies the Elytra inclusion rule.");
    }

    private void validateSpecialRuleSurface(WarzoneConfig config) {
        for (String id : config.specialRules().keySet()) {
            if (!ELYTRA.equals(id)) {
                throw new IllegalStateException("rotation.special-rules supports only '"
                        + ELYTRA + "'; unsupported entry '" + id + "'.");
            }
        }
    }

    private void requireFeasibleBranch(WarzoneConfig config, List<List<String>> branch,
                                       String message) {
        boolean feasible = branch.stream()
                .anyMatch(value -> config.selection().countWeights().containsKey(value.size()));
        if (!feasible) throw new IllegalStateException(message);
    }

    private boolean restrictsMace(WarzoneConfig config, List<String> ids) {
        for (String id : ids) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier != null && modifier.restrictions().containsKey(MACE)) return true;
        }
        return false;
    }

    private int weightedCount(Map<Integer, Integer> weights, Set<Integer> feasible) {
        long total = feasible.stream().mapToLong(count -> weights.getOrDefault(count, 0)).sum();
        if (total <= 0) throw new IllegalStateException("No feasible modifier count has a positive weight.");
        long roll = random.nextLong(total);
        for (int count : feasible.stream().sorted().toList()) {
            roll -= weights.getOrDefault(count, 0);
            if (roll < 0) return count;
        }
        throw new IllegalStateException("Could not select a weighted modifier count.");
    }

    private List<String> weightedWithoutReplacement(WarzoneConfig config,
                                                     List<List<String>> combinations,
                                                     int count) {
        List<String> selected = new ArrayList<>();
        while (selected.size() < count) {
            Set<String> candidates = new LinkedHashSet<>();
            for (List<String> combination : combinations) {
                if (!combination.containsAll(selected)) continue;
                for (String id : combination)
                    if (!selected.contains(id)) candidates.add(id);
            }
            if (candidates.isEmpty())
                throw new IllegalStateException("A weighted selection could not be completed.");
            long total = candidates.stream()
                    .map(config.modifiers()::get)
                    .mapToLong(WarzoneConfig.Modifier::weight)
                    .sum();
            long roll = random.nextLong(total);
            String choice = null;
            for (String id : candidates.stream().sorted().toList()) {
                roll -= config.modifiers().get(id).weight();
                if (roll < 0) {
                    choice = id;
                    break;
                }
            }
            if (choice == null) throw new IllegalStateException("Could not select a weighted modifier.");
            selected.add(choice);
            combinations = combinations.stream()
                    .filter(candidate -> candidate.containsAll(selected)).toList();
        }
        return selected.stream().sorted().toList();
    }

    private void enumerate(WarzoneConfig config, List<String> ids, int index,
                           List<String> current, List<List<String>> result) {
        int minimum = config.selection().minimum();
        int maximum = config.selection().maximum();
        if (current.size() >= minimum && current.size() <= maximum
                && isConflictFree(config, current)) {
            if (result.size() >= MAX_VALID_COMBINATIONS) {
                throw new IllegalStateException("Modifier selection exceeds "
                        + MAX_VALID_COMBINATIONS + " valid combinations; reduce the modifier count "
                        + "or rotation.selection.maximum.");
            }
            result.add(List.copyOf(current));
        }
        if (index >= ids.size() || current.size() >= maximum) return;
        for (int candidate = index; candidate < ids.size(); candidate++) {
            current.add(ids.get(candidate));
            if (isConflictFree(config, current))
                enumerate(config, ids, candidate + 1, current, result);
            current.removeLast();
        }
    }

    private boolean isConflictFree(WarzoneConfig config, List<String> ids) {
        Set<String> selected = Set.copyOf(ids);
        for (Set<String> group : config.conflictGroups().values()) {
            int matches = 0;
            for (String id : group)
                if (selected.contains(id) && ++matches > 1) return false;
        }
        WarzoneConfig.SpecialRule elytraRule = config.specialRules().get(ELYTRA);
        if (selected.contains(ELYTRA)) {
            if (elytraRule != null && elytraRule.weeklyInclusionChancePercent() == 0) return false;
            if (elytraRule != null && elytraRule.unrestrictedMaceChancePercent() == 100
                    && restrictsMace(config, ids)) return false;
        }
        Map<RestrictionTarget, WarzoneConfig.Restriction> seen = new LinkedHashMap<>();
        for (String id : ids) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier == null || !modifier.enabled()) return false;
            for (Map.Entry<RestrictionTarget, WarzoneConfig.Restriction> entry
                    : modifier.restrictions().entrySet()) {
                WarzoneConfig.Restriction previous = seen.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) return false;
            }
        }
        return true;
    }

    private boolean percent(int chance) {
        return chance >= 100 || chance > 0 && random.nextInt(100) < chance;
    }

    public record SelectionResult(List<String> modifierIds, WarzoneConfig.ActiveSet activeSet,
                                  int validCombinationCount) {
        public SelectionResult {
            modifierIds = List.copyOf(modifierIds);
        }
    }
}
