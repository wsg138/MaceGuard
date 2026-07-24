package com.lincoln.maceguard.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSafetyPolicyTest {
    @Test void enabledFalsePreventsAutomaticResetExecution() {
        assertFalse(RuntimeSafetyPolicy.allowsAutomaticReset(false));
    }

    @Test void enabledFalsePreventsSparseEditCancellationAndNewCobwebTracking() {
        assertFalse(RuntimeSafetyPolicy.allowsSparseOriginalInterception(false));
        assertFalse(RuntimeSafetyPolicy.allowsTemporaryTracking(false));
    }

    @Test void enabledFalseKeepsOnlySchedulePauseAvailable() {
        assertFalse(RuntimeSafetyPolicy.allowsCapture(false));
        assertFalse(RuntimeSafetyPolicy.allowsArm(false));
        assertFalse(RuntimeSafetyPolicy.allowsManualReset(false));
        assertFalse(RuntimeSafetyPolicy.allowsScheduleChange(false, true));
        assertTrue(RuntimeSafetyPolicy.allowsScheduleChange(false, false));
    }
}
