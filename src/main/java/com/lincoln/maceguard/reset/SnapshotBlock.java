package com.lincoln.maceguard.reset;

import java.util.List;

public record SnapshotBlock(int x, int y, int z, String blockData, BlockEntity blockEntity) {
    public record BlockEntity(String type, List<byte[]> inventoryItems) { }
}
