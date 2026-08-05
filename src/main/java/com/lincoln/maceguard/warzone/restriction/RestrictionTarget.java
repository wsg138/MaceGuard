package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RestrictionTarget implements Comparable<RestrictionTarget> {
    public enum Kind { MATERIAL, SPEAR_GROUP, SPEAR_DAMAGE, SPEAR_LUNGE }

    private static final Set<Material> PROJECTILE_MATERIALS = Set.of(
            Material.ENDER_PEARL, Material.WIND_CHARGE, Material.SNOWBALL, Material.EGG,
            Material.SPLASH_POTION, Material.LINGERING_POTION, Material.EXPERIENCE_BOTTLE,
            Material.FIREWORK_ROCKET, Material.TRIDENT, Material.BOW, Material.CROSSBOW);

    public static final RestrictionTarget SPEAR = new RestrictionTarget("SPEAR", Kind.SPEAR_GROUP, null,
            Set.of(CooldownCapability.PROJECTILE, CooldownCapability.DIRECT_ATTACK));
    public static final RestrictionTarget SPEAR_DAMAGE = new RestrictionTarget("SPEAR_DAMAGE", Kind.SPEAR_DAMAGE, null,
            Set.of(CooldownCapability.DIRECT_ATTACK));
    public static final RestrictionTarget SPEAR_LUNGE = new RestrictionTarget("SPEAR_LUNGE", Kind.SPEAR_LUNGE, null,
            Set.of(CooldownCapability.LUNGE_EFFECT));

    private final String id;
    private final Kind kind;
    private final Material material;
    private final Set<CooldownCapability> cooldownCapabilities;

    private RestrictionTarget(String id, Kind kind, Material material, Set<CooldownCapability> cooldownCapabilities) {
        this.id = id;
        this.kind = kind;
        this.material = material;
        this.cooldownCapabilities = Set.copyOf(cooldownCapabilities);
    }

    public static Optional<RestrictionTarget> parse(String raw) {
        if (raw == null) return Optional.empty();
        String id = raw.trim().toUpperCase(Locale.ROOT);
        if (id.equals(SPEAR.id)) return Optional.of(SPEAR);
        if (id.equals(SPEAR_DAMAGE.id)) return Optional.of(SPEAR_DAMAGE);
        if (id.equals(SPEAR_LUNGE.id)) return Optional.of(SPEAR_LUNGE);
        try {
            Material material = Material.valueOf(id);
            return Optional.of(new RestrictionTarget(material.name(), Kind.MATERIAL, material,
                    capabilities(material)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Capability classification must remain usable in plain unit tests, before Paper installs
     * its runtime registries. Only actions with stable, registry-independent success events are
     * accepted here. Other arbitrary materials remain valid DISABLED targets but cannot use
     * COOLDOWN mode.
     */
    private static Set<CooldownCapability> capabilities(Material material) {
        EnumSet<CooldownCapability> result = EnumSet.noneOf(CooldownCapability.class);
        if (PROJECTILE_MATERIALS.contains(material) || isSpear(material))
            result.add(CooldownCapability.PROJECTILE);
        if (isDirectAttackMaterial(material)) result.add(CooldownCapability.DIRECT_ATTACK);
        if (result.isEmpty()) result.add(CooldownCapability.UNSUPPORTED);
        return result;
    }

    private static boolean isDirectAttackMaterial(Material material) {
        String name = material.name();
        return material == Material.MACE || material == Material.TRIDENT || isSpear(material)
                || name.endsWith("_SWORD") || name.endsWith("_AXE");
    }

    public boolean matches(Material candidate) {
        return switch (kind) {
            case MATERIAL -> material == candidate;
            case SPEAR_GROUP -> isSpear(candidate);
            case SPEAR_DAMAGE, SPEAR_LUNGE -> false;
        };
    }

    public static boolean isSpear(Material material) {
        return material != null && material.name().endsWith("_SPEAR");
    }

    public boolean effectOnly() { return kind == Kind.SPEAR_LUNGE; }
    public boolean combatCarryoverEligible() {
        if (kind != Kind.MATERIAL) return true;
        if (material == Material.COBWEB || material == Material.END_CRYSTAL
                || material == Material.RESPAWN_ANCHOR) return false;
        return material == Material.MACE || material == Material.ENDER_PEARL
                || material == Material.WIND_CHARGE || material == Material.TRIDENT
                || isSpear(material);
    }
    public boolean supportsCooldown() { return !cooldownCapabilities.contains(CooldownCapability.UNSUPPORTED); }
    public boolean supports(CooldownCapability capability) { return cooldownCapabilities.contains(capability); }
    public Set<CooldownCapability> cooldownCapabilities() { return cooldownCapabilities; }
    public String id() { return id; }
    public Kind kind() { return kind; }
    public Material material() { return material; }

    @Override public int compareTo(RestrictionTarget other) { return id.compareTo(other.id); }
    @Override public boolean equals(Object value) {
        return value instanceof RestrictionTarget other && id.equals(other.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
    @Override public String toString() { return id; }
}
