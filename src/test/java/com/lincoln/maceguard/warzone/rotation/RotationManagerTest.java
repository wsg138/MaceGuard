package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.lincoln.maceguard.warzone.restriction.CooldownService;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RotationManagerTest {
    @TempDir Path directory;

    @Test void restoresActiveNextDeadlineAndWarningsAfterRestart() {
        MutableClock clock = new MutableClock(1_000_000);
        WarzoneStateStore store = store();
        RotationManager first = manager(store, clock);
        long end = first.state().endsAtMillis();
        store.update(new RotationState("a", first.state().startedAtMillis(), end, "b", Set.of(10L)));
        clock.advance(Duration.ofSeconds(12));
        RotationManager restored = manager(new WarzoneStateStore(directory.resolve("state.yml"),
                Logger.getLogger("test"), Runnable::run), clock);
        assertEquals("a", restored.state().activeRotationId());
        assertEquals("b", restored.state().nextRotationId());
        assertEquals(end, restored.state().endsAtMillis());
        assertEquals(Set.of(10L), restored.state().emittedWarningsSeconds());
    }

    @Test void advancesAcrossEveryDeadlineElapsedWhileServerWasOffline() {
        MutableClock clock = new MutableClock(2_000_000);
        manager(store(), clock);
        clock.advance(Duration.ofSeconds(135));
        RotationManager restored = manager(new WarzoneStateStore(directory.resolve("state.yml"),
                Logger.getLogger("test"), Runnable::run), clock);
        assertEquals("a", restored.active().id());
        assertEquals(Duration.ofSeconds(45), restored.remaining());
    }

    @Test void transitionCallbackClearsCooldownsFromPreviousRotation() {
        MutableClock clock = new MutableClock(3_000_000);
        AtomicLong cooldownClock = new AtomicLong(3_000_000);
        CooldownService cooldowns = new CooldownService(cooldownClock::get);
        RestrictionTarget target = RestrictionTarget.parse("MACE").orElseThrow();
        UUID player = UUID.randomUUID();
        cooldowns.start(player, target, Duration.ofMinutes(1));
        RotationManager manager = new RotationManager(config(), store(), clock,
                (previous, current, announce) -> cooldowns.clear(), (rotation, remaining) -> { });
        manager.skip();
        assertEquals(0, cooldowns.size());
    }

    private RotationManager manager(WarzoneStateStore store, Clock clock) {
        return new RotationManager(config(), store, clock, (previous, current, announce) -> { },
                (rotation, remaining) -> { });
    }

    private WarzoneStateStore store() {
        return new WarzoneStateStore(directory.resolve("state.yml"), Logger.getLogger("test"), Runnable::run);
    }

    private WarzoneConfig config() {
        RestrictionTarget mace = RestrictionTarget.parse("MACE").orElseThrow();
        WarzoneConfig.Restriction restriction = new WarzoneConfig.Restriction(mace, RestrictionMode.DISABLED, null);
        return new WarzoneConfig(3, true, new WarzoneConfig.Region("world", "warzone"), List.of(),
                new WarzoneConfig.Messages(Duration.ofSeconds(2), WarzoneConfig.Audience.GLOBAL,
                        WarzoneConfig.Audience.GLOBAL),
                new WarzoneConfig.Cobwebs(Duration.ofSeconds(60), true, true),
                Map.of(mace, new WarzoneConfig.TargetPolicy(true, false, null)),
                List.of(rotation("a", restriction), rotation("b", restriction)));
    }

    private WarzoneConfig.Rotation rotation(String id, WarzoneConfig.Restriction restriction) {
        return new WarzoneConfig.Rotation(id, id, id, Duration.ofSeconds(60), true,
                Map.of(restriction.target(), restriction), "", null, null);
    }

    private static final class MutableClock extends Clock {
        private long millis;
        private MutableClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        void advance(Duration duration) { millis += duration.toMillis(); }
    }
}
