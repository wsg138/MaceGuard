package com.lincoln.maceguard.region;

import java.util.List;
import java.util.Set;

public final class Zone {
    public enum ResetMode { AIR, SNAPSHOT }
    public enum ResetScope { CHANGED, FULL } // FULL = restore entire cuboid from snapshot

    public final String name;
    public final Cuboid cuboid;
    public final boolean allowAllPlace;
    public final boolean allowAllBreak;
    public final Set<String> allowedPlace;
    public final Set<String> denyPlace;
    public final boolean confineLiquids;
    public final boolean blockInfiniteSources;
    public final int ttlSeconds;
    public final int fullResetMinutes;
    public final ResetMode resetMode;
    public final ResetScope resetScope;
    public final List<Integer> warnBeforeSeconds; // e.g., [300,60,30,5]
    public final int priority; // higher wins

    public Zone(String name, Cuboid cuboid,
                boolean allowAllPlace,
                boolean allowAllBreak,
                Set<String> allowedPlace,
                Set<String> denyPlace,
                boolean confineLiquids,
                boolean blockInfiniteSources,
                int ttlSeconds,
                int fullResetMinutes,
                ResetMode resetMode,
                ResetScope resetScope,
                List<Integer> warnBeforeSeconds,
                int priority) {
        this.name = name;
        this.cuboid = cuboid;
        this.allowAllPlace = allowAllPlace;
        this.allowAllBreak = allowAllBreak;
        this.allowedPlace = allowedPlace;
        this.denyPlace = denyPlace;
        this.confineLiquids = confineLiquids;
        this.blockInfiniteSources = blockInfiniteSources;
        this.ttlSeconds = ttlSeconds;
        this.fullResetMinutes = fullResetMinutes;
        this.resetMode = resetMode;
        this.resetScope = resetScope;
        this.warnBeforeSeconds = warnBeforeSeconds;
        this.priority = priority;
    }

    @Override public String toString() { return "Zone{" + name + " " + cuboid + " p=" + priority + " scope=" + resetScope + "}"; }
}
