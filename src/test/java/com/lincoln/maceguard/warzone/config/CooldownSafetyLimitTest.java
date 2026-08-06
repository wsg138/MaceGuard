package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CooldownSafetyLimitTest {
    @Test void acceptsLargestSupportedCooldown() {
        assertTrue(CooldownSafetyValidator.validate(control(Duration.ofDays(365))).isEmpty());
        assertEquals(Duration.ofDays(365), CooldownSafetyValidator.MAX_COOLDOWN);
    }

    @Test void rejectsCooldownBeyondSafetyLimit() {
        List<String> errors = CooldownSafetyValidator.validate(control(Duration.ofDays(366)));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().allMatch(value -> value.contains("must not exceed 365d")),
                errors.toString());
    }

    private WarzoneControlConfig control(Duration cooldown) {
        RestrictionTarget target = RestrictionTarget.parse("MACE").orElseThrow();
        WarzoneConfig.Modifier modifier = new WarzoneConfig.Modifier("test", true, 1, false,
                "Test", "Test", Set.of(), Map.of(target, new WarzoneConfig.Restriction(
                target, RestrictionMode.COOLDOWN, cooldown)), "", "", "");
        WarzoneConfig gameplay = new WarzoneConfig(5, true,
                new WarzoneConfig.Region("world", "warzone", List.of()),
                null, null, Map.of(), List.of(), null, null, null,
                Map.of(target, new WarzoneConfig.TargetPolicy(true, true, cooldown)),
                Map.of("test", modifier), Map.of());
        return new WarzoneControlConfig(WarzoneControlConfig.VERSION, gameplay, Map.of(),
                null, null);
    }
}
