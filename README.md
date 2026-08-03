# MaceGuard

MaceGuard 5 provides WorldGuard-scoped weekly warzone modifiers, strict temporary-block and block-policy handling, broad explosion blocking, and fail-closed region restoration for Paper/Leaf 1.21.11. WorldGuard remains the sole owner of region geometry, membership, parents, priorities, and ordinary protection.

## Requirements

- Java 21
- Paper or Leaf 1.21.11-compatible server
- WorldGuard 7.0.17 and its matching WorldEdit dependency
- Optional PlaceholderAPI 2.11.6+

## Weekly warzone modifiers

`warzone.yml` version 4 selects one to three compatible modifiers at the configured weekly calendar boundary. The default transition is Sunday at 04:00 in `America/Indiana/Indianapolis`. The selected modifier IDs, activation time, weekly boundary, effective transition, warning state, and sequence are persisted atomically. Restarting does not reroll the current week, and an offline server advances to the current calendar week when it returns.

Bundled modifiers are:

- `cobwebs`: temporary tracked cobweb placement in the effective warzone.
- `no-lunge`: disables only the correlated `SPEAR_LUNGE` effect.
- `mace-disabled`: disables mace use.
- `mace-cooldown`: starts a cooldown only after an uncancelled applied mace attack.
- `elytra-no-rockets`: permits elytra gliding while blocking actual firework boosts.

The effective scope is the configured outer `warzone` region minus every configured exclusion. The default exclusions are `spawn` and `market`. An unresolved outer region or exclusion never broadens restrictions to the world.

Commands:

```text
/warzone info
/warzone modifiers
/warzone items
/warzone next
/warzone skip
/warzone force
/warzone set <modifier> [modifier...]
/warzone extend <duration>
/warzone reload
/warzone validate
/warzone debug
```

`skip`, `force`, and `set` change the active state without moving the effective transition time. `extend` delays the next effective transition once; after that transition, later changes return to the configured weekly calendar schedule. `/warzone next` reports the transition time but does not reveal an unselected future random combination.

## Block policies

The WorldGuard string flag `maceguard-block-policy` associates a region with a named policy from `config.yml`. WorldGuard must first allow the action; MaceGuard only adds restrictions and never un-cancels another plugin's event.

The bundled `cobweb-box` policy permits players to place and break cobwebs and ice, use and collect water, confines liquids to the region, blocks infinite-water source creation, and denies unlisted materials. Pistons, dispensers, fluid flow, and non-player block sources are checked explicitly. Missing or invalid named policies fail closed.

The block policy decides whether cobweb is an allowed material. The separate `maceguard-cobwebs allow` flag explicitly enables temporary tracked cobweb behavior and its TTL. Both are required in a policy-controlled cobweb box.

## Reset profiles

MaceGuard supports:

- `FULL_SNAPSHOT` for bounded areas such as `war-pit` and `cobweb-box`.
- `FILTERED_SNAPSHOT` for a large, vertically limited `warzone-reset` cuboid. Only explicitly configured fragile block data is persisted and restored.

Filtered restoration may replace only configured air variants, liquids, or fragile materials. A normal solid block at a captured coordinate is skipped and reported; it is never overwritten. Excluded regions are applied during capture, preflight, and restore. Capture and restore run in bounded main-thread batches, do not force-load chunks, and share one destructive-operation lock.

Every production reset uses the existing lifecycle:

```text
/maceguard capture <region>
/maceguard validate <region>
/maceguard plan <region>
/maceguard arm <region>
/maceguard reset <region> <one-use-plan-token>
/maceguard schedule <region> <on|off>
```

Snapshot checksums, exact geometry, exclusions, arming records, confirmation tokens, restore journals, atomic persistence, and interrupted-operation lockout remain authoritative. MaceGuard never automatically captures, arms, or enables a reset schedule.

## Explosion behavior

`maceguard-explosives deny` remains intentionally broad. It blocks end crystals, respawn anchors, TNT, TNT minecarts, beds, creepers, and other block or entity explosions at the effective WorldGuard location.

## Migration

Main configuration schema is version 8; warzone configuration schema is version 4. Existing older files are backed up and receive a review report. The plugin does not silently reinterpret old short sequential rotations as weekly random modifiers, create WorldGuard regions, capture snapshots, arm profiles, or enable schedules. The standalone `plugins/WarzoneRotator` directory is left intact for rollback.

See [deployment and staging](docs/DEPLOYMENT.md) and [migration behavior](docs/MIGRATION.md).
