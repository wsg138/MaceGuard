package com.lincoln.maceguard.reset;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class BlockStateCodec {
    public SnapshotBlock capture(Block block) {
        return capture(block.getState(false), block.getX(), block.getY(), block.getZ());
    }

    public SnapshotBlock capture(BlockState state, int x, int y, int z) {
        SnapshotBlock.BlockEntity entity = null;
        if (state instanceof Container container) {
            List<byte[]> items = new ArrayList<>();
            for (ItemStack item : container.getInventory().getContents()) items.add(item == null ? null : item.serializeAsBytes());
            entity = new SnapshotBlock.BlockEntity("CONTAINER", items);
        } else if (state instanceof TileState) {
            throw new UnsupportedOperationException("Unsupported block entity " + state.getType() + " at " + x + "," + y + "," + z);
        }
        return new SnapshotBlock(x, y, z, state.getBlockData().getAsString(true), entity);
    }

    public void restore(Block block, SnapshotBlock target) {
        block.setBlockData(org.bukkit.Bukkit.createBlockData(target.blockData()), false);
        if (target.blockEntity() == null) return;
        BlockState state = block.getState(false);
        if (!(state instanceof Container container) || !"CONTAINER".equals(target.blockEntity().type()))
            throw new IllegalStateException("Target block entity no longer matches snapshot");
        List<byte[]> encoded = target.blockEntity().inventoryItems();
        ItemStack[] contents = new ItemStack[container.getInventory().getSize()];
        if (encoded.size() != contents.length) throw new IllegalStateException("Container size differs from snapshot");
        for (int i = 0; i < contents.length; i++) contents[i] = encoded.get(i) == null ? null : ItemStack.deserializeBytes(encoded.get(i));
        container.getInventory().setContents(contents);
        container.update(true, false);
    }

    public boolean sameState(SnapshotBlock expected, SnapshotBlock current) {
        if (!expected.blockData().equals(current.blockData())) return false;
        if (expected.blockEntity() == null || current.blockEntity() == null) return expected.blockEntity() == current.blockEntity();
        if (!expected.blockEntity().type().equals(current.blockEntity().type())) return false;
        List<byte[]> left = expected.blockEntity().inventoryItems();
        List<byte[]> right = current.blockEntity().inventoryItems();
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!java.util.Arrays.equals(left.get(i), right.get(i))) return false;
        return true;
    }
}
