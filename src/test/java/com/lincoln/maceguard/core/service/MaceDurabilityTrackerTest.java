package com.lincoln.maceguard.core.service;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaceDurabilityTrackerTest {
    private final UUID attackerOne = UUID.randomUUID();
    private final UUID attackerTwo = UUID.randomUUID();
    private final UUID victimOne = UUID.randomUUID();
    private final UUID victimTwo = UUID.randomUUID();

    @Test
    void maceContextCapsEveryEquippedArmorSlotIndependently() {
        MaceDurabilityTracker tracker = new MaceDurabilityTracker();
        tracker.createContext(attackerOne, victimOne, "warzone", 2, EnumSet.allOf(MaceDurabilityTracker.ArmorSlot.class));

        for (MaceDurabilityTracker.ArmorSlot slot : MaceDurabilityTracker.ArmorSlot.values()) {
            assertEquals(2, tracker.claim(victimOne, slot).orElseThrow().cap());
        }
        assertTrue(tracker.claim(victimOne, MaceDurabilityTracker.ArmorSlot.HEAD).isEmpty());
    }

    @Test
    void contextsAreIsolatedByVictimAndPreserveTheirOwnCaps() {
        MaceDurabilityTracker tracker = new MaceDurabilityTracker();
        tracker.createContext(attackerOne, victimOne, "warzone", 2, EnumSet.of(MaceDurabilityTracker.ArmorSlot.HEAD));
        tracker.createContext(attackerTwo, victimTwo, "pit", 5, EnumSet.of(MaceDurabilityTracker.ArmorSlot.HEAD));

        assertEquals(2, tracker.claim(victimOne, MaceDurabilityTracker.ArmorSlot.HEAD).orElseThrow().cap());
        assertEquals(5, tracker.claim(victimTwo, MaceDurabilityTracker.ArmorSlot.HEAD).orElseThrow().cap());
    }

    @Test
    void multipleAttacksAgainstOneVictimRemainOrdered() {
        MaceDurabilityTracker tracker = new MaceDurabilityTracker();
        tracker.createContext(attackerOne, victimOne, "first", 2, EnumSet.of(MaceDurabilityTracker.ArmorSlot.HEAD));
        tracker.createContext(attackerTwo, victimOne, "second", 4, EnumSet.of(MaceDurabilityTracker.ArmorSlot.CHEST));

        assertEquals(2, tracker.claim(victimOne, MaceDurabilityTracker.ArmorSlot.HEAD).orElseThrow().cap());
        assertEquals(4, tracker.claim(victimOne, MaceDurabilityTracker.ArmorSlot.CHEST).orElseThrow().cap());
    }

    @Test
    void attackSnapshotAllowsMaceClassificationAfterHandChanges() {
        MaceDurabilityTracker tracker = new MaceDurabilityTracker();
        tracker.recordMaceAttackSnapshot(attackerOne, victimOne);

        assertTrue(tracker.consumeMaceAttackSnapshot(attackerOne, victimOne));
        assertFalse(tracker.consumeMaceAttackSnapshot(attackerOne, victimOne));
    }

    @Test
    void swordSnapshotCannotClassifyAHitAndSnapshotsExpireOnTheNextTick() {
        MaceDurabilityTracker tracker = new MaceDurabilityTracker();
        assertFalse(tracker.consumeMaceAttackSnapshot(attackerOne, victimOne));
        tracker.recordMaceAttackSnapshot(attackerOne, victimOne);
        tracker.advanceTick();
        assertFalse(tracker.consumeMaceAttackSnapshot(attackerOne, victimOne));
    }

    @Test
    void expiredAndClearedContextsCannotAffectLaterArmorDamage() {
        MaceDurabilityTracker tracker = new MaceDurabilityTracker();
        tracker.createContext(attackerOne, victimOne, "warzone", 2, EnumSet.of(MaceDurabilityTracker.ArmorSlot.HEAD));
        tracker.advanceTick();
        assertTrue(tracker.claim(victimOne, MaceDurabilityTracker.ArmorSlot.HEAD).isEmpty());

        tracker.createContext(attackerOne, victimOne, "warzone", 2, EnumSet.of(MaceDurabilityTracker.ArmorSlot.HEAD));
        tracker.clearPlayer(victimOne);
        Optional<MaceDurabilityTracker.HitContext> context = tracker.claim(victimOne, MaceDurabilityTracker.ArmorSlot.HEAD);
        assertTrue(context.isEmpty());
    }
}
