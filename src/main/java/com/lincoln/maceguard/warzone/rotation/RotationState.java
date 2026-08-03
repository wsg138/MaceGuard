package com.lincoln.maceguard.warzone.rotation;

import java.util.List;
import java.util.Set;

public record RotationState(
        List<String> activeModifierIds,
        long activatedAtMillis,
        long weeklyBoundaryMillis,
        long transitionAtMillis,
        Set<Long> emittedWarningsSeconds,
        long selectionSequence
) {
    public RotationState {
        activeModifierIds = List.copyOf(activeModifierIds);
        emittedWarningsSeconds = Set.copyOf(emittedWarningsSeconds);
    }
}
