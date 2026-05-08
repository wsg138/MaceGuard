package com.lincoln.maceguard.core.model;

public record EndIslandSettings(
        boolean enabled,
        int islandRadius,
        boolean blockMaces,
        boolean blockSpears,
        boolean funBedSleep,
        EndIslandExplosiveSettings explosives
) {
}
