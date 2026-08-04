# MaceGuard 5 migration

## Main configuration

MaceGuard 5 requires `config-version: 8` for `config.yml`. When an older main configuration is found, MaceGuard:

1. copies the original file into the migration directory;
2. writes a review report describing removed or unsupported fields;
3. installs the clean bundled schema-8 example while preserving only explicitly safe settings that validate;
4. does not create or modify WorldGuard regions;
5. does not capture a snapshot, arm a profile, or enable a reset schedule.

## Warzone schema 4 to schema 5

`warzone.yml` schema 5 adds independently enabled weighted outcomes, weighted modifier counts, Ender Pearl and Wind Charge modes, and explicit Elytra selection rules.

When a schema-4 Warzone configuration is found, MaceGuard first creates a timestamped backup under:

```text
plugins/MaceGuard/migration-backups/
```

It then starts from the bundled schema-5 defaults and preserves the following schema-4 values when present:

- the top-level module `enabled` value;
- `region.world`;
- `region.id`;
- every `region.excluded-region-ids` entry;
- the weekly schedule day, time, and timezone;
- warning times;
- message audience and blocked-message cooldown settings;
- cobweb cleanup settings;
- restriction-target policies;
- existing conflict groups;
- built-in modifier display names, descriptions, effects, restrictions, and start/end/warning messages;
- valid custom modifier definitions, including custom IDs referenced by conflict groups;
- explicit modifier `enabled` or `weight` values if an operator had already added them;
- the existing persisted weekly state file.

New schema-5 fields receive bundled defaults:

```yaml
rotation:
  selection:
    mode: WEIGHTED_RANDOM_MODIFIERS
    minimum: 1
    maximum: 3
    prevent-identical-repeat: true
    count-weights:
      1: 35
      2: 45
      3: 20
  special-rules:
    elytra-no-rockets:
      weekly-inclusion-chance-percent: 8
      unrestricted-mace-chance-percent: 90
```

Bundled modifiers retain their bundled schema-5 `enabled` and `weight` defaults unless schema 4 explicitly provided those fields. A custom schema-4 modifier receives `enabled: true` and `weight: 10` only when those fields were absent. New Pearl and Wind Charge outcomes, their restriction targets, and their conflict groups are added from the bundled schema.

The complete migrated file is validated before replacement. If a custom modifier, custom conflict group, restriction, count rule, or other preserved value does not validate under schema 5, migration deletes the temporary output and leaves the original `warzone.yml` active and unchanged. The timestamped backup and the validation error remain available for manual migration.

The migration does not automatically enable the Warzone module. A disabled schema-4 file remains disabled after migration.

## Persisted weekly selection

A valid persisted modifier combination remains active when every selected ID is still enabled and valid under schema 5.

When a persisted selection contains a newly disabled, unknown, or conflicting ID, MaceGuard selects a valid replacement while preserving:

- the stored weekly calendar boundary;
- the stored effective transition time;
- the current weekly period.

It does not shift the Sunday 04:00 schedule merely because configuration changed. The replacement selection and updated sequence are persisted atomically.

Legacy sequential state is different from weekly state. If the state file does not contain the weekly `selection.active-modifiers` structure, it is backed up and ignored rather than reinterpreted as a weekly selection. Corrupt state is also preserved as a backup before a fresh safe selection is created.

## Older incompatible Warzone configurations

Schemas older than 4 are backed up but are not silently translated into schema 5 because ordered duration-based rotations do not have a safe one-to-one meaning in the weekly weighted model. MaceGuard installs a clean schema-5 example that remains disabled by default and records the decision in its migration report.

The standalone `plugins/WarzoneRotator` directory is never deleted or rewritten. Keep it through staging and rollback review.

## Safety boundaries

Warzone migration never:

- creates, deletes, resizes, or changes WorldGuard regions;
- changes region priorities, parents, owners, members, or flags;
- enables the Warzone module automatically;
- broadens an unresolved scope to the whole world;
- discards a valid custom schema-4 modifier silently;
- replaces the original schema-4 file with an invalid migrated file;
- captures a reset snapshot;
- arms a reset profile;
- enables or changes a reset schedule;
- alters restore journals or confirmation tokens.

The effective gameplay scope remains inactive whenever the configured world, outer region, or any required exclusion is unresolved.

## Post-migration review

After startup:

```text
/version MaceGuard
/warzone validate
/warzone debug
/warzone modifiers
/warzone items
```

Confirm:

1. `warzone.yml` reports `config-version: 5`.
2. The module enabled value matches the old file.
3. World and region IDs are unchanged.
4. Sunday 04:00 Indiana scheduling is unchanged.
5. Existing safe messages and built-in modifier text remain present.
6. Every expected custom modifier and custom conflict group remains present.
7. Every enabled modifier has a positive weight.
8. Count weights and Elytra percentages match operator intent.
9. Every configured probability branch has at least one feasible combination.
10. The current selected IDs are all enabled and conflict-free.
11. The weekly boundary and transition were not shifted by a required reroll.
12. No reset profile was captured, armed, or scheduled.

Review the generated report under `plugins/MaceGuard/migration-reports/` and retain both it and the timestamped configuration backup through staging.

## Production incident distinction

MaceGuard 4.0.1 could treat the entire configured Bukkit world as inside the Warzone when the configured outer region could not be resolved. MaceGuard 5.0.0 and later do not use that fallback. Losing any required geometry makes restrictions, positive effects, cooldown overlays, Warzone cobweb behavior, and Warzone-only announcements inactive until exact resolution returns.

An explicitly configured WorldGuard `maceguard-block-policy` value on `__global__` is separate operator configuration. It remains effective until manually changed; MaceGuard reports the source but does not modify it.

## Reset data

Full and filtered snapshots remain bound to world identity, exact geometry, profile mode, exclusions, checksum, snapshot format, and explicit armed state. A schema/profile/geometry/exclusion mismatch disarms or refuses the reset. Production regions are never automatically recaptured or rearmed after migration.

## Rollback

1. Stop the server cleanly.
2. Preserve `plugins/MaceGuard/state`, snapshots, migration reports, backups, and restore journals.
3. Restore the world and WorldGuard database from the same known-good backup if a destructive staged operation was interrupted.
4. Restore the prior JAR and its matching configuration files.
5. Keep the standalone WarzoneRotator directory intact until rollback is no longer required.
