# MaceGuard

[![Build](https://github.com/wsg138/MaceGuard/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/wsg138/MaceGuard/actions/workflows/build.yml)
[Download the latest automated JAR](https://github.com/wsg138/MaceGuard/releases/download/latest/MaceGuard.jar)

MaceGuard adds opt-in combat behavior, integrated warzone rotations, persistent temporary cobwebs, and safe reset tooling to WorldGuard regions. WorldGuard remains the sole authority for region geometry, membership, ownership, priorities, parents, and ordinary protection.

## Requirements and installation

- Java 21
- Paper 1.21.11-compatible server
- WorldGuard 7.0.17 (and its required WorldEdit version)
- Optional: PlaceholderAPI 2.11.6 or newer

Install WorldEdit and WorldGuard first, then MaceGuard. WarzoneRotator is integrated into MaceGuard 4.0.0 and its separate JAR is not required. Keep the old WarzoneRotator directory during initial deployment so its configuration/state remains available for migration and rollback. MaceGuard registers its flags during `onLoad`, before WorldGuard locks the registry.

## WorldGuard flags

| Flag | Type | Effect |
| --- | --- | --- |
| `maceguard-mace-durability` | state | `ALLOW` enables the configured armor durability cap at the victim. Missing/`DENY` does nothing. |
| `maceguard-cobwebs` | state | `ALLOW` enables tracked cobweb TTL behavior after all build and integrated rotation checks pass. |
| `maceguard-explosives` | state | `DENY` blocks explosive placement/use and cancels explosions. Missing/`ALLOW` does nothing. |
| `maceguard-reset-profile` | string | Directly associates an exact WorldGuard cuboid region with a named config profile. |
| `warzonerotator-cobwebs` | state | Preserved compatibility flag. `DENY` blocks integrated warzone cobweb placement; missing/`ALLOW` permits the rotation check to continue. |

Examples:

```text
/rg flag warzone maceguard-mace-durability allow
/rg flag warzone maceguard-cobwebs allow
/rg flag warzone warzonerotator-cobwebs allow
/rg flag war-pit maceguard-reset-profile war-pit
/rg flag war-pit maceguard-explosives deny
```

Create the war-pit region from the exact WorldEdit cuboid selected on the server, then apply its policy:

```text
/rg define war-pit
/rg flag war-pit block-break allow
/rg flag war-pit block-place allow
/rg flag war-pit maceguard-cobwebs allow
/rg flag war-pit maceguard-reset-profile war-pit
/rg flag war-pit maceguard-explosives deny
```

The repository cannot safely pre-create that live WorldGuard region because the world and selected bounds belong to the server's WorldGuard data. MaceGuard deliberately stores no duplicate war-pit coordinates.

Do not set `build allow` merely to enable MaceGuard behavior. Configure WorldGuard membership, passthrough, parents, priorities, and protection flags normally.

## Safe configuration

There are no coordinates in `config.yml`. Numeric limits, schedules, batching, storage policy, and main-End-island weapon restrictions remain MaceGuard settings.

```yaml
config-version: 7
mace-durability:
  damage-per-armor-piece: 2
performance:
  capture-batch-size: 2000
  plan-batch-size: 4000
  restore-batch-size: 1000
reset-profiles:
  war-pit:
    mode: FULL_SNAPSHOT
    interval-minutes: 60
    max-coordinates: 500000
    max-total-changes: 100000
    max-air-changes: 25000
    excluded-region-ids: []
```

Missing/unknown modes, missing profiles, invalid limits, unresolved exclusions, and old config versions disable destructive behavior. There is no `AIR` mode.

Warzone configuration is separate:

- `warzone.yml` contains the target region, schedule, rotations, restriction policies, item/ability cooldowns, and cobweb rotation policy.
- `warzone-messages.yml` contains MiniMessage templates.
- `state/warzone-state.yml` contains the active/next rotation, timestamps, and emitted warning thresholds.

Both YAML files reject duplicate keys and report path-specific validation errors. `/warzone reload` validates a complete replacement before changing the live runtime. `/maceguard reload` validates both the main and warzone configuration first. See [Integrated warzone configuration](docs/WARZONE.md).

## Full snapshot reset lifecycle

1. Define and review the region in WorldGuard. Only exact cuboid regions are supported.
2. Add a valid config profile with conservative total-change and air-change limits.
3. Set `maceguard-reset-profile` directly on that region.
4. Run `/maceguard capture <region>`. Every required chunk must already be loaded; MaceGuard never force-loads it.
5. Run `/maceguard validate <region>`.
6. Run `/maceguard plan <region>` and review all counts.
7. Run `/maceguard arm <region>`.
8. Run `plan` again and execute `/maceguard reset <region> <token>` with the current one-use token.

An interval greater than zero enables automatic preflight after arming. `/maceguard schedule <region> off` pauses automatic restores without discarding the valid armed snapshot; `on` starts a fresh interval. Automatic resets never use confirmation-token overrides and are disarmed if resolution, validation, or a safety threshold fails.

`/maceguard filler <region> off` (also available as `restore`) durably disarms the region, stopping both manual and automatic restores. Turning it on uses the normal `arm` path and therefore refuses to enable if the snapshot/baseline is missing or invalid. A missing snapshot never causes MaceGuard to fill a region with air or any fallback material.

Snapshots contain a format/plugin version, region/world identity, world UUID, exact cuboid geometry and hash, profile, capture timestamps, completion marker, full coordinate count, explicit air block data, checksum, and supported container inventories. A snapshot with unsupported tile entities is not captured. Legacy snapshots are never loaded from the old directory.

Geometry, world UUID/type, profile/mode, snapshot format/checksum, or exclusion changes invalidate the armed state. MaceGuard does not automatically re-arm it.

## Preflight, recovery, and rollback

Preflight reports inspected coordinates, total/non-air/air changes, block entities, exclusions, and batches. A token is bound to that exact plan, expires after five minutes, and is single-use. Changed world state makes it stale.

Restore progress is atomically journaled before mutation and after every batch. Interrupted `PREPARED` or `RESTORING` state is reported by `/maceguard recover <region>` and blocks all further resets; MaceGuard never automatically resumes destructive work. Keep the snapshot and journal for inspection, disarm the region, restore the server/world backup if needed, and only replace the journal after determining the actual world state offline.

## Sparse originals

`SPARSE_ORIGINALS` uses the same WorldGuard association, exclusions, arming, preflight, thresholds, confirmation, and restore journal. Run `capture` to create an empty, versioned baseline, validate it, then arm. For the first player place/break at a coordinate, MaceGuard cancels that attempt, commits the exact original state atomically off-thread, and tells the player to retry. The retry is accepted only after the sole original is durable. Later changes at that coordinate do not replace the original. After a completed restore, the baseline is cleared only after the completion journal is durable.

Non-player world mutations (including explosions, pistons, fluid flow, growth, and commands from other plugins) are intentionally not inferred as sparse player edits. Configure WorldGuard to deny those mutation sources inside a sparse restoration region if they must be reversible. MaceGuard does not cancel or reinterpret them and never restores a coordinate it did not durably journal.

Rollback procedure:

1. Stop the server cleanly.
2. Preserve `plugins/MaceGuard/state` and `snapshots-v1` for diagnosis.
3. Restore the world and WorldGuard region database from the same known-good backup.
4. Restore the previous MaceGuard JAR/config only if its coordinate-based reset features are disabled.
5. Start, validate WorldGuard behavior, capture a fresh baseline, preflight, and arm explicitly.

## Integrated warzone rotations

The integrated module preserves `/warzone`, `/warzonerotator`, `/wzr`, the `warzonerotator.*` permissions, and the `%warzone_*%` PlaceholderAPI identifiers. A rotation can disable a complete material, apply a server-authoritative per-player cooldown, disable all `*_SPEAR` materials through `SPEAR`, or restrict only the `SPEAR_LUNGE` effect.

`SPEAR_LUNGE` never restricts holding, hotbar selection, hand swapping, normal spear damage, or throwing. A disabled/active-cooldown Lunge suppresses only a short, direction-correlated Lunge velocity after a spear swing. Cooldowns start at the successful projectile, damage, placement, or Lunge event monitor and are not created for actions cancelled by another plugin. They remain keyed by UUID after logout, expire in memory, clear on rotation changes/reload, and are never written on item use.

Restrictions apply when the actor is inside the configured warzone or a restricted direct attack targets an entity inside it. `warzonerotator.bypass` bypasses both disabled and cooldown restrictions.

## Cobweb behavior

MaceGuard is the only temporary-cobweb state owner. Inside the configured warzone, placement requires normal WorldGuard build permission, `maceguard-cobwebs ALLOW`, `warzonerotator-cobwebs` not `DENY`, the active rotation to allow cobwebs, and no applicable item restriction. Outside that target region, the existing MaceGuard temporary-block policy remains independent of the rotation.

Successful tracked placements preserve exact original block data and use MaceGuard's replacement-material allowlist, tracked-block limit, atomic persistent state, expiry, reset reconciliation, and shutdown behavior. A meta transition can clear only tracked cobwebs inside the target warzone. A completed reset discards every temporary-block record inside its restored cuboid so the reset remains authoritative.

For a cobweb/warzone area: define the WorldGuard region, configure its membership and `block-break`/`block-place` policy, set `maceguard-cobwebs allow` and `warzonerotator-cobwebs allow`, and then capture/validate/arm it only if that same region also needs reset behavior. Cobweb TTL alone does not require a snapshot. A resetting war pit additionally needs `maceguard-reset-profile war-pit`, followed by `capture`, `validate`, `plan`, and `arm`.

## Explosive behavior

`maceguard-explosives deny` blocks TNT placement, TNT minecart and end-crystal placement, respawn-anchor use, crystal detonation, priming, and entity/block explosion events at the effective WorldGuard location. This also stops other explosion sources such as creepers and beds because the flag means no explosions, not merely no block damage. The flag does not replace WorldGuard's other build or interaction flags.

## Mace durability and End behavior

Mace durability uses Paper's damage source plus a tick-scoped pre-attack context. It changes only equipped armor item-damage events and clears state on expiry, quit, death, reload, and disable. Health, damage calculation, knockback, cooldowns, enchantments, attributes, and weapon switching remain vanilla.

End Eye throwing and End portal lighting are no longer intercepted or scheduled by MaceGuard. Main-End-island mace/spear restrictions remain global configuration.

WarzoneDuels-specific cuboids, bundled duel footprints, and explosion filtering were removed. Configure duel ownership, explosion behavior, and block permissions in WorldGuard; MaceGuard no longer needs a WarzoneDuels hook.

## Commands and permissions

- `/maceguard here` — final effective custom behavior and actual applicable WorldGuard regions/priorities
- `/maceguard status <region>` — profile, mode, arming, recovery state
- `/maceguard capture|validate|plan|arm|disarm <region>`
- `/maceguard reset <region> <token>`
- `/maceguard recover <region>`
- `/maceguard temporary`
- `/maceguard reload` — refused during capture/restore
- `/maceguard filler|restore <region> <on|off>`
- `/maceguard schedule <region> <on|off>`

Permissions are `maceguard.admin`, `maceguard.reset`, and `maceguard.reload` (operator by default).

Warzone commands:

- `/warzone info|items|next`
- `/warzone skip|force|set <rotation-id>|extend <duration>`
- `/warzone reload|validate|debug`
- aliases: `/warzonerotator`, `/wzr`

The existing `warzonerotator.command.*`, `warzonerotator.admin`, and `warzonerotator.bypass` nodes are unchanged. `info` and `items` separate disabled items, item cooldowns, effect-only abilities, and cobweb status. `debug` includes region resolution, active/next IDs, deadline, cooldown count, MaceGuard temporary-cobweb count, scheduler, PlaceholderAPI, and sender membership.

## Legacy migration

MaceGuard does not rewrite an existing legacy config. On detection it creates `migration/legacy-config.yml.bak` and `migration/legacy-migration-report.txt` containing review-only flag suggestions. It never creates/changes WorldGuard regions, imports coordinates, applies flags, enables timers, selects modes, arms regions, or trusts old snapshots/sparse baselines.

For the integrated module, when `plugins/MaceGuard/warzone.yml` is absent, MaceGuard checks `plugins/WarzoneRotator/config.yml`. A valid version-1/2 file is converted to version 3, with every legacy `disabled-items` entry becoming a `DISABLED` restriction and a matching global policy. The old messages and rotation state are imported only when their new targets do not exist. Legacy cobweb state is deliberately not imported because MaceGuard's persistent temporary-block service is the sole owner. Nothing in `plugins/WarzoneRotator` is deleted or rewritten, and all migration writes are idempotent.

Use the [production migration checklist](docs/PRODUCTION_MIGRATION.md) before deploying.

## Troubleshooting and intentionally unsupported cases

- Wrong-type custom flag: remove/rename the conflicting plugin flag registration; that MaceGuard feature stays disabled.
- Missing snapshot: capture a fresh one; never copy an old snapshot into `snapshots-v1`.
- Invalid geometry: polygonal/global regions are rejected rather than resetting a bounding box.
- Unloaded chunk: load it operationally and rerun capture/preflight; MaceGuard does not force chunks.
- Unsupported tile entity: remove it from reset scope or wait for explicit codec support; capture fails safely.
- Sparse mode journals direct player place/break changes. Non-player mutation sources must be controlled with WorldGuard if they need deterministic restoration.
- Interrupted journal: automatic continuation is intentionally unsupported.
