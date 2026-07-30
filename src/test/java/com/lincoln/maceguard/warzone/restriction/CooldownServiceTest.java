package com.lincoln.maceguard.warzone.restriction;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CooldownServiceTest {
    private final AtomicLong clock = new AtomicLong(1_000);
    private final CooldownService cooldowns = new CooldownService(clock::get);
    private final RestrictionTarget pearl = RestrictionTarget.parse("ENDER_PEARL").orElseThrow();

    @Test void createsAndExpiresCooldownPrecisely() {
        UUID player = UUID.randomUUID();
        cooldowns.start(player, pearl, Duration.ofMillis(1_500));
        assertEquals(Duration.ofMillis(1_500), cooldowns.remaining(player, pearl));
        clock.addAndGet(1_499);
        assertEquals(Duration.ofMillis(1), cooldowns.remaining(player, pearl));
        clock.incrementAndGet();
        assertFalse(cooldowns.active(player, pearl));
        assertEquals(0, cooldowns.size());
    }

    @Test void isolatesCooldownsBetweenPlayers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cooldowns.start(first, pearl, Duration.ofSeconds(10));
        assertTrue(cooldowns.active(first, pearl));
        assertFalse(cooldowns.active(second, pearl));
    }

    @Test void transitionCallbackCanClearAllPreviousRotationCooldowns() {
        UUID player = UUID.randomUUID();
        cooldowns.start(player, pearl, Duration.ofSeconds(10));
        cooldowns.clear();
        assertFalse(cooldowns.active(player, pearl));
    }
}
