package com.lincoln.maceguard.core.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Main-thread combat state. A context is created from a real damage event and is
 * only eligible for the armor-durability callbacks immediately following it.
 */
public final class MaceDurabilityTracker {
    private static final int MAX_CONTEXTS_PER_VICTIM = 8;

    private final Map<AttackKey, AttackSnapshot> attackSnapshots = new HashMap<>();
    private final Map<UUID, Deque<HitContext>> contextsByVictim = new HashMap<>();
    private long tick;

    public void advanceTick() {
        tick++;
        attackSnapshots.entrySet().removeIf(entry -> entry.getValue().expiresAfter(tick));
        contextsByVictim.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(context -> context.expiresAfter(tick));
            return entry.getValue().isEmpty();
        });
    }

    public long currentTick() {
        return tick;
    }

    public void recordMaceAttackSnapshot(UUID attacker, UUID victim) {
        attackSnapshots.put(new AttackKey(attacker, victim), new AttackSnapshot(tick));
    }

    public boolean consumeMaceAttackSnapshot(UUID attacker, UUID victim) {
        AttackSnapshot snapshot = attackSnapshots.remove(new AttackKey(attacker, victim));
        return snapshot != null && !snapshot.expiresAfter(tick);
    }

    public void createContext(UUID attacker, UUID victim, String zoneName, int cap, EnumSet<ArmorSlot> expectedArmor) {
        if (expectedArmor.isEmpty()) {
            return;
        }
        Deque<HitContext> contexts = contextsByVictim.computeIfAbsent(victim, ignored -> new ArrayDeque<>());
        while (contexts.size() >= MAX_CONTEXTS_PER_VICTIM) {
            contexts.removeFirst();
        }
        contexts.addLast(new HitContext(attacker, victim, tick, zoneName, cap, expectedArmor));
    }

    public Optional<HitContext> claim(UUID victim, ArmorSlot armorSlot) {
        Deque<HitContext> contexts = contextsByVictim.get(victim);
        if (contexts == null) {
            return Optional.empty();
        }
        Iterator<HitContext> iterator = contexts.iterator();
        while (iterator.hasNext()) {
            HitContext context = iterator.next();
            if (context.claim(armorSlot)) {
                if (context.complete()) {
                    iterator.remove();
                    if (contexts.isEmpty()) {
                        contextsByVictim.remove(victim);
                    }
                }
                return Optional.of(context);
            }
        }
        return Optional.empty();
    }

    public void clearPlayer(UUID player) {
        contextsByVictim.remove(player);
        attackSnapshots.keySet().removeIf(key -> key.attacker().equals(player) || key.victim().equals(player));
    }

    public void clear() {
        attackSnapshots.clear();
        contextsByVictim.clear();
    }

    public enum ArmorSlot { HEAD, CHEST, LEGS, FEET }

    public static final class HitContext {
        private final UUID attacker;
        private final UUID victim;
        private final long createdTick;
        private final String zoneName;
        private final int cap;
        private final EnumSet<ArmorSlot> pendingArmor;

        private HitContext(UUID attacker, UUID victim, long createdTick, String zoneName, int cap, EnumSet<ArmorSlot> expectedArmor) {
            this.attacker = attacker;
            this.victim = victim;
            this.createdTick = createdTick;
            this.zoneName = zoneName;
            this.cap = cap;
            this.pendingArmor = EnumSet.copyOf(expectedArmor);
        }

        private boolean claim(ArmorSlot slot) {
            return pendingArmor.remove(slot);
        }

        private boolean complete() {
            return pendingArmor.isEmpty();
        }

        private boolean expiresAfter(long currentTick) {
            return currentTick > createdTick;
        }

        public UUID attacker() { return attacker; }
        public UUID victim() { return victim; }
        public long createdTick() { return createdTick; }
        public String zoneName() { return zoneName; }
        public int cap() { return cap; }
    }

    private record AttackKey(UUID attacker, UUID victim) { }

    private record AttackSnapshot(long createdTick) {
        boolean expiresAfter(long currentTick) {
            return currentTick > createdTick;
        }
    }
}
