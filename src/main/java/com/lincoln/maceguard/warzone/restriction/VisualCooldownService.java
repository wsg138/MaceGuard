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
 * Only overlays this service actually lengthened are tracked and eligible for reconciliation.
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
        if (!shouldApply(existing, ticks)) return;

        long now = clock.getAsLong();
        Key key = new Key(player.getUniqueId(), material);
        OwnedOverlay prior = owned.get(key);
        long previousExpiresAt = prior == null
                ? safeAdd(now, ticksToMillis(existing))
                : prior.previousExpiresAtMillis();

        player.setCooldown(material, ticks);
        owned.put(key, new OwnedOverlay(previousExpiresAt, safeAdd(now, duration.toMillis())));
    }

    /** Reconciles every overlay owned by this service. */
    public void clearOwned() {
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            Player player = server.getPlayer(key.playerId());
            if (player != null) clearOwned(player, key.material());
            else owned.remove(key);
        }
    }

    /** Reconciles only this player's overlays, used when they leave the configured region. */
    public void clearOwned(Player player) {
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            if (key.playerId().equals(player.getUniqueId())) clearOwned(player, key.material());
        }
    }

    private void clearOwned(Player player, Material material) {
        Key key = new Key(player.getUniqueId(), material);
        OwnedOverlay overlay = owned.remove(key);
        if (overlay == null) return;

        long now = clock.getAsLong();
        int current = player.getCooldown(material);
        int expected = toTicks(Duration.ofMillis(Math.max(0L, overlay.expiresAtMillis() - now)));
        int previous = toTicks(Duration.ofMillis(Math.max(0L, overlay.previousExpiresAtMillis() - now)));
        int replacement = reconciledTicks(current, expected, previous);
        if (replacement >= 0) player.setCooldown(material, replacement);
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

    static boolean shouldApply(int existingTicks, int requestedTicks) {
        return requestedTicks > 0 && existingTicks < requestedTicks;
    }

    /** Returns -1 when the current cooldown was independently changed and must remain untouched. */
    static int reconciledTicks(int currentTicks, int expectedOwnedTicks, int previousTicks) {
        if (currentTicks > expectedOwnedTicks + 2) return -1;
        if (currentTicks + 2 < expectedOwnedTicks) return -1;
        return Math.max(0, previousTicks);
    }

    public static int toTicks(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return 0;
        long millis = duration.toMillis();
        long rounded = millis > Long.MAX_VALUE - 49L ? Long.MAX_VALUE : millis + 49L;
        long ticks = Math.max(1L, rounded / 50L);
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    private static long ticksToMillis(int ticks) {
        return ticks <= 0 ? 0L : Math.min(Long.MAX_VALUE, (long) ticks * 50L);
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private record Key(UUID playerId, Material material) { }
    private record OwnedOverlay(long previousExpiresAtMillis, long expiresAtMillis) { }
}
