package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Temporarily removes the vanilla Lunge enchantment while a Warzone Lunge restriction is denied.
 *
 * <p>Paper 1.21.11 has no cancellable Lunge event, so cancelling velocity after the fact is not a
 * reliable way to prevent the movement. The original enchantment level is stored directly on the
 * item and restored as soon as the restriction no longer applies. The marker also lets a later
 * runtime restore an item after a reload or interrupted listener handoff.</p>
 */
final class LungeEnchantmentSuppressor {
    private final NamespacedKey levelKey;
    private final LungeAccess lunge;

    LungeEnchantmentSuppressor(Plugin ignoredPlugin) {
        this(new NamespacedKey("maceguard", "suppressed-lunge-level"), new BukkitLungeAccess());
    }

    LungeEnchantmentSuppressor(NamespacedKey levelKey) {
        this(levelKey, new BukkitLungeAccess());
    }

    LungeEnchantmentSuppressor(NamespacedKey levelKey, LungeAccess lunge) {
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
        this.lunge = Objects.requireNonNull(lunge, "lunge");
    }

    boolean hasLiveLunge(ItemStack item) {
        return item != null && lunge.itemLevel(item) > 0;
    }

    boolean isSuppressed(ItemStack item) {
        ItemMeta meta = meta(item);
        if (meta == null) return false;
        Integer level = data(meta).get(levelKey, PersistentDataType.INTEGER);
        return level != null && level > 0;
    }

    boolean suppress(ItemStack item) {
        ItemMeta meta = meta(item);
        if (meta == null) return false;
        int liveLevel = lunge.metaLevel(meta);
        if (liveLevel <= 0) return false;

        PersistentDataContainer data = data(meta);
        Integer stored = data.get(levelKey, PersistentDataType.INTEGER);
        data.set(levelKey, PersistentDataType.INTEGER,
                Math.max(liveLevel, stored == null ? 0 : stored));
        lunge.remove(meta);
        return item.setItemMeta(meta);
    }

    boolean restore(ItemStack item) {
        // Lunge is spear-exclusive. Reconciliation scans every inventory slot every tick, so
        // reject non-spears before getItemMeta() creates a metadata copy on the main thread.
        if (item == null || !RestrictionTarget.isSpear(item.getType())) return false;
        ItemMeta meta = meta(item);
        if (meta == null) return false;
        PersistentDataContainer data = data(meta);
        Integer stored = data.get(levelKey, PersistentDataType.INTEGER);
        if (stored == null) return false;

        data.remove(levelKey);
        if (stored > 0 && lunge.metaLevel(meta) < stored)
            lunge.add(meta, stored);
        return item.setItemMeta(meta);
    }

    private ItemMeta meta(ItemStack item) {
        return item == null || item.getType() == Material.AIR ? null : item.getItemMeta();
    }

    private PersistentDataContainer data(ItemMeta meta) {
        return meta.getPersistentDataContainer();
    }

    interface LungeAccess {
        int itemLevel(ItemStack item);
        int metaLevel(ItemMeta meta);
        void remove(ItemMeta meta);
        void add(ItemMeta meta, int level);
    }

    private static final class BukkitLungeAccess implements LungeAccess {
        @Override
        public int itemLevel(ItemStack item) {
            return item.getEnchantmentLevel(Enchantment.LUNGE);
        }

        @Override
        public int metaLevel(ItemMeta meta) {
            return meta.getEnchantLevel(Enchantment.LUNGE);
        }

        @Override
        public void remove(ItemMeta meta) {
            meta.removeEnchant(Enchantment.LUNGE);
        }

        @Override
        public void add(ItemMeta meta, int level) {
            meta.addEnchant(Enchantment.LUNGE, level, true);
        }
    }
}
