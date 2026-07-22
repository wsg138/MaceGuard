package com.lincoln.maceguard.reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

public final class ResetPlanner {
    private final BlockStateCodec codec = new BlockStateCodec();
    public ResetPlan plan(Snapshot snapshot, List<SnapshotBlock> currentBlocks, Predicate<SnapshotBlock> excluded, int batchSize) {
        if (snapshot.blocks().size() != currentBlocks.size()) throw new IllegalArgumentException("Current state coverage differs from snapshot");
        List<ResetPlan.Change> changes = new ArrayList<>();
        long excludedCount = 0;
        int air = 0;
        int nonAir = 0;
        int entities = 0;
        for (int i = 0; i < snapshot.blocks().size(); i++) {
            SnapshotBlock target = snapshot.blocks().get(i);
            if (excluded.test(target)) { excludedCount++; continue; }
            SnapshotBlock current = currentBlocks.get(i);
            if (codec.sameState(target, current)) continue;
            changes.add(new ResetPlan.Change(target.x(), target.y(), target.z(), current, target));
            if (isAir(target.blockData())) air++; else nonAir++;
            if (target.blockEntity() != null) entities++;
        }
        String canonical = snapshot.regionId() + "|" + snapshot.geometryHash() + "|" + snapshot.checksum() + "|" + new com.google.gson.Gson().toJson(changes);
        String hash = sha256(canonical);
        return new ResetPlan(snapshot.regionId(), snapshot.worldUuid(), snapshot.geometryHash(), snapshot.checksum(),
                snapshot.blocks().size(), changes.size(), nonAir, air, entities, 0, excludedCount,
                changes.isEmpty() ? 0 : (changes.size() + batchSize - 1) / batchSize, hash, List.copyOf(changes));
    }

    public String refusal(ResetPlan plan, int maxTotal, int maxAir) {
        if (plan.totalChanges() > maxTotal) return "would change " + plan.totalChanges() + " blocks; configured maximum is " + maxTotal;
        if (plan.airChanges() > maxAir) return "would set " + plan.airChanges() + " blocks to air; configured maximum is " + maxAir;
        if (plan.unsupportedStates() > 0) return "contains " + plan.unsupportedStates() + " unsupported states";
        return null;
    }

    private boolean isAir(String value) { return value.equals("minecraft:air") || value.startsWith("minecraft:air["); }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
