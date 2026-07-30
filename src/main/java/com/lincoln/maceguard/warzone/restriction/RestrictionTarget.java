package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class RestrictionTarget implements Comparable<RestrictionTarget> {
    public enum Kind { MATERIAL, SPEAR_GROUP, SPEAR_LUNGE }

    public static final RestrictionTarget SPEAR = new RestrictionTarget("SPEAR", Kind.SPEAR_GROUP, null);
    public static final RestrictionTarget SPEAR_LUNGE = new RestrictionTarget("SPEAR_LUNGE", Kind.SPEAR_LUNGE, null);

    private final String id;
    private final Kind kind;
    private final Material material;

    private RestrictionTarget(String id, Kind kind, Material material) {
        this.id = id;
        this.kind = kind;
        this.material = material;
    }

    public static Optional<RestrictionTarget> parse(String raw) {
        if (raw == null) return Optional.empty();
        String id = raw.trim().toUpperCase(Locale.ROOT);
        if (id.equals(SPEAR.id)) return Optional.of(SPEAR);
        if (id.equals(SPEAR_LUNGE.id)) return Optional.of(SPEAR_LUNGE);
        try {
            Material material = Material.valueOf(id);
            return Optional.of(new RestrictionTarget(material.name(), Kind.MATERIAL, material));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public boolean matches(Material candidate) {
        return switch (kind) {
            case MATERIAL -> material == candidate;
            case SPEAR_GROUP -> isSpear(candidate);
            case SPEAR_LUNGE -> false;
        };
    }

    public static boolean isSpear(Material material) {
        return material != null && material.name().endsWith("_SPEAR");
    }

    public boolean effectOnly() { return kind == Kind.SPEAR_LUNGE; }
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
