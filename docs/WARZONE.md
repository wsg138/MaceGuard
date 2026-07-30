# Integrated warzone configuration

MaceGuard 4.0.0 contains the WarzoneRotator runtime. Use only the MaceGuard JAR after migration; retain the standalone WarzoneRotator directory until the integrated build has passed live-server testing.

## Files and validation

`plugins/MaceGuard/warzone.yml` is version 3. It is independent of the reset/mace settings in `config.yml`. `warzone-messages.yml` holds MiniMessage templates, and `state/warzone-state.yml` is managed by the plugin.

Both configuration loaders reject duplicate YAML keys, wrong scalar/list/mapping types, unknown paths, malformed material names, undeclared targets, unsupported modes, non-positive durations, duplicate warnings, policy bypasses, and cooldowns above their global maximum.

`/warzone reload` validates the proposed warzone config, messages, and a target region when its world is loaded. On failure, the active config, rotation, timestamps, cooldown service, listeners, scheduler, and placeholders stay unchanged. `/maceguard reload` performs the same validation together with `config.yml` and remains refused while a capture/restore is active.

## Restriction targets and policies

Every target used by a rotation must be declared under `restriction-targets`.

```yaml
restriction-targets:
  ENDER_PEARL:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
  SPEAR:
    can-disable: true
    can-cooldown: false
  SPEAR_LUNGE:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
```

Normal targets must be exact Bukkit material enum names such as `MACE`, `ENDER_PEARL`, or `DIAMOND_SWORD`.

- `SPEAR` matches every material whose name ends in `_SPEAR`.
- `SPEAR_LUNGE` matches only the Lunge effect and never the spear item.
- `DISABLED` needs `can-disable: true`.
- `COOLDOWN` needs `can-cooldown: true`, a positive `cooldown`, and a positive `maximum-cooldown` policy.
- `COOLDOWN` is accepted only for targets with a reliable success event: supported projectiles, direct-attack weapons, `SPEAR`, and `SPEAR_LUNGE`.
- Arbitrary unsupported materials remain valid `DISABLED` targets but are rejected in `COOLDOWN` mode rather than starting cooldowns from ambiguous right-clicks.
- Omitting a target from a rotation means unrestricted.

Invalid requests are rejected. MaceGuard does not clamp a cooldown or silently change its mode.

## Rotation example

```yaml
config-version: 3
enabled: true
region:
  world: world
  id: warzone
rotation:
  warning-times: [10m, 5m, 1m, 30s, 10s, 5s, 4s, 3s, 2s, 1s]
messages:
  blocked-message-cooldown: 2s
  warning-audience: global
  transition-audience: global
cobwebs:
  clear-after: 60s
  clear-on-meta-change: true
  clear-on-disable: true
restriction-targets:
  MACE:
    can-disable: true
    can-cooldown: false
  ENDER_PEARL:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
  SPEAR_LUNGE:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
rotations:
  limited-mobility:
    display-name: "<gold>Limited Mobility"
    description: "<gray>Pearls and Lunge use longer cooldowns."
    duration: 90m
    cobwebs-allowed: false
    restrictions:
      ENDER_PEARL:
        mode: COOLDOWN
        cooldown: 15s
      SPEAR_LUNGE:
        mode: COOLDOWN
        cooldown: 10s
    start-message: "<gold>The warzone meta changed to <white>Limited Mobility<gold>."
```

Rotation IDs define the configured order. State restoration advances through every deadline elapsed while the server was offline and retains already-emitted warning thresholds.

## Cooldown and Lunge behavior

The first allowed pearl/projectile use is decided once in `PlayerLaunchProjectileEvent`. A rejected launch is cancelled with item consumption disabled. An allowed cooldown action is committed only after an uncancelled `ProjectileLaunchEvent` monitor confirms that no later plugin cancelled the actual launch; that second event does not repeat the restriction decision.

Other supported complete-item cooldowns begin only after their corresponding uncancelled projectile or applied direct-damage event. A non-cancelled `PlayerInteractEvent` is never treated as proof that an item was used. The UUID/target cooldown service is authoritative, periodically removes expired records, and retains unexpired records through logout/reconnect.

