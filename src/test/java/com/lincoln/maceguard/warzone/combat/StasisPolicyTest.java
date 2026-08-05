package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StasisPolicyTest {
    @Test void blocksOnlyAgedLatchedDeniedCombatPearls() {
        assertTrue(StasisPolicy.shouldBlock(true, true, false, false, true, true));
        assertFalse(StasisPolicy.shouldBlock(false, true, false, false, true, true));
        assertFalse(StasisPolicy.shouldBlock(true, false, false, false, true, true));
        assertFalse(StasisPolicy.shouldBlock(true, true, false, false, false, true));
        assertFalse(StasisPolicy.shouldBlock(true, true, false, false, true, false));
    }

    @Test void eitherExplicitBypassPreventsBlocking() {
        assertFalse(StasisPolicy.shouldBlock(true, true, true, false, true, true));
        assertFalse(StasisPolicy.shouldBlock(true, true, false, true, true, true));
    }
}
