package com.lincoln.maceguard.reset;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfirmationTokens {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public ConfirmationTokens() { this(Clock.systemUTC()); }
    ConfirmationTokens(Clock clock) { this.clock = clock; }

    public String issue(ResetPlan plan) {
        byte[] value = new byte[12];
        random.nextBytes(value);
        String token = HexFormat.of().formatHex(value);
        tokens.put(token, new Entry(plan.planHash(), clock.millis() + 300_000));
        return token;
    }

    public boolean consume(String token, ResetPlan currentPlan) {
        Entry entry = tokens.remove(token);
        return entry != null && entry.expiresAt >= clock.millis() && entry.planHash.equals(currentPlan.planHash());
    }

    public void clear() { tokens.clear(); }
    private record Entry(String planHash, long expiresAt) { }
}
