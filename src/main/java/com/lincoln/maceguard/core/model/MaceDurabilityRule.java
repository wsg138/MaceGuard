package com.lincoln.maceguard.core.model;

public record MaceDurabilityRule(boolean enabled, int damagePerArmorPiece) {

    public static final MaceDurabilityRule DISABLED = new MaceDurabilityRule(false, 0);

    public MaceDurabilityRule {
        if (damagePerArmorPiece < 0) {
            throw new IllegalArgumentException("damagePerArmorPiece cannot be negative");
        }
    }

    public int cappedDamage(int vanillaDamage) {
        if (!enabled) {
            return vanillaDamage;
        }
        if (damagePerArmorPiece <= 0) {
            return 0;
        }
        return Math.min(vanillaDamage, damagePerArmorPiece);
    }
}