Material targets such as `ENDER_PEARL` and `WIND_CHARGE` receive a Bukkit cooldown overlay while the player is inside the configured warzone. The overlay is removed when the player leaves, restored when they enter or reconnect inside, and never replaces the authoritative UUID cooldown. MaceGuard does not shorten a stronger cooldown installed by vanilla or another plugin and restores a pre-existing shorter cooldown when removing its own overlay. `SPEAR_LUNGE` is effect-only and never receives a material overlay.

Paper/Leaf 1.21.11 does not expose a dedicated cancellable Lunge event. For this target version, MaceGuard uses a 250 ms compatibility gate armed only by a real `PrePlayerAttackEntityEvent` with a Lunge-enchanted spear. Generic arm swings do not arm it. Only one bounded horizontal velocity delta aligned with the horizontal view direction is treated as the vanilla Lunge propulsion; backward/perpendicular movement, unrelated velocity, oversized impulses, and expired attempts are ignored. Vertical fall velocity is ignored rather than used to reject a valid horizontal Lunge.

`SPEAR_LUNGE: DISABLED` cancels only the correlated Lunge velocity. `SPEAR_LUNGE: COOLDOWN` allows the first correlated movement, starts the cooldown only if that velocity event remains uncancelled, and cancels only later correlated Lunge movement. Ordinary spear damage, throwing, holding, slot changes, and hand swaps are never cancelled by the effect-only target. Direct boundary attacks preserve both attacker and target region membership in the short-lived correlation record.

## Region resolution and cobweb cleanup

The configured WorldGuard world and region are re-resolved by ID every five seconds. This recovers from a late-loaded world or region manager and from a region that is deleted, recreated, or replaced. While the configured world is loaded but the region cannot be resolved, integrated restrictions fail closed in that world. Destructive cobweb selection always requires an exactly resolved region and is never broadened to the entire world. `/warzone debug` reports the current resolution status.

A forced cobweb clear restores matching entries immediately when their chunks are loaded. Entries in unloaded chunks are persisted with `pendingClear: true` and restored when those chunks load, without force-loading them. If the expected temporary block was already changed, MaceGuard removes the stale record without overwriting the newer block. The pending state survives restart, and legacy records without that field load as not pending.

## Commands, permissions, and placeholders

Commands and aliases remain `/warzone`, `/warzonerotator`, and `/wzr`. Subcommands are `info`, `items`, `next`, `skip`, `force`, `set`, `extend`, `reload`, `validate`, and `debug`.

Permissions remain:

- `warzonerotator.admin`
- `warzonerotator.command.info`
- `warzonerotator.command.items`
- `warzonerotator.command.next`
- `warzonerotator.command.skip`
- `warzonerotator.command.force`
- `warzonerotator.command.set`
- `warzonerotator.command.extend`
- `warzonerotator.command.reload`
- `warzonerotator.command.validate`
- `warzonerotator.command.debug`
- `warzonerotator.bypass`

Existing `%warzone_*%` identifiers are retained. New identifiers are `%warzone_cooldown_items%`, `%warzone_cooldown_items_count%`, and `%warzone_restrictions%`. The disabled placeholders include only complete `DISABLED` targets; effect-only restrictions are represented by the combined restrictions placeholder.

## Migration

Migration runs only into missing targets:

1. Convert a valid `plugins/WarzoneRotator/config.yml` to `plugins/MaceGuard/warzone.yml`.
2. Convert legacy `disabled-items` entries to `mode: DISABLED`.
3. Copy a valid old messages file to `warzone-messages.yml`.
4. Import active/next IDs, start/end timestamps, and emitted warnings into `state/warzone-state.yml`.
5. Leave the entire old directory unchanged.

Legacy temporary-cobweb entries are not imported. If conversion is unsafe, MaceGuard logs the exact error, leaves the old file untouched, and keeps only the integrated warzone module inactive.
