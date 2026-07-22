package com.lincoln.maceguard.reset;

import com.google.gson.Gson;
import com.lincoln.maceguard.worldguard.RegionDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public final class SparseBaselineValidator {
    private static final Gson GSON = new Gson();

    public Validation validate(SparseBaseline baseline, RegionDescriptor region, String profile, String exclusionHash) {
        if (baseline == null) return new Validation(false, "sparse baseline missing");
        if (baseline.formatVersion() != SparseBaseline.FORMAT_VERSION) return new Validation(false, "unsupported sparse baseline format");
        if (!baseline.complete()) return new Validation(false, "sparse baseline incomplete");
        if (!region.worldUuid().toString().equals(baseline.worldUuid()) || !region.id().equals(baseline.regionId())) return new Validation(false, "sparse baseline ownership differs");
        if (!region.equals(baseline.geometry())) return new Validation(false, "sparse baseline geometry differs");
        if (!profile.equals(baseline.profile()) || !exclusionHash.equals(baseline.exclusionHash())) return new Validation(false, "sparse baseline profile or exclusions differ");
        for (Map.Entry<String, SnapshotBlock> entry : baseline.originals().entrySet()) {
            SnapshotBlock block = entry.getValue();
            if (!entry.getKey().equals(SparseBaseline.coordinateKey(block.x(), block.y(), block.z())) || !region.contains(block.x(), block.y(), block.z()))
                return new Validation(false, "sparse baseline contains an invalid coordinate");
        }
        if (!checksum(baseline.originals()).equals(baseline.checksum())) return new Validation(false, "sparse baseline checksum mismatch");
        return new Validation(true, "valid");
    }

    public String checksum(Map<String, SnapshotBlock> originals) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GSON.toJson(new java.util.TreeMap<>(originals)).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public record Validation(boolean valid, String reason) { }
}
