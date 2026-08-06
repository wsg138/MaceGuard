# MaceGuard 6.1.1 migration

MaceGuard 6.1.1 retains Warzone configuration schema 7 for combat carryover and stasis settings. Migration is backup-first and validates the complete replacement before it can replace the active file. The existing versioned automatic-slot/manual-override state model remains in place.

## Main configuration

The existing main `config.yml`, WorldGuard regions and flags, reset profiles, snapshots, arming records, journals, and temporary-block files are not reinterpreted by the Warzone schema migration. Existing main-configuration migration behavior remains unchanged.

MaceGuard never creates or changes WorldGuard regions, captures snapshots, arms profiles, or enables reset schedules during this migration.


## Schema 6 to schema 7

Before rewriting `warzone.yml`, MaceGuard copies the schema-6 file to `plugins/MaceGuard/migration-backups/`. The schema-7 candidate preserves all operator-defined enabled states, region scope, rotation and schedule data, messages, cobweb settings, kits, GUI settings, restriction-target policies, modifier definitions and weights, and conflict groups.

Every existing modifier receives `combat-carryover: false` when the field was absent. This prevents an upgrade from unexpectedly extending a restriction outside the configured Warzone. The new stasis setting defaults to:

```yaml
combat:
  stasis:
    minimum-age: 60s
```

WorldGuard custom flags are registered by the plugin but are not assigned to any region by migration. No staff exemption is created. The complete schema-7 candidate is passed through the strict loader before atomic replacement.

## Schema 5 to schema 7

Before rewriting `warzone.yml`, MaceGuard copies it to `plugins/MaceGuard/migration-backups/`. It then builds a schema-7 candidate from the bundled defaults and preserves the schema-5 values for:

- top-level module enabled state;
- configured world, outer region, and exclusions;
- former weekday, local time, and timezone;
- weighted random minimum, maximum, repeat prevention, and count weights;
- special selection rules;
- warning times and audiences;
- messages and cobweb settings;
- restriction-target policies;
- bundled and custom modifier definitions;
- conflict groups.

The former weekly schedule becomes an anchored one-entry repeating cycle:

```yaml
rotation:
  schedule:
    enabled: true
    timezone: <former timezone>
    anchor-date: <a date on the former weekday>
    time: <former local time>
    cadence:
      every: 1
      unit: WEEKS
    cycle:
      - type: RANDOM
```

The anchor date is selected only to preserve the prior weekday phase. Calendar boundaries remain aligned to the former weekday, time, and timezone.

For every built-in or custom modifier that exists in the source schema-5 file, a missing `combat-carryover` field is written as `false`. Explicit `false` remains false and explicit `true` is preserved because schema-7 validation supports the field. The bundled schema-7 value is never inherited merely because an old modifier ID matches a bundled ID. New modifiers that did not exist in the source retain their bundled definition and remain disabled where the schema-5 migration contract requires it.

New spear outcomes and the bundled spear kit are disabled during schema-5 migration unless the old file already defined those IDs. This prevents the existing random pool from changing silently. Operators may enable them after reviewing `SPEAR`, `SPEAR_DAMAGE`, `SPEAR_LUNGE`, and the new conflict groups. Trident remains location-bound and is not a combat-carryover target.

The candidate is written to a temporary sibling and parsed by the strict schema-7 loader. If any preserved custom modifier, restriction, conflict, kit, schedule, material icon, or selection rule is invalid, the temporary file is deleted, migration is rejected, and the original active file is never replaced.

## Persisted schema-5 selection

The schema-5 state structure is recognized:

```text
selection.active-modifiers
selection.activated-at
selection.weekly-boundary
selection.transition-at
selection.emitted-warnings
selection.sequence
```

It is interpreted as the current `RANDOM` result for the one-entry migrated cycle. When its stored boundary and transition match the currently due migrated slot, the exact modifier order and selection sequence are retained and the random result is not rerolled. The next successful state write versions it into the current versioned state format.

If the old selection contains unknown, disabled, conflicting, out-of-range, or otherwise invalid random modifiers, it is rejected and a safe current-slot selection is generated. Invalid state is backed up and logged; it is never used to broaden gameplay scope.

## Schema 4 to schema 7

Schema 4 migrates through the existing validated schema-5 representation and then through the schema-5-to-7 process. Existing schema-4 scope, weekly schedule, warnings, messages, cobweb settings, restrictions, modifier definitions, and conflict groups are preserved where valid. Before the intermediate representation is passed forward, every source modifier missing `combat-carryover` is explicitly assigned `false`; this prevents schema-7 defaults from leaking through either migration stage. Explicit `true` and `false` remain unchanged.

A custom modifier receives `enabled: true` and `weight: 10` only when those fields were absent. Existing explicit values are retained. Custom names, weights, restrictions, messages, enabled states, and carryover values are copied before strict validation.

## Older or incompatible Warzone files

Configurations older than schema 4 do not have a safe one-to-one meaning in the weighted schema-5 model. They are backed up and replaced with the clean bundled schema-7 example, which remains disabled by default. The migration report records that decision.

The standalone `plugins/WarzoneRotator` directory is preserved unchanged for rollback. Sequential state that does not contain the recognized schema-5 selection structure is backed up and ignored rather than guessed into a cycle.

## Versioned Warzone state

`state/warzone-state.yml` now records:

- state version;
- automatic slot identity, absolute index, cycle position, and phase offset;
- persisted schedule enable override;
- automatic slot start/end and activation time;
- automatic source type, source ID, and ordered modifier IDs;
- optional manual source type, source ID, ordered modifier IDs, duration mode, activation time, and expiration;
- emitted warning thresholds;
- selection sequence.

Writes remain atomic and ordered through the existing storage executor. A matching `RANDOM` automatic result is reused after restart. Exact scheduled kits or modifier collections are recomputed from the current validated configuration, while a manual override retains its confirmed exact modifier set and expiration.

## Post-migration review

After migration:

1. Read the newest migration report and confirm the backup paths.
2. Run `/warzone validate` before enabling the module.
3. Review the anchor, cadence, cycle, kit order, icons, and enabled states.
4. Confirm migrated new spear outcomes and the spear kit remain disabled unless intentionally enabled.
5. Run `/warzone debug` and verify automatic source, slot start/end, cycle position, state health, and final active source.
6. Restart during the same migrated random slot and confirm modifier order and selection sequence remain unchanged.
7. Test a boundary while offline and confirm startup jumps directly to the current slot.
8. Test one-hour, next-boundary, and indefinite overrides across restart and reload.
9. Complete the deployment and live staging checklist before production merge.

## Production incident distinction

MaceGuard 4.0.1 could treat the configured Bukkit world as inside the Warzone when the outer region was unresolved. MaceGuard 5.0.0 and later do not use that fallback. Missing required geometry makes Warzone restrictions, positive effects, cooldown overlays, cobweb behavior, and Warzone-only announcements inactive until exact scope resolution returns.

## Rollback

To roll back:

1. Stop the server.
2. Preserve the current schema-7 config and state for diagnosis.
3. Restore the matching pre-migration `warzone.yml` backup and plugin JAR together.
4. Restore a matching older state file only when the older plugin expects it.
5. Leave WorldGuard regions, reset snapshots, and temporary-block recovery files intact unless a separate verified rollback procedure requires otherwise.
