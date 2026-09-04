package com.lincoln.maceguard.warzone.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PersistentDataTestSupport {
    private PersistentDataTestSupport() { }

    static PersistentDataContainer container() {
        return fixture().data();
    }

    static Fixture fixture() {
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> values = new HashMap<>();
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        when(data.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            values.remove(invocation.getArgument(0));
            return null;
        }).when(data).remove(any(NamespacedKey.class));
        return new Fixture(data, values);
    }

    record Fixture(PersistentDataContainer data, Map<NamespacedKey, Object> values) { }
}
