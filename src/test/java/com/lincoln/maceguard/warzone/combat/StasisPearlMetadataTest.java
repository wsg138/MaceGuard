package com.lincoln.maceguard.warzone.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.EnderPearl;
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

class StasisPearlMetadataTest {
    private EnderPearl pearl;
    private Map<NamespacedKey, Object> values;
    private StasisPearlMetadata metadata;

    @BeforeEach void setUp() {
        pearl = mock(EnderPearl.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        values = new HashMap<>();
        when(pearl.getPersistentDataContainer()).thenReturn(data);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        when(data.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        metadata = new StasisPearlMetadata();
    }

    @Test void validMetadataRoundTrips() {
        UUID owner = UUID.randomUUID();
        metadata.mark(pearl, owner, 10_000L);

        StasisPearlMetadata.ReadResult result = metadata.read(pearl, owner, 20_000L);

        assertTrue(result.marked());
        assertFalse(result.failClosed());
        assertEquals(owner, result.ownerId());
        assertEquals(10_000L, result.launchedAtMillis());
    }

    @Test void unmarkedPearlIsIgnored() {
        StasisPearlMetadata.ReadResult result = metadata.read(pearl, UUID.randomUUID(), 20_000L);
        assertFalse(result.marked());
    }

    @Test void malformedOwnerFailsClosedForEntityOwner() {
        UUID entityOwner = UUID.randomUUID();
        put(StasisPearlMetadata.MARKER_KEY, (byte) 1);
        put(StasisPearlMetadata.FORMAT_KEY, StasisPearlMetadata.FORMAT_VERSION);
        put(StasisPearlMetadata.OWNER_KEY, "not-a-uuid");
        put(StasisPearlMetadata.LAUNCHED_AT_KEY, 10_000L);

        StasisPearlMetadata.ReadResult result = metadata.read(pearl, entityOwner, 20_000L);

        assertTrue(result.failClosed());
        assertEquals(entityOwner, result.ownerId());
        assertTrue(result.diagnostic().contains("malformed"));
    }

    @Test void ownerMismatchFailsClosedForActualEntityOwner() {
        UUID entityOwner = UUID.randomUUID();
        UUID metadataOwner = UUID.randomUUID();
        metadata.mark(pearl, metadataOwner, 10_000L);

        StasisPearlMetadata.ReadResult result = metadata.read(pearl, entityOwner, 20_000L);

        assertTrue(result.failClosed());
        assertEquals(entityOwner, result.ownerId());
        assertTrue(result.diagnostic().contains("does not match"));
    }

    @Test void unsupportedFormatAndMissingTimestampFailClosed() {
        UUID owner = UUID.randomUUID();
        metadata.mark(pearl, owner, 10_000L);
        put(StasisPearlMetadata.FORMAT_KEY, 99);
        assertTrue(metadata.read(pearl, owner, 20_000L).failClosed());

        put(StasisPearlMetadata.FORMAT_KEY, StasisPearlMetadata.FORMAT_VERSION);
        remove(StasisPearlMetadata.LAUNCHED_AT_KEY);
        assertTrue(metadata.read(pearl, owner, 20_000L).failClosed());
    }

    @Test void futureAndUnreasonablyOldTimestampsFailClosed() {
        UUID owner = UUID.randomUUID();
        metadata.mark(pearl, owner, 20_001L);
        assertTrue(metadata.read(pearl, owner, 10_000L).failClosed());

        metadata.mark(pearl, owner, 1L);
        long overOneYear = java.time.Duration.ofDays(366).toMillis();
        assertTrue(metadata.read(pearl, owner, overOneYear).failClosed());
    }

    private void put(String key, Object value) {
        values.put(StasisPearlMetadata.key(key), value);
    }

    private void remove(String key) {
        values.remove(StasisPearlMetadata.key(key));
    }
}
