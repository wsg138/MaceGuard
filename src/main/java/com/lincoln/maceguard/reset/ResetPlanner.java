package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.config.ResetProfile;
import org.bukkit.Material;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

public final class ResetPlanner {
    private final BlockStateCodec codec = new BlockStateCodec();

    public ResetPlan plan(Snapshot snapshot, List<SnapshotBlock> currentBlocks,
                          Predicate<SnapshotBlock> excluded, int batchSize) {
        ResetProfile.Mode mode;
        try { mode = ResetProfile.Mode.valueOf(snapshot.resetMode()); }
        catch (IllegalArgumentException ex) { mode = ResetProfile.Mode.FULL_SNAPSHOT; }
        ResetProfile profile = new ResetProfile(snapshot.resetProfile(), mode, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                java.util.Set.of(), java.util.Set.of(),
                ResetProfile.SolidConflictPolicy.SKIP_AND_REPORT, java.util.List.of());
        return plan(snapshot, currentBlocks, excluded, batchSize, profile);
    }

    public ResetPlan plan(Snapshot snapshot, List<SnapshotBlock> currentBlocks,
                          Predicate<SnapshotBlock> excluded, int batchSize,
                          ResetProfile profile) {
        if (snapshot.blocks().size() != currentBlocks.size())
            throw new IllegalArgumentException("Current state coverage differs from snapshot");
        List<ResetPlan.Change> changes = new ArrayList<>();
        long excludedCount = 0;
        int air = 0;
        int nonAir = 0;
        int entities = 0;
        int conflicts = 0;

        for (int i = 0; i < snapshot.blocks().size(); i++) {
            SnapshotBlock target = snapshot.blocks().get(i);
            if (excluded.test(target)) {
                excludedCount++;
                continue;
            }
            SnapshotBlock current = currentBlocks.get(i);
            if (codec.sameState(target, current)) continue;

            if (profile.mode() == ResetProfile.Mode.FILTERED_SNAPSHOT) {
                Material currentMaterial;
                try { currentMaterial = material(current.blockData()); }
                catch (RuntimeException ex) {
                    conflicts++;
                    continue;
                }
                if (!profile.restoreWhenCurrent().contains(currentMaterial)) {
                    conflicts++;
                    continue;
                }
            }

            changes.add(new ResetPlan.Change(target.x(), target.y(), target.z(), current, target));
            if (isAir(target.blockData())) air++; else nonAir++;
            if (target.blockEntity() != null) entities++;
        }
        String canonical = snapshot.regionId() + "|" + snapshot.geometryHash() + "|"
                + snapshot.checksum() + "|" + new com.google.gson.Gson().toJson(changes);
        String hash = sha256(canonical);
        return new ResetPlan(snapshot.regionId(), snapshot.worldUuid(), snapshot.geometryHash(),
                snapshot.checksum(), snapshot.blocks().size(), changes.size(), nonAir, air,
                entities, conflicts, excludedCount,
                changes.isEmpty() ? 0 : (changes.size() + batchSize - 1) / batchSize,
                hash, List.copyOf(changes));
    }

    public String refusal(ResetPlan plan, int maxTotal, int maxAir) {
        if (plan.totalChanges() > maxTotal)
            return "would change " + plan.totalChanges()
                    + " blocks; configured maximum is " + maxTotal;
        if (plan.airChanges() > maxAir)
            return "would set " + plan.airChanges()
                    + " blocks to air; configured maximum is " + maxAir;
        return null;
    }

    private Material material(String blockData) {
        String key = blockData;
        int properties = key.indexOf('[');
        if (properties >= 0) key = key.substring(0, properties);
        int namespace = key.indexOf(':');
        if (namespace >= 0) key = key.substring(namespace + 1);
        Material material = Material.matchMaterial(key.toUpperCase(java.util.Locale.ROOT));
        if (material == null) throw new IllegalArgumentException("unknown material");
        return material;
    }

    private boolean isAir(String value) {
        return value.equals("minecraft:air") || value.startsWith("minecraft:air[");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
