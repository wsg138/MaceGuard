package com.lincoln.maceguard.reset;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class SnapshotChecksum {
    private static final Gson GSON = new Gson();
    private SnapshotChecksum() { }

    public static String calculate(List<SnapshotBlock> blocks) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GSON.toJson(blocks).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
