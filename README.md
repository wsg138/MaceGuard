# MaceGuard

MaceGuard adds a small set of opt-in behaviors to WorldGuard regions. WorldGuard is the sole authority for region geometry, membership, ownership, priorities, parents, build/break permission, pistons, liquids, explosions, interactions, and containers. MaceGuard contains no region coordinates and never acts as a general protection plugin.

## Quickstart

```yaml
# config.yml
config-version: 7
reset-profiles:
  war-pit:
    mode: FULL_SNAPSHOT
    interval-minutes: 0      # start with 0, enable timer after testing
    max-coordinates: 500000
    max-total-changes: 100000
    max-air-changes: 25000
    excluded-region-ids: []
```

1. Define a cuboid region in WorldGuard: `/rg define war-pit`
2. Tag it: `/rg flag war-pit maceguard-reset-profile war-pit`
3. Load all chunks in the region, then: `/maceguard capture war-pit`
4. Verify: `/maceguard validate war-pit`
5. Review before arming: `/maceguard plan war-pit`
6. Arm: `/maceguard arm war-pit`
7. To manually reset: `/maceguard plan war-pit` then `/maceguard reset war-pit <token>`
8. Check all armed regions: `/maceguard status-all`

Set `interval-minutes` only after at least one observed successful manual cycle. A manual reset via token re-arms the region with a fresh schedule — the interval clock restarts from that moment.

## Requirements and installation

- Java 21
- Paper 1.21.11-compatible server
- WorldGuard 7.0.17 (and its required WorldEdit version)
- Optional: WarzoneRotator with `isCobwebPlacementAllowed(Player, Location)`

Install WorldEdit and WorldGuard first, then MaceGuard. `plugin.yml` declares WorldGuard as a hard dependency. MaceGuard registers its flags during `onLoad`, before WorldGuard locks the registry. A same-name flag with the expected type is reused; a wrong-type conflict disables that feature and logs a severe error.

## WorldGuard flags

| Flag | Type | Effect |
| --- | --- | --- |
| `maceguard-mace-durability` | state | `ALLOW` enables the configured armor durability cap at the victim. Missing/`DENY` does nothing. |
| `maceguard-cobwebs` | state | `ALLOW` enables tracked cobweb TTL behavior only after WorldGuard allows the placement and WarzoneRotator allows it. |
| `maceguard-reset-profile` | string | Directly associates an exact WorldGuard cuboid region with a named config profile. |

Examples:

```text
/rg flag warzone maceguard-mace-durability allow
/rg flag warzone maceguard-cobwebs allow
/rg flag war-pit maceguard-reset-profile war-pit
```

Do not set `build allow` merely to enable MaceGuard behavior. Configure WorldGuard membership, passthrough, parents, priorities, and protection flags normally.

## Safe configuration

There are no coordinates in `config.yml`. Numeric limits, schedules, batching, storage policy, and global End restrictions remain MaceGuard settings.

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

Snapshot storage uses gzipped JSON. Since the full snapshot includes every block coordinate (including air), repeated "minecraft:air" entries compress to negligible size. A region's snapshot file is roughly proportional to its non-air block count, not its total volume.

## Full snapshot reset lifecycle

1. Define and review the region in WorldGuard. Only exact cuboid regions are supported.
2. Add a valid config profile with conservative total-change and air-change limits.
3. Set `maceguard-reset-profile` directly on that region.
4. Run `/maceguard capture <region>`. Every required chunk must already be loaded; MaceGuard never force-loads it.
5. Run `/maceguard validate <region>`.
6. Run `/maceguard plan <region>` and review all counts.
7. Run `/maceguard arm <region>`.
8. Run `plan` again and execute `/maceguard reset <region> <token>` with the current one-use token.

An interval greater than zero enables automatic preflight after arming. Automatic resets never use confirmation-token overrides and are disarmed if resolution, validation, or a safety threshold fails.

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

## Cobweb behavior

MaceGuard only tracks a successful, WorldGuard-permitted cobweb placement when the effective custom flag is `ALLOW`, WarzoneRotator explicitly permits it, the replaced material is configured, and persistence is healthy. Expiry restores the recorded original block only if the block still exactly matches the tracked cobweb and remains in an enabled location. Missing integrations fail closed for custom TTL behavior without cancelling normal construction. Liquids are left to WorldGuard; MaceGuard does not drain them.

## Mace durability and End behavior

Mace durability uses Paper's damage source plus a tick-scoped pre-attack context. It changes only equipped armor item-damage events and clears state on expiry, quit, death, reload, and disable. Health, damage calculation, knockback, cooldowns, enchantments, attributes, and weapon switching remain vanilla.

End Eye/portal scheduling and main-End-island mace/spear restrictions remain global configuration. MaceGuard no longer controls End explosions or ordinary block interactions; use WorldGuard for those.

WarzoneDuels-specific cuboids, bundled duel footprints, and explosion filtering were removed. Configure duel ownership, explosion behavior, and block permissions in WorldGuard; MaceGuard no longer needs a WarzoneDuels hook.

## Commands and permissions

- `/maceguard here` — final effective custom behavior and actual applicable WorldGuard regions/priorities
- `/maceguard status <region>` — profile, mode, arming, recovery state for one region
- `/maceguard status-all` — armed/disarmed status for every known region across all loaded worlds
- `/maceguard capture|validate|plan|arm|disarm <region>`
- `/maceguard reset <region> <token>`
- `/maceguard recover <region>`
- `/maceguard temporary`
- `/maceguard reload` — refused during capture/restore; invalidates sparse baseline cache
- `/maceguard endeyes`, `endportal`, `endstatus`

Permissions are `maceguard.admin`, `maceguard.reset`, and `maceguard.reload` (operator by default).

## Legacy migration

MaceGuard does not rewrite an existing legacy config. On detection it creates `migration/legacy-config.yml.bak` and `migration/legacy-migration-report.txt` containing review-only flag suggestions. It never creates/changes WorldGuard regions, imports coordinates, applies flags, enables timers, selects modes, arms regions, or trusts old snapshots/sparse baselines.

Use the [production migration checklist](docs/PRODUCTION_MIGRATION.md) before deploying.

## Troubleshooting and intentionally unsupported cases

- Wrong-type custom flag: remove/rename the conflicting plugin flag registration; that MaceGuard feature stays disabled.
- Missing snapshot: capture a fresh one; never copy an old snapshot into `snapshots-v1`.
- Invalid geometry: polygonal/global regions are rejected rather than resetting a bounding box.
- Unloaded chunk: load it operationally and rerun capture/preflight; MaceGuard does not force chunks.
- Unsupported tile entity: remove it from reset scope or wait for explicit codec support; capture fails safely.
- Sparse mode journals direct player place/break changes. Non-player mutation sources must be controlled with WorldGuard if they need deterministic restoration.
- Interrupted journal: automatic continuation is intentionally unsupported.
- Region changed externally: if a WorldGuard region is redefined, the next tick cycle (up to 60s) detects the geometry mismatch and disarms it. There is no real-time event listener — WorldGuard does not expose public region modification events.
