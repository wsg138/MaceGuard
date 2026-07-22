package com.lincoln.maceguard.worldguard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class RegionGeometryFingerprint {
    private RegionGeometryFingerprint() { }

    public static String hash(String id, UUID worldUuid, String type, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        String canonical = id + "\n" + worldUuid + "\n" + type + "\n" + minX + "," + minY + "," + minZ + "\n" + maxX + "," + maxY + "," + maxZ;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
