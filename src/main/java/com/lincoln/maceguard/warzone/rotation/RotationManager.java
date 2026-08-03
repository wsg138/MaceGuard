package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class RotationManager {
    @FunctionalInterface public interface Transition {
        void accept(WarzoneConfig.ActiveSet previous, WarzoneConfig.ActiveSet current, boolean announce);
    }
    @FunctionalInterface public interface Warning {
        void accept(WarzoneConfig.ActiveSet active, Duration remaining);
    }

    private volatile WarzoneConfig config;
    private final WarzoneStateStore store;
    private final Clock clock;
    private final ModifierSelector selector;
    private final Transition transition;
    private final Warning warning;
    private final RotationState storedState;
    private volatile RotationState state;
    private volatile WarzoneConfig.ActiveSet active;
    private volatile boolean advancedDuringRestore;

    public RotationManager(WarzoneConfig config, WarzoneStateStore store, Clock clock,
                           Transition transition, Warning warning) {
        this(config, store, clock, RandomGenerator.getDefault(), transition, warning);
    }

    public RotationManager(WarzoneConfig config, WarzoneStateStore store, Clock clock, RandomGenerator random,
                           Transition transition, Warning warning) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.selector = new ModifierSelector(random);
        this.transition = transition;
        this.warning = warning;
        Optional<RotationState> loaded = store.load();
        this.storedState = loaded.orElse(null);
        restore(loaded);
        store.update(state);
    }

    public void tick() {
        long now = clock.millis();
        if (now >= state.transitionAtMillis()) {
            naturalTransition(now, true);
            return;
        }
        long remainingMillis = state.transitionAtMillis() - now;
        Duration threshold = config.warningTimes().stream()
                .filter(value -> value.toMillis() >= remainingMillis)
                .min(Duration::compareTo).orElse(null);
        if (threshold == null || state.emittedWarningsSeconds().contains(threshold.getSeconds())) return;
        Set<Long> emitted = new LinkedHashSet<>(state.emittedWarningsSeconds());
        emitted.add(threshold.getSeconds());
        state = new RotationState(state.activeModifierIds(), state.activatedAtMillis(),
                state.weeklyBoundaryMillis(), state.transitionAtMillis(), emitted, state.selectionSequence());
        store.update(state);
        warning.accept(active, threshold);
    }

    public boolean skip() { return force(); }

    public boolean force() {
        WarzoneConfig.ActiveSet previous = active;
        ModifierSelector.SelectionResult selected = selector.select(config, Set.copyOf(state.activeModifierIds()));
        activate(selected.activeSet(), clock.millis(), state.weeklyBoundaryMillis(),
                state.transitionAtMillis(), state.selectionSequence() + 1, true);
        return !previous.modifierIds().equals(active.modifierIds());
    }

    public boolean set(List<String> modifierIds, boolean announce) {
        WarzoneConfig.ActiveSet target;
        try { target = selector.compose(config, modifierIds); }
        catch (IllegalArgumentException ex) { return false; }
        WarzoneConfig.ActiveSet previous = active;
        if (previous.modifierIds().equals(target.modifierIds())) return false;
        activate(target, clock.millis(), state.weeklyBoundaryMillis(), state.transitionAtMillis(),
                state.selectionSequence() + 1, announce);
        return true;
    }

    public boolean activate(String id, boolean announce) {
        return set(List.of(id), announce);
    }

    public boolean extend(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return false;
        long extended = Math.addExact(state.transitionAtMillis(), duration.toMillis());
        state = new RotationState(state.activeModifierIds(), state.activatedAtMillis(),
                state.weeklyBoundaryMillis(), extended, Set.of(), state.selectionSequence());
        store.update(state);
        return true;
    }

    public ApplyResult applyConfig(WarzoneConfig proposed) {
        WarzoneConfig.ActiveSet previous = active;
        WarzoneConfig previousConfig = config;
        config = proposed;
        WeeklySchedule proposedSchedule = new WeeklySchedule(proposed.schedule());
        Instant now = clock.instant();
        long currentBoundary = proposedSchedule.previousBoundaryAtOrBefore(now).toEpochMilli();
        long proposedNextBoundary = proposedSchedule.nextBoundaryAfter(now).toEpochMilli();
        boolean usedNaturalTransition = state.transitionAtMillis()
                == new WeeklySchedule(previousConfig.schedule()).nextBoundaryAfter(now).toEpochMilli();
        boolean replaced;
        try {
            active = selector.compose(proposed, state.activeModifierIds());
            state = new RotationState(active.modifierIds(), state.activatedAtMillis(), currentBoundary,
                    usedNaturalTransition ? proposedNextBoundary : state.transitionAtMillis(),
                    state.emittedWarningsSeconds(), state.selectionSequence());
            replaced = false;
        } catch (IllegalArgumentException ex) {
            ModifierSelector.SelectionResult selected = selector.select(proposed, Set.copyOf(state.activeModifierIds()));
            active = selected.activeSet();
            state = new RotationState(active.modifierIds(), clock.millis(), currentBoundary,
                    proposedNextBoundary, Set.of(), state.selectionSequence() + 1);
            replaced = true;
        }
        store.update(state);
        return new ApplyResult(previous, active, replaced);
    }

    public WarzoneConfig.ActiveSet active() { return active; }
    public Duration remaining() {
        return Duration.ofMillis(Math.max(0, state.transitionAtMillis() - clock.millis()));
    }
    public WarzoneConfig config() { return config; }
    public RotationState state() { return state; }
    public boolean advancedDuringRestore() { return advancedDuringRestore; }
    public List<String> storedActiveModifierIds() {
        return storedState == null ? List.of() : storedState.activeModifierIds();
    }

    private void restore(Optional<RotationState> loaded) {
        WeeklySchedule weekly = new WeeklySchedule(config.schedule());
        long now = clock.millis();
        if (loaded.isPresent()) {
            RotationState candidate = loaded.get();
            try {
                WarzoneConfig.ActiveSet restored = selector.compose(config, candidate.activeModifierIds());
                if (candidate.activatedAtMillis() > 0 && candidate.transitionAtMillis() > candidate.activatedAtMillis()
                        && candidate.weeklyBoundaryMillis() > 0) {
                    state = candidate;
                    active = restored;
                    if (now >= state.transitionAtMillis()) {
                        naturalTransition(now, false);
                        advancedDuringRestore = true;
                    }
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid or stale state is replaced with a fresh current-week selection.
            }
        }
        Instant current = clock.instant();
        Instant boundary = weekly.previousBoundaryAtOrBefore(current);
        Instant next = weekly.nextBoundaryAfter(current);
        ModifierSelector.SelectionResult selected = selector.select(config, Set.of());
        state = new RotationState(selected.modifierIds(), current.toEpochMilli(), boundary.toEpochMilli(),
                next.toEpochMilli(), Set.of(), 1);
        active = selected.activeSet();
    }

    private void naturalTransition(long now, boolean announce) {
        WeeklySchedule weekly = new WeeklySchedule(config.schedule());
        WarzoneConfig.ActiveSet previous = active;
        ModifierSelector.SelectionResult selected = selector.select(config, Set.copyOf(state.activeModifierIds()));
        Instant nowInstant = Instant.ofEpochMilli(now);
        Instant boundary = weekly.previousBoundaryAtOrBefore(nowInstant);
        Instant next = weekly.nextBoundaryAfter(nowInstant);
        activate(selected.activeSet(), now, boundary.toEpochMilli(), next.toEpochMilli(),
                state.selectionSequence() + 1, announce);
        if (!announce) active = selected.activeSet();
        if (announce && previous.modifierIds().equals(active.modifierIds())) {
            // A repeat is possible only when no different valid combination exists.
        }
    }

    private void activate(WarzoneConfig.ActiveSet target, long activatedAt, long weeklyBoundary,
                          long transitionAt, long sequence, boolean announce) {
        WarzoneConfig.ActiveSet previous = active;
        active = target;
        state = new RotationState(target.modifierIds(), activatedAt, weeklyBoundary, transitionAt, Set.of(), sequence);
        store.update(state);
        if (previous != null) transition.accept(previous, target, announce);
    }

    public record ApplyResult(WarzoneConfig.ActiveSet previous, WarzoneConfig.ActiveSet current, boolean replaced) { }
}
