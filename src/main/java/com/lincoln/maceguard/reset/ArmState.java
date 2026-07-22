package com.lincoln.maceguard.reset;

public record ArmState(String worldUuid, String regionId, String geometryHash, String profile, String mode,
                       String exclusionsHash, int snapshotFormat, String snapshotChecksum, long armedAt,
                       Boolean scheduleEnabled) {
    /** Null preserves the enabled behavior of arming records written before this field existed. */
    public boolean isScheduleEnabled() { return scheduleEnabled == null || scheduleEnabled; }
}
