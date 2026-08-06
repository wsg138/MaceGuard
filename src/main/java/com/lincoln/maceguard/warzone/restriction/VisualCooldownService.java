package com.lincoln.maceguard.warzone.restriction;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Adds client-visible material cooldowns without making them authoritative. */
public final class VisualCooldownService {
    private static final int COOLDOWN_TOLERANCE_TICKS = 2;
    private final Server server;
    private final LongSupplier wallClock;
    private final LongSupplier tickClock;
    // Bukkit-main-thread owned; insertion order makes overlay transfer/removal deterministic.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<Key, OwnedOverlay> owned = new LinkedHashMap<>();

    public VisualCooldownService(Server server, LongSupplier wallClock, LongSupplier tickClock) {
        this.server = server;
        this.wallClock = wallClock;
        this.tickClock = tickClock;
    }

    public void apply(Player player, RestrictionDecision decision) {
        apply(player, decision, null);
    }

    public void apply(Player player, RestrictionDecision decision, Material concreteMaterial) {
        if (!decision.startsCooldownAfterSuccess() || decision.target() == null
                || decision.restriction() == null) return;
        Material material = visualMaterial(decision.target(), concreteMaterial);
        if (material != null) apply(player, material, decision.restriction().cooldown());
    }

    /** Reconciles this player's complete desired visual state, including transferred ownership. */
    @SuppressWarnings("PMD.UseConcurrentHashMap") // Bukkit-main-thread ordered reconciliation.
    public void reapply(Player player, Map<RestrictionTarget, Duration> activeCooldowns) {
        Map<Material, Duration> desired = new LinkedHashMap<>();
        activeCooldowns.forEach((target, remaining) -> {
            Material material = visualMaterial(target, null);
            if (material != null) desired.put(material, remaining);
        });
        reapplyMaterials(player, desired);
    }

