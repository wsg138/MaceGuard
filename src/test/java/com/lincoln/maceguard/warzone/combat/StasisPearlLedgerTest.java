package com.lincoln.maceguard.warzone.combat;

import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.lincoln.maceguard.warzone.combat.PersistentDataTestSupport.container;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StasisPearlLedgerTest {
    private Player player;
    private PersistentDataContainer data;
    private StasisPearlLedger ledger;

    @BeforeEach void setUp() {
        player = mock(Player.class);
        data = container();
        when(player.getPersistentDataContainer()).thenReturn(data);
        ledger = new StasisPearlLedger();
    }

    @Test void recordRoundTripsOriginalLaunchTimeAndRemoveClearsIt() {
        UUID pearl = UUID.randomUUID();
        ledger.record(player, pearl, 12_345L);

        assertEquals(12_345L, ledger.read(data).get(pearl));

        ledger.remove(player, pearl);
        assertTrue(ledger.read(data).isEmpty());
    }

    @Test void malformedShapeAndUnsupportedFormatAreIgnored() {
        data.set(StasisPearlMetadata.key(StasisPearlLedger.LEDGER_KEY),
                org.bukkit.persistence.PersistentDataType.LONG_ARRAY,
                new long[]{StasisPearlLedger.FORMAT_VERSION, 1L, 2L});
        assertTrue(ledger.read(data).isEmpty());

        data.set(StasisPearlMetadata.key(StasisPearlLedger.LEDGER_KEY),
                org.bukkit.persistence.PersistentDataType.LONG_ARRAY,
                new long[]{99L, 1L, 2L, 3L});
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
