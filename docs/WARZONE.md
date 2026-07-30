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

Other complete-item decisions begin a cooldown only after their corresponding uncancelled damage, shot, placement, or right-click event. The UUID/target cooldown service is authoritative, periodically removes expired records, and retains unexpired records through logout/reconnect. Because restrictions are region-scoped, MaceGuard does not apply Bukkit's global material cooldown overlay where it would incorrectly block use outside the warzone.

Lunge uses a 450 ms correlation window after an actual Lunge-enchanted spear swing/attack. Only a bounded horizontal velocity delta aligned with the horizontal view direction is treated as Lunge; backward knockback, vertical/perpendicular velocity, explosion-sized impulses, expired attempts, and swings with another item are ignored. `SPEAR_LUNGE: DISABLED` cancels only that velocity. `SPEAR_LUNGE: COOLDOWN` allows the first correlated movement, starts the cooldown at the uncancelled velocity monitor, and cancels only later correlated Lunge movement. Ordinary spear damage, throwing, holding, slot changes, and hand swaps are never cancelled by the effect-only target.

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
