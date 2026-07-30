package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Adds client-visible material cooldowns without making them authoritative.
 * Only overlays this service actually lengthened are tracked and eligible for clearing.
 */
public final class VisualCooldownService {
    private final Server server;
    private final LongSupplier clock;
    private final Map<Key, OwnedOverlay> owned = new HashMap<>();

    public VisualCooldownService(Server server, LongSupplier clock) {
        this.server = server;
        this.clock = clock;
    }

    public void apply(Player player, RestrictionDecision decision) {
        if (!decision.startsCooldownAfterSuccess() || decision.target() == null
                || decision.target().kind() != RestrictionTarget.Kind.MATERIAL
                || decision.target().material() == null || decision.restriction() == null) return;
        apply(player, decision.target().material(), decision.restriction().cooldown());
    }

    public void reapply(Player player, Map<RestrictionTarget, Duration> activeCooldowns) {
        activeCooldowns.forEach((target, remaining) -> {
            if (target.kind() == RestrictionTarget.Kind.MATERIAL && target.material() != null)
                apply(player, target.material(), remaining);
        });
    }

    private void apply(Player player, Material material, Duration duration) {
        int ticks = toTicks(duration);
        if (ticks <= 0) return;
        int existing = player.getCooldown(material);
        if (existing >= ticks) return;
        player.setCooldown(material, ticks);
        owned.put(new Key(player.getUniqueId(), material),
                new OwnedOverlay(Math.addExact(clock.getAsLong(), duration.toMillis())));
    }

    public void clearOwned() {
        long now = clock.getAsLong();
        for (Map.Entry<Key, OwnedOverlay> entry : owned.entrySet()) {
            Player player = server.getPlayer(entry.getKey().playerId());
            if (player == null) continue;
            int current = player.getCooldown(entry.getKey().material());
            int expectedRemaining = toTicks(Duration.ofMillis(Math.max(0L,
                    entry.getValue().expiresAtMillis() - now)));
            if (current > 0 && current <= expectedRemaining + 2)
                player.setCooldown(entry.getKey().material(), 0);
        }
        owned.clear();
    }

    public void forget(UUID playerId) {
        owned.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public int cleanup() {
        long now = clock.getAsLong();
        int removed = 0;
        Iterator<OwnedOverlay> iterator = owned.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMillis() > now) continue;
            iterator.remove();
            removed++;
        }
        return removed;
    }

    public static int toTicks(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return 0;
        long millis = duration.toMillis();
        long ticks = Math.max(1L, Math.floorDiv(Math.addExact(millis, 49L), 50L));
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    private record Key(UUID playerId, Material material) { }
    private record OwnedOverlay(long expiresAtMillis) { }
}
