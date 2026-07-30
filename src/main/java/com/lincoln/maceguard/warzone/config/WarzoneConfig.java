package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record WarzoneConfig(
        int version,
        boolean enabled,
        Region region,
        List<Duration> warningTimes,
        Messages messages,
        Cobwebs cobwebs,
        Map<RestrictionTarget, TargetPolicy> targetPolicies,
        List<Rotation> rotations
) {
    public Map<String, Rotation> rotationsById() {
        return rotations.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Rotation::id, value -> value));
    }

    public record Region(String world, String id) { }
    public record Messages(Duration blockedMessageCooldown, Audience warningAudience, Audience transitionAudience) { }
    public record Cobwebs(Duration clearAfter, boolean clearOnMetaChange, boolean clearOnDisable) { }
    public record TargetPolicy(boolean canDisable, boolean canCooldown, Duration maximumCooldown) { }
    public record Restriction(RestrictionTarget target, RestrictionMode mode, Duration cooldown) { }
    public record Rotation(
            String id,
            String displayName,
            String description,
            Duration duration,
            boolean cobwebsAllowed,
            Map<RestrictionTarget, Restriction> restrictions,
            String startMessage,
            String endMessage,
            String warningMessage
    ) { }

    public enum Audience { GLOBAL, WARZONE }
}
