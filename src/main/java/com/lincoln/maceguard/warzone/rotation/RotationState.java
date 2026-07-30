package com.lincoln.maceguard.warzone.rotation;

import java.util.Set;

public record RotationState(
        String activeRotationId,
        long startedAtMillis,
        long endsAtMillis,
        String nextRotationId,
        Set<Long> emittedWarningsSeconds
) {
    public RotationState {
        emittedWarningsSeconds = Set.copyOf(emittedWarningsSeconds);
    }
}
