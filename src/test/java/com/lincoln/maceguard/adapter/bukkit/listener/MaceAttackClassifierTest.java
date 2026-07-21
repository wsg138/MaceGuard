package com.lincoln.maceguard.adapter.bukkit.listener;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaceAttackClassifierTest {
    private final MaceAttackClassifier classifier = new MaceAttackClassifier();

    @Test
    void damageSourceIsAuthoritativeForAttributeSwappedMaceSmashes() {
        assertTrue(classifier.isMaceSmash(NamespacedKey.minecraft("mace_smash")));
        assertEquals(MaceAttackClassifier.Source.DAMAGE_TYPE, classifier.classify(true, false, false));
    }

    @Test
    void normalMaceAttacksUseAttackTimeSnapshotBeforeHeldItemFallback() {
        assertEquals(MaceAttackClassifier.Source.PRE_ATTACK_SNAPSHOT, classifier.classify(false, true, false));
        assertEquals(MaceAttackClassifier.Source.HELD_ITEM_FALLBACK, classifier.classify(false, false, true));
        assertEquals(MaceAttackClassifier.Source.NONE, classifier.classify(false, false, false));
    }
}
