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

A valid version-4 persisted weekly state is preserved. Legacy sequential state is backed up and ignored rather than reinterpreted. Corrupt state is preserved as an invalid backup and replaced with a safe current-week selection.

The standalone `plugins/WarzoneRotator` directory is never deleted or rewritten. Keep it through staging and rollback review.

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
