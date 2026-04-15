package com.lincoln.maceguard.adapter.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lincoln.maceguard.core.model.CuboidRegion;
import com.lincoln.maceguard.core.model.SnapshotBlock;
import com.lincoln.maceguard.core.model.SnapshotData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class FileSnapshotRepository {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path rootDirectory;

    public FileSnapshotRepository(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(rootDirectory);
    }

    public Optional<SnapshotData> load(String zoneName) throws IOException {
        Path file = file(zoneName);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            SnapshotFile snapshotFile = GSON.fromJson(reader, SnapshotFile.class);
            if (snapshotFile == null || snapshotFile.blocks == null || snapshotFile.blocks.isEmpty()) {
                return Optional.empty();
            }
            CuboidRegion region = CuboidRegion.of(
                    snapshotFile.zoneName,
                    snapshotFile.worldName,
                    snapshotFile.minX,
                    snapshotFile.minY,
                    snapshotFile.minZ,
                    snapshotFile.maxX,
                    snapshotFile.maxY,
                    snapshotFile.maxZ
            );
            Map<Long, String> blocks = new HashMap<>(snapshotFile.blocks.size());
            for (SnapshotBlock block : snapshotFile.blocks) {
                blocks.put(com.lincoln.maceguard.core.model.BlockKey.pack(block.x(), block.y(), block.z()), block.blockData());
            }
            return Optional.of(new SnapshotData(snapshotFile.zoneName, snapshotFile.worldName, region, blocks, snapshotFile.blocks));
        }
    }

    public void save(SnapshotData data) throws IOException {
        ensureDirectory();
        SnapshotFile fileData = new SnapshotFile();
        fileData.zoneName = data.zoneName();
        fileData.worldName = data.worldName();
        fileData.minX = data.region().minX();
        fileData.minY = data.region().minY();
        fileData.minZ = data.region().minZ();
        fileData.maxX = data.region().maxX();
        fileData.maxY = data.region().maxY();
        fileData.maxZ = data.region().maxZ();
        fileData.blocks = data.serializedBlocks();

        try (BufferedWriter writer = new BufferedWriter(
                new java.io.OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file(data.zoneName()))), StandardCharsets.UTF_8))) {
            GSON.toJson(fileData, writer);
        }
    }

    public Path file(String zoneName) {
        return rootDirectory.resolve(zoneName + ".json.gz");
    }

    private static final class SnapshotFile {
        String zoneName;
        String worldName;
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        List<SnapshotBlock> blocks;
    }
}
