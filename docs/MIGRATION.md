# MaceGuard 5 migration

## Main configuration

MaceGuard 5 requires `config-version: 8`. When an older main configuration is found, MaceGuard:

1. copies the original file into the migration directory;
2. writes a review report describing removed or unsupported fields;
3. installs the clean bundled schema-8 example while preserving only explicitly safe runtime tuning and supported End-island settings when they validate;
4. does not create or modify WorldGuard regions;
5. does not capture a snapshot, arm a profile, or enable a reset schedule.

Removed legacy concepts include coordinate zones, `gameplay_zones`, `zones`, `AIR`, `SNAPSHOT`, `SPARSE_SNAPSHOT`, duel footprints, End-access scheduling, explosion percentages, backstop scanning, old placement lists, and old weekly reset coordinates.

## Warzone configuration and state

`warzone.yml` version 4 replaces ordered short rotations with weekly random composable modifiers. Version-3 sequential rotations are backed up and reported for manual review. They are not silently converted because an ordered duration-based list does not have a safe one-to-one meaning in the weekly modifier model.

Fresh schema-4 files install with `enabled: false`. An existing valid schema-4 file is not rewritten, so its explicit `enabled` value is preserved. A valid version-4 persisted weekly state is also preserved. Legacy sequential state is backed up and ignored rather than reinterpreted. Corrupt state is preserved as an invalid backup and replaced with a safe current-week selection.

The standalone `plugins/WarzoneRotator` directory is never deleted or rewritten. Keep it through staging and rollback review.

## 4.0.1 production incident distinction

MaceGuard 4.0.1 could treat the entire configured Bukkit world as inside the warzone when the configured outer `warzone` WorldGuard region could not be resolved. This allowed selected restrictions to affect ordinary world gameplay depending on the held item, event path, and bypass permission.

MaceGuard 5.0.0 does not use that fallback. The effective gameplay scope is active only when the configured world, outer region, and every required exclusion resolve. Losing any required geometry makes all locations outside the effective scope until exact resolution returns. The selected weekly state remains persisted and visible for diagnostics, but restrictions, positive effects, cooldown overlays, warzone cobweb behavior, and automated warzone announcements are inactive.

An explicitly configured WorldGuard `maceguard-block-policy` value on `__global__` is separate operator configuration. It remains effective until manually unset. MaceGuard 5 identifies and warns about the global source but does not modify WorldGuard flags.

Before upgrading or investigating unexpected restrictions, run:

```text
/version MaceGuard
/rg info warzone
/rg flags warzone
/rg flags __global__
/warzone validate
/warzone debug
/maceguard here
```

The intended policy assignment is normally only:

```text
/rg flag cobweb-box maceguard-block-policy cobweb-box
```

The custom block-policy flag should normally remain unset on `__global__`, `warzone`, `spawn`, `market`, and `war-pit`.

## Reset data

Full and filtered snapshots use the current snapshot format and remain bound to:

- world UUID and name;
- exact cuboid geometry and geometry hash;
- named profile and mode;
- exclusions hash;
- snapshot checksum and format;
- explicit armed state.

A schema/profile/geometry/exclusion mismatch disarms or refuses the reset. Production regions are never automatically recaptured or rearmed after migration.

## Rollback

1. Stop the server cleanly.
2. Preserve `plugins/MaceGuard/state`, snapshots, migration reports, and restore journals.
3. Restore the world and WorldGuard database from the same known-good backup if a destructive staged operation was interrupted.
4. Restore the prior JAR and its matching configuration files.
5. Keep the standalone WarzoneRotator directory intact until rollback is no longer required.
