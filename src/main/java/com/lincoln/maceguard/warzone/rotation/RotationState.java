package com.lincoln.maceguard.warzone.rotation;

import java.util.List;
import java.util.Set;

/** Versioned persisted automatic slot plus an optional manual override. */
public record RotationState(
        int stateVersion,
        String automaticSlotIdentity,
        long automaticSlotIndex,
        int currentCycleIndex,
        int cyclePhaseOffset,
        Boolean scheduleEnabledOverride,
        long automaticSlotStartMillis,
        long automaticSlotEndMillis,
        long automaticActivatedAtMillis,
        SelectionSourceType automaticSourceType,
        String automaticSourceId,
        List<String> automaticModifierIds,
        SelectionSourceType overrideSourceType,
        String overrideSourceId,
        List<String> overrideModifierIds,
        OverrideDurationMode overrideDurationMode,
        long overrideActivatedAtMillis,
        long overrideExpiresAtMillis,
        Set<Long> emittedWarningsSeconds,
        long selectionSequence
) {
    public static final int VERSION = 3;

    public RotationState {
        automaticSlotIdentity = automaticSlotIdentity == null ? "" : automaticSlotIdentity;
        automaticModifierIds = List.copyOf(automaticModifierIds);
        overrideModifierIds = List.copyOf(overrideModifierIds);
        emittedWarningsSeconds = Set.copyOf(emittedWarningsSeconds);
    }

    public boolean overrideActive() { return overrideSourceType != null; }
    public List<String> activeModifierIds() {
        return overrideActive() ? overrideModifierIds : automaticModifierIds;
    }
    public SelectionSourceType activeSourceType() {
        return overrideActive() ? overrideSourceType : automaticSourceType;
    }
    public String activeSourceId() {
        return overrideActive() ? overrideSourceId : automaticSourceId;
    }
    public long activatedAtMillis() {
        return overrideActive() ? overrideActivatedAtMillis : automaticActivatedAtMillis;
    }
    /** Compatibility accessor retained for existing integrations. */
    public long weeklyBoundaryMillis() { return automaticSlotStartMillis; }
    /** The next point at which the final active set can change. */
    public long transitionAtMillis() {
        return overrideActive() && overrideExpiresAtMillis > 0
                ? overrideExpiresAtMillis : automaticSlotEndMillis;
    }

    public RotationState withWarnings(Set<Long> warnings) {
        return copy(automaticSourceType, automaticSourceId, automaticModifierIds,
                overrideSourceType, overrideSourceId, overrideModifierIds, overrideDurationMode,
                overrideActivatedAtMillis, overrideExpiresAtMillis, warnings, selectionSequence,
                cyclePhaseOffset, scheduleEnabledOverride);
    }

    public RotationState withOverride(SelectionSourceType sourceType, String sourceId,
                                      List<String> modifierIds, OverrideDurationMode mode,
                                      long activatedAt, long expiresAt, long sequence) {
        return copy(automaticSourceType, automaticSourceId, automaticModifierIds,
                sourceType, sourceId, modifierIds, mode, activatedAt, expiresAt,
                Set.of(), sequence, cyclePhaseOffset, scheduleEnabledOverride);
    }

    public RotationState withoutOverride(long sequence) {
        return copy(automaticSourceType, automaticSourceId, automaticModifierIds,
                null, null, List.of(), null, 0, 0, Set.of(), sequence,
                cyclePhaseOffset, scheduleEnabledOverride);
    }

    public RotationState withScheduleEnabledOverride(boolean enabled, long sequence) {
        return copy(automaticSourceType, automaticSourceId, automaticModifierIds,
                overrideSourceType, overrideSourceId, overrideModifierIds, overrideDurationMode,
                overrideActivatedAtMillis, overrideExpiresAtMillis, emittedWarningsSeconds, sequence,
                cyclePhaseOffset, enabled);
    }

    private RotationState copy(SelectionSourceType automaticType, String automaticId,
                               List<String> automaticModifiers, SelectionSourceType manualType,
                               String manualId, List<String> manualModifiers,
                               OverrideDurationMode manualMode, long manualActivated,
                               long manualExpires, Set<Long> warnings, long sequence,
                               int phaseOffset, Boolean enabledOverride) {
        return new RotationState(stateVersion, automaticSlotIdentity, automaticSlotIndex,
                currentCycleIndex, phaseOffset, enabledOverride,
                automaticSlotStartMillis, automaticSlotEndMillis, automaticActivatedAtMillis,
                automaticType, automaticId, automaticModifiers, manualType, manualId,
                manualModifiers, manualMode, manualActivated, manualExpires, warnings, sequence);
    }
}
