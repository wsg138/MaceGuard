package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

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

    private final RandomGenerator random;

    public ModifierSelector(RandomGenerator random) {
        this.random = random;
    }

    public SelectionResult select(WarzoneConfig config, Set<String> previous) {
        List<List<String>> combinations = validCombinations(config);
        if (combinations.isEmpty())
            throw new IllegalStateException("No valid modifier combinations are configured.");
        List<List<String>> choices = combinations;
        if (config.selection().preventIdenticalRepeat() && combinations.size() > 1) {
            List<List<String>> filtered = combinations.stream()
                    .filter(candidate -> !new LinkedHashSet<>(candidate).equals(previous))
                    .toList();
            if (!filtered.isEmpty()) choices = filtered;
        }
        List<String> selected = choices.get(random.nextInt(choices.size()));
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
        Map<com.lincoln.maceguard.warzone.restriction.RestrictionTarget, WarzoneConfig.Restriction> restrictions =
                new LinkedHashMap<>();
        Set<WarzoneConfig.Effect> effects = new LinkedHashSet<>();
        List<String> displays = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (String id : normalized) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier == null)
                throw new IllegalArgumentException("Unknown modifier '" + id + "'.");
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
            throw new IllegalArgumentException("Selected modifiers violate a mutual-exclusion group.");
        return new WarzoneConfig.ActiveSet(normalized,
                String.join(" <gray>+ </gray>", displays),
                String.join(" ", descriptions), effects, restrictions);
    }

    public List<List<String>> validCombinations(WarzoneConfig config) {
        List<String> ids = config.modifiers().keySet().stream()
                .sorted(Comparator.naturalOrder()).toList();
        if (ids.size() > MAX_CONFIGURED_MODIFIERS) {
            throw new IllegalStateException("Modifier selection supports at most "
                    + MAX_CONFIGURED_MODIFIERS + " configured modifiers.");
        }
        List<List<String>> result = new ArrayList<>();
        enumerate(config, ids, 0, new ArrayList<>(), result);
        return List.copyOf(result);
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
        Map<com.lincoln.maceguard.warzone.restriction.RestrictionTarget, WarzoneConfig.Restriction> seen =
                new LinkedHashMap<>();
        for (String id : ids) {
            WarzoneConfig.Modifier modifier = config.modifiers().get(id);
            if (modifier == null) return false;
            for (Map.Entry<com.lincoln.maceguard.warzone.restriction.RestrictionTarget, WarzoneConfig.Restriction> entry
                    : modifier.restrictions().entrySet()) {
                WarzoneConfig.Restriction previous = seen.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) return false;
            }
        }
        return true;
    }

    public record SelectionResult(List<String> modifierIds, WarzoneConfig.ActiveSet activeSet,
                                  int validCombinationCount) {
        public SelectionResult {
            modifierIds = List.copyOf(modifierIds);
        }
    }
}
