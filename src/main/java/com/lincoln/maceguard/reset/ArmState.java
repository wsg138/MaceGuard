package com.lincoln.maceguard.reset;

public record ArmState(String worldUuid, String regionId, String geometryHash, String profile, String mode,
                       String exclusionsHash, int snapshotFormat, String snapshotChecksum, long armedAt) { }
