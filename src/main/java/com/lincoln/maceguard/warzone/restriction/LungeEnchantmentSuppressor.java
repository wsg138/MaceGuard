package com.lincoln.maceguard.warzone.restriction;

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

    LungeEnchantmentSuppressor(Plugin plugin) {
        this(new NamespacedKey(plugin, "suppressed-lunge-level"));
    }

    LungeEnchantmentSuppressor(NamespacedKey levelKey) {
        this.levelKey = Objects.requireNonNull(levelKey, "levelKey");
    }

    boolean hasLiveLunge(ItemStack item) {
        return item != null && item.getEnchantmentLevel(Enchantment.LUNGE) > 0;
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
        int liveLevel = meta.getEnchantLevel(Enchantment.LUNGE);
        if (liveLevel <= 0) return false;

        PersistentDataContainer data = data(meta);
        Integer stored = data.get(levelKey, PersistentDataType.INTEGER);
        data.set(levelKey, PersistentDataType.INTEGER,
                Math.max(liveLevel, stored == null ? 0 : stored));
        meta.removeEnchant(Enchantment.LUNGE);
        return item.setItemMeta(meta);
    }

    boolean restore(ItemStack item) {
        ItemMeta meta = meta(item);
        if (meta == null) return false;
        PersistentDataContainer data = data(meta);
        Integer stored = data.get(levelKey, PersistentDataType.INTEGER);
        if (stored == null) return false;

        data.remove(levelKey);
        if (stored > 0 && meta.getEnchantLevel(Enchantment.LUNGE) < stored)
            meta.addEnchant(Enchantment.LUNGE, stored, true);
        return item.setItemMeta(meta);
    }

    private ItemMeta meta(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.getItemMeta();
    }

    private PersistentDataContainer data(ItemMeta meta) {
        return meta.getPersistentDataContainer();
    }
}