    /** Reconciles exact concrete materials, including whole-Spear cooldown projections. */
    public void reapplyMaterials(Player player, Map<Material, Duration> desired) {
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            if (key.playerId().equals(player.getUniqueId()) && !desired.containsKey(key.material()))
                clearOwned(player, key.material());
        }
        desired.forEach((material, remaining) -> reconcile(player, material, remaining));
    }

    private Material visualMaterial(RestrictionTarget target, Material concreteMaterial) {
        if (target.kind() == RestrictionTarget.Kind.MATERIAL) return target.material();
        return target == RestrictionTarget.SPEAR && RestrictionTarget.isSpear(concreteMaterial)
                ? concreteMaterial : null;
    }

    private void apply(Player player, Material material, Duration duration) {
        int requestedTicks = toTicks(duration);
        if (requestedTicks <= 0) return;
        int existingTicks = player.getCooldown(material);
        if (!shouldApply(existingTicks, requestedTicks)) return;
        ownAndSet(player, material, requestedTicks, existingTicks);
    }

    private void reconcile(Player player, Material material, Duration duration) {
        int requestedTicks = toTicks(duration);
        if (requestedTicks <= 0) {
            clearOwned(player, material);
            return;
        }
        long nowTick = tickClock.getAsLong();
        Key key = new Key(player.getUniqueId(), material);
        OwnedOverlay prior = owned.get(key);
        int current = player.getCooldown(material);
        if (prior == null) {
            if (current > requestedTicks + COOLDOWN_TOLERANCE_TICKS) return;
            ownAndSet(player, material, requestedTicks, current);
            return;
        }
        long elapsed = Math.max(0L, nowTick - prior.appliedAtTick());
        int expected = remainingTicks(prior.appliedTicks(), elapsed);
        if (Math.abs(current - expected) > COOLDOWN_TOLERANCE_TICKS) {
            owned.remove(key);
            return;
        }
        int previous = remainingTicks(prior.previousTicks(), elapsed);
        player.setCooldown(material, requestedTicks);
        owned.put(key, new OwnedOverlay(previous, requestedTicks, nowTick,
                safeAdd(wallClock.getAsLong(), duration.toMillis())));
    }

    private void ownAndSet(Player player, Material material, int requestedTicks, int previousTicks) {
        long nowTick = tickClock.getAsLong();
        player.setCooldown(material, requestedTicks);
        owned.put(new Key(player.getUniqueId(), material),
                new OwnedOverlay(previousTicks, requestedTicks, nowTick,
                        safeAdd(wallClock.getAsLong(), requestedTicks * 50L)));
    }

    public Snapshot snapshot() {
        return new Snapshot(owned.entrySet().stream().map(entry -> new SnapshotEntry(
                entry.getKey().playerId(), entry.getKey().material(),
                entry.getValue().previousTicks(), entry.getValue().appliedTicks(),
                entry.getValue().appliedAtTick(), entry.getValue().expiresAtMillis())).toList());
    }

    /** Adopts bookkeeping only; no Bukkit cooldown is changed until normal reconciliation. */
    public void restore(Snapshot snapshot) {
        owned.clear();
        for (SnapshotEntry entry : snapshot.entries())
            restoreEntry(entry);
    }

    private void restoreEntry(SnapshotEntry entry) {
        owned.put(new Key(entry.playerId(), entry.material()), new OwnedOverlay(
                entry.previousTicks(), entry.appliedTicks(), entry.appliedAtTick(),
                entry.expiresAtMillis()));
    }

    /** Relinquishes bookkeeping without clearing the client overlay during a successful handoff. */
    public void releaseOwnership() { owned.clear(); }

    public void clearOwned() {
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            Player player = server.getPlayer(key.playerId());
            if (player != null) clearOwned(player, key.material());
            else owned.remove(key);
        }
    }

    public void clearOwned(java.util.Set<RestrictionTarget> targets) {
        if (targets.isEmpty()) return;
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            boolean affected = targets.stream().anyMatch(target -> target.matches(key.material()));
            if (!affected) continue;
            Player player = server.getPlayer(key.playerId());
            if (player != null) clearOwned(player, key.material());
            else owned.remove(key);
        }
    }

    public void clearOwned(Player player) {
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            if (key.playerId().equals(player.getUniqueId())) clearOwned(player, key.material());
        }
    }

    private void clearOwned(Player player, Material material) {
        Key key = new Key(player.getUniqueId(), material);
        OwnedOverlay overlay = owned.remove(key);
        if (overlay == null) return;
        long elapsedTicks = Math.max(0L, tickClock.getAsLong() - overlay.appliedAtTick());
        int expectedTicks = remainingTicks(overlay.appliedTicks(), elapsedTicks);
        int previousTicks = remainingTicks(overlay.previousTicks(), elapsedTicks);
        int replacement = reconciledTicks(player.getCooldown(material), expectedTicks, previousTicks);
        if (replacement >= 0) player.setCooldown(material, replacement);
    }

    public void forget(UUID playerId) {
        owned.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public int cleanup() {
        long now = wallClock.getAsLong();
        int removed = 0;
        for (Key key : java.util.Set.copyOf(owned.keySet())) {
            OwnedOverlay overlay = owned.get(key);
            if (overlay == null || overlay.expiresAtMillis() > now) continue;
            Player player = server.getPlayer(key.playerId());
            if (player == null) owned.remove(key);
            else clearOwned(player, key.material());
            removed++;
        }
        return removed;
    }

    static boolean shouldApply(int existingTicks, int requestedTicks) {
        return requestedTicks > 0 && existingTicks < requestedTicks;
    }
    static int reconciledTicks(int currentTicks, int expectedOwnedTicks, int previousTicks) {
        if (currentTicks > expectedOwnedTicks + COOLDOWN_TOLERANCE_TICKS) return -1;
        if (currentTicks + COOLDOWN_TOLERANCE_TICKS < expectedOwnedTicks) return -1;
        return Math.max(0, previousTicks);
    }
    static int remainingTicks(int initialTicks, long elapsedTicks) {
        if (initialTicks <= 0 || elapsedTicks >= initialTicks) return 0;
        return (int) Math.max(0L, initialTicks - Math.max(0L, elapsedTicks));
    }
    public static int toTicks(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) return 0;
        long millis = duration.toMillis();
        long rounded = millis > Long.MAX_VALUE - 49L ? Long.MAX_VALUE : millis + 49L;
        long ticks = Math.max(1L, rounded / 50L);
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }
    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    public record Snapshot(List<SnapshotEntry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
        public static Snapshot empty() { return new Snapshot(List.of()); }
    }
    public record SnapshotEntry(UUID playerId, Material material, int previousTicks,
                                int appliedTicks, long appliedAtTick, long expiresAtMillis) { }
    private record Key(UUID playerId, Material material) { }
    private record OwnedOverlay(int previousTicks, int appliedTicks,
                                long appliedAtTick, long expiresAtMillis) { }
}
