package com.lincoln.maceguard.adapter.bukkit.listener;

import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageType;

/** Keeps Bukkit/Paper damage-source classification separate from hit tracking. */
final class MaceAttackClassifier {
    enum Source { DAMAGE_TYPE, PRE_ATTACK_SNAPSHOT, HELD_ITEM_FALLBACK, NONE }

    Source classify(boolean maceSmashDamageType, boolean macePreAttackSnapshot, boolean maceHeldItem) {
        if (maceSmashDamageType) {
            return Source.DAMAGE_TYPE;
        }
        if (macePreAttackSnapshot) {
            return Source.PRE_ATTACK_SNAPSHOT;
        }
        return maceHeldItem ? Source.HELD_ITEM_FALLBACK : Source.NONE;
    }

    boolean isMaceSmash(DamageType damageType) {
        return isMaceSmash(damageType.getKey());
    }

    boolean isMaceSmash(NamespacedKey damageTypeKey) {
        // Compare the vanilla registry key. Referencing DamageType.MACE_SMASH in
        // a unit test initializes Paper's live registry, which is unavailable off-server.
        return NamespacedKey.MINECRAFT.equals(damageTypeKey.getNamespace()) && "mace_smash".equals(damageTypeKey.getKey());
    }
}
