package com.lincoln.maceguard.adapter.bukkit.listener;

import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageType;

/** Keeps Bukkit/Paper damage-source classification separate from hit tracking. */
public final class MaceAttackClassifier {
    public enum Source { DAMAGE_TYPE, PRE_ATTACK_SNAPSHOT, NONE }

    public Source classify(boolean maceSmashDamageType, boolean macePreAttackSnapshot, boolean maceHeldItem) {
        if (maceSmashDamageType) {
            return Source.DAMAGE_TYPE;
        }
        // A pre-attack event can be emitted without a later successful hit. For
        // generic player attacks, require the live attack-time hand to still be
        // a mace so a stale failed-attempt snapshot cannot cap a sword hit.
        if (macePreAttackSnapshot && maceHeldItem) {
            return Source.PRE_ATTACK_SNAPSHOT;
        }
        return Source.NONE;
    }

    public boolean isMaceSmash(DamageType damageType) {
        return isMaceSmash(damageType.getKey());
    }

    public boolean isMaceSmash(NamespacedKey damageTypeKey) {
        // Compare the vanilla registry key. Referencing DamageType.MACE_SMASH in
        // a unit test initializes Paper's live registry, which is unavailable off-server.
        return NamespacedKey.MINECRAFT.equals(damageTypeKey.getNamespace()) && "mace_smash".equals(damageTypeKey.getKey());
    }
}
