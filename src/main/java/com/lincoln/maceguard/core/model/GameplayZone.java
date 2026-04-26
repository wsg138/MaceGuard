package com.lincoln.maceguard.core.model;

import java.util.List;
import java.util.Set;

public record GameplayZone(
        String name,
        CuboidRegion region,
        int priority,
        boolean allowAllPlace,
        boolean allowAllBreak,
        Set<String> allowedPlace,
        Set<String> denyPlace,
        boolean externallyManaged,
        boolean confineLiquids,
        boolean blockInfiniteSources,
        int ttlSeconds,
        int fullResetMinutes,
        ResetMode resetMode,
        ResetScope resetScope,
        List<Integer> warnBeforeSeconds,
        MaceDurabilityRule maceDurabilityRule
) {
}
