package com.lincoln.maceguard.warzone.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ReloadGuardTest {
    @Test void invalidReloadPreservesPreviousRuntimeWithoutBuildingReplacement() {
        Object current = new Object();
        AtomicBoolean invoked = new AtomicBoolean();
        ReloadGuard.Result<Object> result = ReloadGuard.prepare(current, false, () -> {
            invoked.set(true);
            return new Object();
        });
        assertFalse(result.accepted());
        assertSame(current, result.value());
        assertFalse(invoked.get());
    }
}
