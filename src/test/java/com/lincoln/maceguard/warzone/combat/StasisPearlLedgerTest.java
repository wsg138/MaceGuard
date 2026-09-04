package com.lincoln.maceguard.warzone.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StasisPearlLedgerTest {
    private Player player;
    private PersistentDataContainer data;
    private Map<NamespacedKey, Object> values;
    private StasisPearlLedger ledger;

    @BeforeEach void setUp() {
        player = mock(Player.class);
        data = mock(PersistentDataContainer.class);
        values = new HashMap<>();
        when(player.getPersistentDataContainer()).thenReturn(data);
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
        ledger = new StasisPearlLedger();
    }

    @Test void recordRoundTripsOriginalLaunchTimeAndRemoveClearsIt() {
        UUID pearl = UUID.randomUUID();
        ledger.record(player, pearl, 12_345L);

        assertEquals(12_345L, ledger.read(data).get(pearl));

        ledger.remove(player, pearl);
        assertFalse(ledger.read(data).containsKey(pearl));
        assertFalse(values.containsKey(StasisPearlMetadata.key(StasisPearlLedger.LEDGER_KEY)));
    }

    @Test void malformedShapeAndUnsupportedFormatAreIgnored() {
        NamespacedKey key = StasisPearlMetadata.key(StasisPearlLedger.LEDGER_KEY);
        values.put(key, new long[]{StasisPearlLedger.FORMAT_VERSION, 1L, 2L});
        assertTrue(ledger.read(data).isEmpty());

        values.put(key, new long[]{99L, 1L, 2L, 3L});
        assertTrue(ledger.read(data).isEmpty());
    }

    @Test void ledgerKeepsOldestThirtyTwoLaunches() {
        for (int index = 0; index < 40; index++)
            ledger.record(player, UUID.randomUUID(), 1_000L + index);

        Map<UUID, Long> restored = ledger.read(data);
        assertEquals(StasisPearlLedger.MAX_ENTRIES, restored.size());
        assertTrue(restored.containsValue(1_000L));
        assertTrue(restored.containsValue(1_031L));
        assertFalse(restored.containsValue(1_032L));
        assertFalse(restored.containsValue(1_039L));
    }
}
