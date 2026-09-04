package com.lincoln.maceguard.warzone.combat;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable owner-side mirror of active pearl identity and original launch time. */
final class StasisPearlLedger {
    static final String LEDGER_KEY = "stasis-pearl-ledger";
    static final long FORMAT_VERSION = 1L;
    static final int MAX_ENTRIES = 32;
    private static final int VALUES_PER_ENTRY = 3;
    private static final int HEADER_VALUES = 1;

    private final NamespacedKey key = StasisPearlMetadata.key(LEDGER_KEY);

    Map<UUID, Long> read(PersistentDataContainerView data) {
        Map<UUID, Long> entries = new LinkedHashMap<>();
        long[] stored = data.get(key, PersistentDataType.LONG_ARRAY);
        if (!valid(stored)) return entries;
        int available = (stored.length - HEADER_VALUES) / VALUES_PER_ENTRY;
        int count = Math.min(available, MAX_ENTRIES);
        for (int entry = 0; entry < count; entry++) {
            int index = HEADER_VALUES + entry * VALUES_PER_ENTRY;
            long launchedAt = stored[index + 2];
            if (launchedAt <= 0L) continue;
            UUID pearlId = new UUID(stored[index], stored[index + 1]);
            entries.put(pearlId, launchedAt);
        }
        return entries;
    }

    void record(Player player, UUID pearlId, long launchedAtMillis) {
        if (launchedAtMillis <= 0L) return;
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        entries.put(pearlId, launchedAtMillis);
        if (entries.size() > MAX_ENTRIES) entries.remove(newest(entries));
        write(player, entries);
    }

    void remove(Player player, UUID pearlId) {
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        if (entries.remove(pearlId) != null) write(player, entries);
    }

    void clear(Player player) {
        player.getPersistentDataContainer().remove(key);
    }

    void write(Player player, Map<UUID, Long> source) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (source.isEmpty()) {
            data.remove(key);
            return;
        }
        int count = Math.min(source.size(), MAX_ENTRIES);
        long[] stored = new long[HEADER_VALUES + count * VALUES_PER_ENTRY];
        stored[0] = FORMAT_VERSION;
        int entry = 0;
        for (Map.Entry<UUID, Long> value : source.entrySet()) {
            if (entry >= count) break;
            if (value.getValue() == null || value.getValue() <= 0L) continue;
            int index = HEADER_VALUES + entry * VALUES_PER_ENTRY;
            stored[index] = value.getKey().getMostSignificantBits();
            stored[index + 1] = value.getKey().getLeastSignificantBits();
            stored[index + 2] = value.getValue();
            entry++;
        }
        if (entry == 0) {
            data.remove(key);
            return;
        }
        if (entry < count) {
            long[] compact = new long[HEADER_VALUES + entry * VALUES_PER_ENTRY];
            System.arraycopy(stored, 0, compact, 0, compact.length);
            stored = compact;
        }
        data.set(key, PersistentDataType.LONG_ARRAY, stored);
    }

    private boolean valid(long[] stored) {
        return stored != null && stored.length >= HEADER_VALUES
                && stored[0] == FORMAT_VERSION
                && (stored.length - HEADER_VALUES) % VALUES_PER_ENTRY == 0;
    }

    private UUID newest(Map<UUID, Long> entries) {
        UUID newestId = null;
        long newestLaunch = Long.MIN_VALUE;
        for (Map.Entry<UUID, Long> entry : entries.entrySet()) {
            Long launch = entry.getValue();
            if (launch != null && launch > newestLaunch) {
                newestLaunch = launch;
                newestId = entry.getKey();
            }
        }
        return newestId;
    }
}
