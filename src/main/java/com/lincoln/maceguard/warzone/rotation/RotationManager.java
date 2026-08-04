package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/** Owns the background automatic slot and the single higher-priority manual override. */
public final class RotationManager {
    @FunctionalInterface public interface Transition {
        void accept(WarzoneConfig.ActiveSet previous, WarzoneConfig.ActiveSet current,
                    boolean announce);
    }
    @FunctionalInterface public interface Warning {
        void accept(WarzoneConfig.ActiveSet active, Duration remaining);
    }

    private volatile WarzoneControlConfig control;
    private final WarzoneStateStore store;
    private final Clock clock;
    private final ModifierSelector selector;
    private final Transition transition;
    private final Warning warning;
    private final RotationState storedState;
    private volatile RotationState state;
    private volatile ActiveSelection active;
    private volatile ActiveSelection automatic;
    private volatile boolean advancedDuringRestore;
    private volatile boolean scheduleEnabled;

    public RotationManager(WarzoneConfig config, WarzoneStateStore store, Clock clock,
                           Transition transition, Warning warning) {
        this(WarzoneControlConfig.legacy(config), store, clock,
                RandomGenerator.getDefault(), transition, warning);
    }

    public RotationManager(WarzoneConfig config, WarzoneStateStore store, Clock clock,
                           RandomGenerator random, Transition transition, Warning warning) {
        this(WarzoneControlConfig.legacy(config), store, clock, random, transition, warning);
    }

    public RotationManager(WarzoneControlConfig control, WarzoneStateStore store, Clock clock,
                           Transition transition, Warning warning) {
        this(control, store, clock, RandomGenerator.getDefault(), transition, warning);
    }

    public RotationManager(WarzoneControlConfig control, WarzoneStateStore store, Clock clock,
                           RandomGenerator random, Transition transition, Warning warning) {
        this.control = control;
        this.store = store;
        this.clock = clock;
        this.selector = new ModifierSelector(random);
        this.transition = transition;
        this.warning = warning;
        Optional<RotationState> loaded = store.load();
        this.storedState = loaded.orElse(null);
        Boolean persistedScheduleEnabled = loaded.map(RotationState::scheduleEnabledOverride)
                .orElse(null);
        this.scheduleEnabled = persistedScheduleEnabled != null
                ? persistedScheduleEnabled : control.schedule().enabled();
        restore(loaded.orElse(null));
        store.update(state);
    }

    public void tick() {
        long now = clock.millis();
        if (scheduleEnabled) refreshAutomatic(now, true);
        if (state.overrideActive() && state.overrideExpiresAtMillis() > 0
                && now >= state.overrideExpiresAtMillis()) {
            clearOverride(true);
            return;
        }
        if (state.overrideActive()
                && state.overrideDurationMode() == OverrideDurationMode.UNTIL_CLEARED) return;
        long transitionAt = nextEffectiveTransitionMillis();
        long remainingMillis = transitionAt - now;
        if (remainingMillis <= 0) return;
        Duration threshold = gameplay().warningTimes().stream()
                .filter(value -> value.toMillis() >= remainingMillis)
                .min(Duration::compareTo).orElse(null);
        if (threshold == null || state.emittedWarningsSeconds().contains(threshold.getSeconds())) return;
        Set<Long> emitted = new LinkedHashSet<>(state.emittedWarningsSeconds());
        emitted.add(threshold.getSeconds());
        state = state.withWarnings(emitted);
        store.update(state);
        warning.accept(active.activeSet(), threshold);
    }

    /** Compatibility alias: a manual random selection lasting to the next boundary. */
    public boolean skip() { return force(); }

    /** Compatibility alias: a manual random selection lasting to the next boundary. */
    public boolean force() {
        WarzoneConfig.ActiveSet selected = selector.select(gameplay(),
                Set.copyOf(active.activeSet().modifierIds())).activeSet();
        if (selected.modifierIds().equals(active.activeSet().modifierIds())) return false;
        return activateOverride(SelectionSourceType.RANDOM, null, selected,
                OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE, true);
    }

