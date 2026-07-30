package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

import java.time.Duration;

public record RestrictionDecision(
        Result result,
        RestrictionTarget target,
        WarzoneConfig.Restriction restriction,
        Duration remaining
) {
    public enum Result { UNRESTRICTED, DISABLED, COOLDOWN_READY, COOLDOWN_ACTIVE }

    public boolean denied() { return result == Result.DISABLED || result == Result.COOLDOWN_ACTIVE; }
    public boolean startsCooldownAfterSuccess() { return result == Result.COOLDOWN_READY; }

    public static RestrictionDecision unrestricted() {
        return new RestrictionDecision(Result.UNRESTRICTED, null, null, Duration.ZERO);
    }
}
