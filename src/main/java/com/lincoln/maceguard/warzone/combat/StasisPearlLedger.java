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
            entries.put(new UUID(stored[index], stored[index + 1]), launchedAt);
        }
        return entries;
    }

    void record(Player player, UUID pearlId, long launchedAtMillis) {
        if (launchedAtMillis <= 0L) return;
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        entries.put(pearlId, launchedAtMillis);
        trimNewest(entries);
        write(player, entries);
    }

    /**
     * Records a live marked pearl and migrates one obsolete UUID only when its original launch
     * timestamp identifies exactly one prior ledger entry. Ambiguous timestamp matches are kept.
     */
    void recordObserved(Player player, UUID pearlId, long launchedAtMillis) {
        if (launchedAtMillis <= 0L) return;
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        UUID priorId = uniqueOtherAt(entries, pearlId, launchedAtMillis);
        if (priorId != null) entries.remove(priorId);
        entries.put(pearlId, launchedAtMillis);
        trimNewest(entries);
        write(player, entries);
    }

    Long rebindOldest(Player player, UUID replacementId) {
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        UUID originalId = oldest(entries);
        if (originalId == null) return null;
        Long launchedAt = entries.remove(originalId);
        entries.put(replacementId, launchedAt);
        write(player, entries);
        return launchedAt;
    }

    void remove(Player player, UUID pearlId) {
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        if (entries.remove(pearlId) != null) write(player, entries);
    }

    void clear(Player player) {
        player.getPersistentDataContainer().remove(key);
    }

    private void write(Player player, Map<UUID, Long> source) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (source.isEmpty()) {
            data.remove(key);
            return;
        }
        long[] stored = new long[HEADER_VALUES + source.size() * VALUES_PER_ENTRY];
        stored[0] = FORMAT_VERSION;
        int entry = 0;
        for (Map.Entry<UUID, Long> value : source.entrySet()) {
            Long launchedAt = value.getValue();
            if (launchedAt == null || launchedAt <= 0L) continue;
            int index = HEADER_VALUES + entry * VALUES_PER_ENTRY;
            stored[index] = value.getKey().getMostSignificantBits();
            stored[index + 1] = value.getKey().getLeastSignificantBits();
            stored[index + 2] = launchedAt;
            entry++;
        }
        if (entry == 0) {
            data.remove(key);
            return;
        }
        if (entry * VALUES_PER_ENTRY + HEADER_VALUES != stored.length)
            stored = compact(stored, entry);
        data.set(key, PersistentDataType.LONG_ARRAY, stored);
    }

    private boolean valid(long[] stored) {
        return stored != null && stored.length >= HEADER_VALUES
                && stored[0] == FORMAT_VERSION
                && (stored.length - HEADER_VALUES) % VALUES_PER_ENTRY == 0;
    }

    private long[] compact(long[] stored, int entries) {
        long[] compact = new long[HEADER_VALUES + entries * VALUES_PER_ENTRY];
        System.arraycopy(stored, 0, compact, 0, compact.length);
        return compact;
    }

    private void trimNewest(Map<UUID, Long> entries) {
        if (entries.size() > MAX_ENTRIES) entries.remove(newest(entries));
    }

    private UUID uniqueOtherAt(Map<UUID, Long> entries, UUID currentId, long launchedAtMillis) {
        UUID match = null;
        for (Map.Entry<UUID, Long> entry : entries.entrySet()) {
            if (currentId.equals(entry.getKey()) || entry.getValue() == null
                    || entry.getValue() != launchedAtMillis) continue;
            if (match != null) return null;
            match = entry.getKey();
        }
        return match;
    }

    private UUID oldest(Map<UUID, Long> entries) {
        return extreme(entries, true);
    }

    private UUID newest(Map<UUID, Long> entries) {
        return extreme(entries, false);
    }

    private UUID extreme(Map<UUID, Long> entries, boolean oldest) {
        UUID selected = null;
        long selectedTime = oldest ? Long.MAX_VALUE : Long.MIN_VALUE;
        for (Map.Entry<UUID, Long> entry : entries.entrySet()) {
            Long launch = entry.getValue();
            if (launch == null) continue;
            boolean better = oldest ? launch < selectedTime : launch > selectedTime;
            if (better) {
                selectedTime = launch;
                selected = entry.getKey();
            }
        }
        return selected;
    }
}