    /** Compatibility alias for the old /warzone set command. */
    public boolean set(List<String> modifierIds, boolean announce) {
        try {
            WarzoneConfig.ActiveSet target = selector.compose(gameplay(), modifierIds);
            if (target.modifierIds().equals(active.activeSet().modifierIds())) return false;
            return activateOverride(SelectionSourceType.CUSTOM_OVERRIDE, null, target,
                    OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE, announce);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public boolean activate(String id, boolean announce) { return set(List.of(id), announce); }

    /** Compatibility behavior: extend the currently effective manual expiry. */
    public boolean extend(Duration duration) {
        if (duration.isZero() || duration.isNegative()) return false;
        if (state.overrideActive()
                && state.overrideDurationMode() == OverrideDurationMode.UNTIL_CLEARED)
            throw new IllegalStateException("An indefinite override has no expiration to extend.");
        if (!state.overrideActive() && !scheduleEnabled)
            throw new IllegalStateException("Automatic schedule is disabled; there is no transition to extend.");
        long base = state.overrideActive() && state.overrideExpiresAtMillis() > 0
                ? state.overrideExpiresAtMillis() : state.automaticSlotEndMillis();
        long expires = Math.addExact(base, duration.toMillis());
        WarzoneConfig.ActiveSet current = active.activeSet();
        SelectionSourceType type = state.overrideActive()
                ? state.overrideSourceType() : SelectionSourceType.CUSTOM_OVERRIDE;
        String id = state.overrideActive() ? state.overrideSourceId() : null;
        state = state.withOverride(type, id, current.modifierIds(),
                OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE, clock.millis(), expires,
                state.selectionSequence() + 1);
        active = new ActiveSelection(type, id, current, true);
        store.update(state);
        return true;
    }

    public WarzoneConfig.ActiveSet previewKit(String kitId) {
        WarzoneControlConfig.Kit kit = control.kits().get(normalize(kitId));
        if (kit == null) throw new IllegalArgumentException("Unknown kit '" + kitId + "'.");
        if (!kit.enabled()) throw new IllegalArgumentException("Kit '" + kit.id() + "' is disabled.");
        return selector.composeExact(gameplay(), kit.modifierIds());
    }

    public WarzoneConfig.ActiveSet previewCustom(List<String> modifierIds) {
        return previewCustom(modifierIds, true);
    }

    public WarzoneConfig.ActiveSet previewCustom(List<String> modifierIds, boolean bypassCountLimits) {
        List<String> stable = stableIds(modifierIds);
        return bypassCountLimits ? selector.composeExact(gameplay(), stable)
                : selector.compose(gameplay(), stable);
    }

    public WarzoneConfig.ActiveSet previewAdd(String modifierId) {
        return previewAdd(modifierId, true);
    }

    public WarzoneConfig.ActiveSet previewAdd(String modifierId, boolean bypassCountLimits) {
        List<String> proposed = new ArrayList<>(active.activeSet().modifierIds());
        String normalized = normalize(modifierId);
        if (!proposed.contains(normalized)) proposed.add(normalized);
        return previewCustom(proposed, bypassCountLimits);
    }

    public WarzoneConfig.ActiveSet previewRemove(String modifierId) {
        return previewRemove(modifierId, true);
    }

    public WarzoneConfig.ActiveSet previewRemove(String modifierId, boolean bypassCountLimits) {
        List<String> proposed = new ArrayList<>(active.activeSet().modifierIds());
        proposed.remove(normalize(modifierId));
        return previewCustom(proposed, bypassCountLimits);
    }

    public boolean setKit(String kitId, OverrideDurationMode mode, boolean announce) {
        String normalized = normalize(kitId);
        return activateOverride(SelectionSourceType.KIT, normalized, previewKit(normalized), mode, announce);
    }

    public boolean setCustom(List<String> ids, OverrideDurationMode mode, boolean announce) {
        return activateOverride(SelectionSourceType.CUSTOM_OVERRIDE, null,
                previewCustom(ids), mode, announce);
    }

    public boolean addModifier(String id, OverrideDurationMode mode, boolean announce) {
        return activateOverride(SelectionSourceType.CUSTOM_OVERRIDE, null,
                previewAdd(id), mode, announce);
    }

    public boolean removeModifier(String id, OverrideDurationMode mode, boolean announce) {
        return activateOverride(SelectionSourceType.CUSTOM_OVERRIDE, null,
                previewRemove(id), mode, announce);
    }

    public boolean clearModifiers(OverrideDurationMode mode, boolean announce) {
        return activateOverride(SelectionSourceType.CUSTOM_OVERRIDE, null,
                selector.composeExact(gameplay(), List.of()), mode, announce);
    }

    public WarzoneConfig.ActiveSet previewRandom() {
        return selector.select(gameplay(), Set.copyOf(active.activeSet().modifierIds())).activeSet();
    }

    public boolean random(OverrideDurationMode mode, boolean announce) {
        return activateOverride(SelectionSourceType.RANDOM, null, previewRandom(), mode, announce);
    }

    public boolean applyPrepared(SelectionSourceType type, String sourceId, List<String> ids,
                                 OverrideDurationMode mode, boolean announce) {
        WarzoneConfig.ActiveSet prepared = switch (type) {
            case KIT -> previewKit(sourceId);
            case RANDOM -> selector.compose(gameplay(), stableIds(ids));
            case CUSTOM_OVERRIDE -> previewCustom(ids);
            default -> throw new IllegalArgumentException(
                    "Source type " + type + " is not valid for a manual override.");
        };
        String validatedId = type == SelectionSourceType.KIT ? normalize(sourceId) : null;
        return activateOverride(type, validatedId, prepared, mode, announce);
    }

    public boolean clearOverride(boolean announce) {
        if (!state.overrideActive()) return false;
        WarzoneConfig.ActiveSet previous = active.activeSet();
        state = state.withoutOverride(state.selectionSequence() + 1);
        active = automatic;
        store.update(state);
        if (!previous.modifierIds().equals(active.activeSet().modifierIds()))
            transition.accept(previous, active.activeSet(), announce);
        return true;
    }

    public boolean advanceSchedule(boolean announce) {
        if (!scheduleEnabled)
            throw new IllegalStateException("Automatic schedule is disabled; enable it before advancing.");
        int nextOffset = Math.addExact(state.cyclePhaseOffset(), 1);
        RepeatingSchedule.Slot current = effectiveSlot(rawSchedule().slotAt(clock.instant()), nextOffset);
        Resolved resolved = resolve(current.entry(), state.automaticModifierIds());
        WarzoneConfig.ActiveSet previous = active.activeSet();
        automatic = new ActiveSelection(resolved.type(), resolved.id(), resolved.set(), false);
        state = new RotationState(RotationState.VERSION, current.identity(), current.index(),
                current.cycleIndex(), nextOffset, state.scheduleEnabledOverride(),
                current.start().toEpochMilli(), current.end().toEpochMilli(), clock.millis(),
                resolved.type(), resolved.id(), resolved.set().modifierIds(),
                state.overrideSourceType(), state.overrideSourceId(), state.overrideModifierIds(),
                state.overrideDurationMode(), state.overrideActivatedAtMillis(),
                state.overrideExpiresAtMillis(), Set.of(), state.selectionSequence() + 1);
        if (!state.overrideActive()) active = automatic;
        store.update(state);
        if (!state.overrideActive() && !previous.modifierIds().equals(active.activeSet().modifierIds()))
            transition.accept(previous, active.activeSet(), announce);
        return true;
    }

    private boolean activateOverride(SelectionSourceType type, String id,
                                     WarzoneConfig.ActiveSet target,
                                     OverrideDurationMode mode, boolean announce) {
        if (mode == null) throw new IllegalArgumentException("Override duration is required.");
        if (mode == OverrideDurationMode.UNTIL_NEXT_SCHEDULED_CHANGE && !scheduleEnabled)
            throw new IllegalStateException(
                    "Until-next duration is unavailable while the automatic schedule is disabled.");
        long now = clock.millis();
        long expires = switch (mode) {
            case ONE_HOUR -> Math.addExact(now, Duration.ofHours(1).toMillis());
            case UNTIL_NEXT_SCHEDULED_CHANGE -> state.automaticSlotEndMillis();
            case UNTIL_CLEARED -> 0;
        };
        WarzoneConfig.ActiveSet previous = active.activeSet();
        state = state.withOverride(type, id, target.modifierIds(), mode, now, expires,
                state.selectionSequence() + 1);
        active = new ActiveSelection(type, id, target, true);
        store.update(state);
        if (!previous.modifierIds().equals(target.modifierIds()))
            transition.accept(previous, target, announce);
        return true;
    }

    private void restore(RotationState candidate) {
        long now = clock.millis();
        if (candidate != null && !scheduleEnabled && restoreFrozenAutomatic(candidate, now)) return;
        int phaseOffset = candidate == null ? 0 : candidate.cyclePhaseOffset();
        RepeatingSchedule.Slot slot = effectiveSlot(rawSchedule().slotAt(clock.instant()), phaseOffset);
        Resolved resolved = restoreAutomatic(candidate, slot);
        automatic = new ActiveSelection(resolved.type(), resolved.id(), resolved.set(), false);
        boolean slotAdvanced = candidate != null
                && candidate.automaticSlotStartMillis() > 0
                && !sameAutomaticSlot(candidate, slot);
        advancedDuringRestore = slotAdvanced;
        state = new RotationState(RotationState.VERSION, slot.identity(), slot.index(), slot.cycleIndex(),
                phaseOffset, candidate == null ? null : candidate.scheduleEnabledOverride(),
                slot.start().toEpochMilli(), slot.end().toEpochMilli(),
                candidate != null && !slotAdvanced && candidate.automaticActivatedAtMillis() > 0
                        ? candidate.automaticActivatedAtMillis() : now,
                resolved.type(), resolved.id(), resolved.set().modifierIds(),
                null, null, List.of(), null, 0, 0,
                candidate == null || slotAdvanced ? Set.of() : candidate.emittedWarningsSeconds(),
                candidate == null ? 1 : Math.max(1, candidate.selectionSequence()));
        active = automatic;
        restoreOverride(candidate, now);
    }


    private boolean restoreFrozenAutomatic(RotationState candidate, long now) {
        try {
            WarzoneConfig.ActiveSet restored = composePersistedAutomatic(
                    candidate.automaticSourceType(), candidate.automaticSourceId(),
                    candidate.automaticModifierIds());
            automatic = new ActiveSelection(candidate.automaticSourceType(),
                    candidate.automaticSourceId(), restored, false);
            state = new RotationState(RotationState.VERSION, candidate.automaticSlotIdentity(),
                    candidate.automaticSlotIndex(), candidate.currentCycleIndex(),
                    candidate.cyclePhaseOffset(), candidate.scheduleEnabledOverride(),
                    candidate.automaticSlotStartMillis(), candidate.automaticSlotEndMillis(),
                    candidate.automaticActivatedAtMillis(), candidate.automaticSourceType(),
                    candidate.automaticSourceId(), restored.modifierIds(), null, null, List.of(),
                    null, 0, 0, candidate.emittedWarningsSeconds(),
                    Math.max(1, candidate.selectionSequence()));
            active = automatic;
            restoreOverride(candidate, now);
            return true;
        } catch (IllegalArgumentException ex) {
            store.reportSemanticValidationFailure(ex.getMessage());
            return false;
        }
    }


    private boolean sameAutomaticSlot(RotationState candidate, RepeatingSchedule.Slot slot) {
        if (slot.identity().equals(candidate.automaticSlotIdentity())) return true;
        return candidate.automaticSlotIdentity().startsWith("legacy:")
                && candidate.automaticSlotStartMillis() == slot.start().toEpochMilli()
                && candidate.automaticSlotEndMillis() == slot.end().toEpochMilli();
    }

    private WarzoneConfig.ActiveSet composePersistedAutomatic(SelectionSourceType type,
                                                               String sourceId,
                                                               List<String> modifierIds) {
        return switch (type) {
            case RANDOM -> selector.compose(gameplay(), modifierIds);
            case KIT -> composePersistedKit(sourceId, modifierIds);
            case SCHEDULED_MODIFIERS -> selector.composeExact(gameplay(), modifierIds);
            case NONE -> {
                if (!modifierIds.isEmpty())
                    throw new IllegalArgumentException("Persisted NONE selection contains modifiers.");
                yield selector.composeExact(gameplay(), List.of());
            }
            case CUSTOM_OVERRIDE -> throw new IllegalArgumentException(
                    "CUSTOM_OVERRIDE cannot be restored as an automatic selection.");
        };
    }

    private WarzoneConfig.ActiveSet composePersistedKit(String sourceId, List<String> modifierIds) {
        String normalized = normalize(sourceId);
        WarzoneControlConfig.Kit kit = control.kits().get(normalized);
        if (kit == null || !kit.enabled())
            throw new IllegalArgumentException("Persisted kit '" + normalized + "' is unavailable.");
        WarzoneConfig.ActiveSet current = selector.composeExact(gameplay(), kit.modifierIds());
        if (!current.modifierIds().equals(stableIds(modifierIds)))
            throw new IllegalArgumentException("Persisted kit modifiers no longer match kit '"
                    + normalized + "'.");
        return current;
    }

    private Resolved restoreAutomatic(RotationState candidate, RepeatingSchedule.Slot slot) {
        boolean persistedRandomForSlot = candidate != null
                && sameAutomaticSlot(candidate, slot)
                && candidate.automaticSourceType() == SelectionSourceType.RANDOM
                && slot.entry().type() == WarzoneControlConfig.EntryType.RANDOM;
        if (persistedRandomForSlot) {
            try {
                WarzoneConfig.ActiveSet restored = selector.compose(
                        gameplay(), candidate.automaticModifierIds());
                return new Resolved(SelectionSourceType.RANDOM, null, restored);
            } catch (IllegalArgumentException ex) {
                store.reportSemanticValidationFailure(
                        "Persisted random selection was invalid for the current slot: " + ex.getMessage());
                // Invalid persisted random state is rerolled for this exact slot.
            }
        }
        return resolve(slot.entry(), candidate == null ? List.of() : candidate.automaticModifierIds());
    }

    private void restoreOverride(RotationState candidate, long now) {
        if (candidate == null || !candidate.overrideActive()) return;
        if (candidate.overrideExpiresAtMillis() > 0 && now >= candidate.overrideExpiresAtMillis()) return;
        try {
            WarzoneConfig.ActiveSet restored = switch (candidate.overrideSourceType()) {
                case RANDOM -> selector.compose(gameplay(), candidate.overrideModifierIds());
                case KIT -> composePersistedKit(candidate.overrideSourceId(),
                        candidate.overrideModifierIds());
                case CUSTOM_OVERRIDE -> selector.composeExact(gameplay(),
                        candidate.overrideModifierIds());
                default -> throw new IllegalArgumentException(
                        "Invalid persisted manual source " + candidate.overrideSourceType() + ".");
            };
            state = state.withOverride(candidate.overrideSourceType(), candidate.overrideSourceId(),
                    restored.modifierIds(), candidate.overrideDurationMode(),
                    candidate.overrideActivatedAtMillis(), candidate.overrideExpiresAtMillis(),
                    state.selectionSequence());
            active = new ActiveSelection(candidate.overrideSourceType(), candidate.overrideSourceId(),
                    restored, true);
        } catch (IllegalArgumentException ex) {
            store.reportSemanticValidationFailure(
                    "Persisted manual override was invalid: " + ex.getMessage());
            // Invalid override fails safely back to the currently due automatic slot.
        }
    }

    private void refreshAutomatic(long now, boolean announce) {
        RepeatingSchedule.Slot due = effectiveSlot(rawSchedule().slotAt(Instant.ofEpochMilli(now)),
                state.cyclePhaseOffset());
        if (due.identity().equals(state.automaticSlotIdentity())) return;
        WarzoneConfig.ActiveSet previous = active.activeSet();
        Resolved resolved = resolve(due.entry(), state.automaticModifierIds());
        automatic = new ActiveSelection(resolved.type(), resolved.id(), resolved.set(), false);
        state = new RotationState(RotationState.VERSION, due.identity(), due.index(), due.cycleIndex(),
                state.cyclePhaseOffset(), state.scheduleEnabledOverride(),
                due.start().toEpochMilli(), due.end().toEpochMilli(), now, resolved.type(), resolved.id(),
                resolved.set().modifierIds(), state.overrideSourceType(), state.overrideSourceId(),
                state.overrideModifierIds(), state.overrideDurationMode(), state.overrideActivatedAtMillis(),
                state.overrideExpiresAtMillis(), Set.of(), state.selectionSequence() + 1);
        store.update(state);
        if (!state.overrideActive()) {
            active = automatic;
            if (!previous.modifierIds().equals(active.activeSet().modifierIds()))
                transition.accept(previous, active.activeSet(), announce);
        }
    }

    private Resolved resolve(WarzoneControlConfig.Entry entry, List<String> previousAutomatic) {
        return switch (entry.type()) {
            case RANDOM -> {
                WarzoneConfig.ActiveSet selected = selector.select(gameplay(),
                        Set.copyOf(previousAutomatic)).activeSet();
                yield new Resolved(SelectionSourceType.RANDOM, null, selected);
            }
            case KIT -> {
                WarzoneControlConfig.Kit kit = control.kits().get(entry.kitId());
                if (kit == null || !kit.enabled())
                    throw new IllegalStateException("Scheduled kit '" + entry.kitId() + "' is unavailable.");
                yield new Resolved(SelectionSourceType.KIT, kit.id(),
                        selector.composeExact(gameplay(), kit.modifierIds()));
            }
            case MODIFIERS -> new Resolved(SelectionSourceType.SCHEDULED_MODIFIERS, null,
                    selector.composeExact(gameplay(), entry.modifierIds()));
            case NONE -> new Resolved(SelectionSourceType.NONE, null,
                    selector.composeExact(gameplay(), List.of()));
        };
    }

    public WarzoneConfig.ActiveSet active() { return active.activeSet(); }
    public ActiveSelection activeSelection() { return active; }
    public ActiveSelection automaticSelection() { return automatic; }
    public Duration remaining() {
        if (state.overrideActive()
                && state.overrideDurationMode() == OverrideDurationMode.UNTIL_CLEARED)
            return Duration.ZERO;
        return Duration.ofMillis(Math.max(0, nextEffectiveTransitionMillis() - clock.millis()));
    }
    public long nextEffectiveTransitionMillis() {
        if (state.overrideActive()) {
            return state.overrideExpiresAtMillis() > 0
                    ? state.overrideExpiresAtMillis() : state.automaticSlotEndMillis();
        }
        return state.automaticSlotEndMillis();
    }
    public WarzoneConfig config() { return gameplay(); }
    public WarzoneControlConfig controlConfig() { return control; }
    public RotationState state() { return state; }
    public boolean advancedDuringRestore() { return advancedDuringRestore; }
    public List<String> storedActiveModifierIds() {
        return storedState == null ? List.of() : storedState.activeModifierIds();
    }
    public RepeatingSchedule.Slot currentSlot() {
        return effectiveSlot(rawSchedule().slotAt(clock.instant()), state.cyclePhaseOffset());
    }
    public RepeatingSchedule.Slot nextSlot() {
        RepeatingSchedule.Slot current = rawSchedule().slotAt(clock.instant());
        return effectiveSlot(rawSchedule().slot(current.index() + 1), state.cyclePhaseOffset());
    }
    public boolean scheduleEnabled() { return scheduleEnabled; }
    public long nowMillis() { return clock.millis(); }
    public void setScheduleEnabled(boolean enabled) {
        if (enabled && control.schedule().cycle().isEmpty())
            throw new IllegalStateException("Automatic schedule cannot be enabled with an empty cycle.");
        scheduleEnabled = enabled;
        state = state.withScheduleEnabledOverride(enabled, state.selectionSequence() + 1);
        store.update(state);
        if (enabled) refreshAutomatic(clock.millis(), true);
    }
    public WarzoneStateStore store() { return store; }

    public String entryName(WarzoneControlConfig.Entry entry) {
        return switch (entry.type()) {
            case RANDOM -> "Random selection";
            case KIT -> Optional.ofNullable(control.kits().get(entry.kitId()))
                    .map(WarzoneControlConfig.Kit::displayName).orElse(entry.kitId());
            case MODIFIERS -> String.join(", ", entry.modifierIds());
            case NONE -> "No modifiers";
        };
    }

    private WarzoneConfig gameplay() { return control.gameplay(); }
    private RepeatingSchedule rawSchedule() {
        WarzoneControlConfig.Schedule configured = control.schedule();
        if (!configured.cycle().isEmpty()) return new RepeatingSchedule(configured);
        return new RepeatingSchedule(new WarzoneControlConfig.Schedule(false,
                configured.timezone(), configured.anchorDate(), configured.time(), configured.cadence(),
                List.of(WarzoneControlConfig.Entry.none())));
    }
    private RepeatingSchedule.Slot effectiveSlot(RepeatingSchedule.Slot raw, int phaseOffset) {
        List<WarzoneControlConfig.Entry> cycle = control.schedule().cycle().isEmpty()
                ? List.of(WarzoneControlConfig.Entry.none()) : control.schedule().cycle();
        int cycleIndex = Math.floorMod(raw.cycleIndex() + phaseOffset, cycle.size());
        return new RepeatingSchedule.Slot(raw.index(), cycleIndex, raw.start(), raw.end(),
                cycle.get(cycleIndex), raw.start().toEpochMilli() + ":" + cycleIndex + ":" + phaseOffset);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
    private static List<String> stableIds(List<String> ids) {
        return List.copyOf(new LinkedHashSet<>(ids.stream().map(RotationManager::normalize)
                .filter(value -> !value.isEmpty()).toList()));
    }

    private record Resolved(SelectionSourceType type, String id,
                            WarzoneConfig.ActiveSet set) { }

    public record ApplyResult(WarzoneConfig.ActiveSet previous,
                              WarzoneConfig.ActiveSet current, boolean replaced) { }
}
