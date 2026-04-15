package com.lincoln.maceguard.core.model;

public record EndIslandExplosiveSettings(
        double tntPercent,
        double tntMinecartPercent,
        double respawnAnchorPercent,
        double bedPercent
) {

    public EndIslandExplosiveSettings {
        tntPercent = clamp(tntPercent);
        tntMinecartPercent = clamp(tntMinecartPercent);
        respawnAnchorPercent = clamp(respawnAnchorPercent);
        bedPercent = clamp(bedPercent);
    }

    public double scaleForTnt() {
        return tntPercent / 100.0D;
    }

    public double scaleForTntMinecart() {
        return tntMinecartPercent / 100.0D;
    }

    public double scaleForRespawnAnchor() {
        return respawnAnchorPercent / 100.0D;
    }

    public double scaleForBed() {
        return bedPercent / 100.0D;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(100.0D, value));
    }
}
