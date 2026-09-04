package com.lincoln.maceguard.warzone.combat;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small owner-side mirror of active pearl launch times. Modern Paper can reconstruct a player's
 * associated Ender Pearl during reconnect, so entity PDC alone is not a sufficient identity store.
 */
final class StasisPearlLedger {
    static final String LEDGER_KEY = "stasis-pearl-ledger";
    static final int FORMAT_VERSION = 1;
    static final int MAX_ENTRIES = 32;

    private final NamespacedKey key = StasisPearlMetadata.key(LEDGER_KEY);

    Map<UUID, Long> read(PersistentDataContainerView data) {
        Map<UUID, Long> entries = new LinkedHashMap<>();
        String serialized = data.get(key, PersistentDataType.STRING);
        if (serialized == null || serialized.isBlank()) return entries;
        String[] lines = serialized.split("\\n");
        if (lines.length == 0 || !Integer.toString(FORMAT_VERSION).equals(lines[0])) return entries;
        for (int index = 1; index < lines.length && entries.size() < MAX_ENTRIES; index++) {
            String line = lines[index];
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) continue;
            try {
                UUID pearlId = UUID.fromString(line.substring(0, separator));
                long launchedAt = Long.parseLong(line.substring(separator + 1));
                if (launchedAt > 0L) entries.put(pearlId, launchedAt);
            } catch (IllegalArgumentException ignored) {
                // A malformed owner-side mirror must not make unrelated pearls authoritative.
            }
        }
        return entries;
    }

    void record(Player player, UUID pearlId, long launchedAtMillis) {
        if (launchedAtMillis <= 0L) return;
        Map<UUID, Long> entries = read(player.getPersistentDataContainer());
        entries.put(pearlId, launchedAtMillis);
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
        StringBuilder serialized = new StringBuilder(Integer.toString(FORMAT_VERSION));
        source.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0L)
                .sorted(Comparator.<Map.Entry<UUID, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(MAX_ENTRIES)
                .forEach(entry -> serialized.append('\n').append(entry.getKey())
                        .append('=').append(entry.getValue()));
        if (serialized.indexOf("\n") < 0) data.remove(key);
        else data.set(key, PersistentDataType.STRING, serialized.toString());
    }
}
