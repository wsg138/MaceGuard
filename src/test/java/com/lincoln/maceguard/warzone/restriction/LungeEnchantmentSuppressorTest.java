package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LungeEnchantmentSuppressorTest {
    private final NamespacedKey key = new NamespacedKey("maceguard", "suppressed-lunge-level");
    private final LungeEnchantmentSuppressor.LungeAccess lunge =
            mock(LungeEnchantmentSuppressor.LungeAccess.class);
    private final LungeEnchantmentSuppressor suppressor =
            new LungeEnchantmentSuppressor(key, lunge);

    @Test
    void suppressionStoresLevelAndRemovesOnlyLunge() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.IRON_SPEAR);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(lunge.metaLevel(meta)).thenReturn(3);
        when(item.setItemMeta(meta)).thenReturn(true);

        assertTrue(suppressor.suppress(item));

        verify(data).set(key, PersistentDataType.INTEGER, 3);
        verify(lunge).remove(meta);
        verify(item).setItemMeta(meta);
    }

    @Test
    void restorationReappliesStoredLevelAndDeletesMarker() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.DIAMOND_SPEAR);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(key, PersistentDataType.INTEGER)).thenReturn(2);
        when(lunge.metaLevel(meta)).thenReturn(0);
        when(item.setItemMeta(meta)).thenReturn(true);

        assertTrue(suppressor.restore(item));

        verify(data).remove(key);
        verify(lunge).add(meta, 2);
        verify(item).setItemMeta(meta);
    }

    @Test
    void unrelatedItemsAreNotChanged() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.getItemMeta()).thenReturn(null);

        assertFalse(suppressor.suppress(item));
        assertFalse(suppressor.restore(item));
    }
}
