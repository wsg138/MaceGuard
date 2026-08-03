# MaceGuard

MaceGuard 5 provides WorldGuard-scoped weekly warzone modifiers, strict temporary-block and block-policy handling, broad explosion blocking, and fail-closed region restoration for Paper/Leaf 1.21.11. WorldGuard remains the sole owner of region geometry, membership, parents, priorities, and ordinary protection.

## Requirements

- Java 21
- Paper or Leaf 1.21.11-compatible server
- WorldGuard 7.0.17 and its matching WorldEdit dependency
- Optional PlaceholderAPI 2.11.6+

## Weekly warzone modifiers

`warzone.yml` schema 5 selects one to three compatible modifiers at the configured weekly calendar boundary. The default transition remains Sunday at 04:00 in `America/Indiana/Indianapolis`. Selection, activation, the weekly boundary, effective transition, warning state, and sequence persist atomically. Restarting does not reroll a valid current week.

Modifier count is selected from `rotation.selection.count-weights` (defaults: one `35`, two `45`, three `20`). Enabled modifiers are then selected without replacement by their relative `weight`, while conflict groups and special rules are enforced. `enabled: false` removes one outcome from both random and manual selection without deleting its configuration.

Bundled outcomes and default weights:

| Modifier | Weight | Effect |
| --- | ---: | --- |
| `cobwebs` | 10 | Enables temporary tracked cobweb placement. |
| `no-lunge` | 8 | Disables only `SPEAR_LUNGE`. |
| `mace-disabled` | 4 | Disables Maces. |
| `mace-cooldown` | 8 | Ten-second cooldown after a successful Mace hit. |
| `ender-pearl-disabled` | 3 | Disables Ender Pearls. |
| `ender-pearl-cooldown-5` | 9 | Five-second cooldown after a successful Pearl launch. |
| `ender-pearl-cooldown-10` | 6 | Ten-second cooldown after a successful Pearl launch. |
| `wind-charge-disabled` | 3 | Disables Wind Charges. |
| `wind-charge-cooldown-5` | 9 | Five-second cooldown after a successful Wind Charge launch. |
| `wind-charge-cooldown-10` | 6 | Ten-second cooldown after a successful Wind Charge launch. |
| `elytra-no-rockets` | 1 | Allows gliding while blocking firework boosts. |

Only one Mace, Pearl, or Wind Charge mode may be active at once. Leaving every outcome for one item disabled leaves that item unrestricted every week.

To disable one outcome:

```yaml
modifiers:
  ender-pearl-disabled:
    enabled: false
```

To disable an entire item category, set every outcome for that item to `enabled: false`. Do not delete sections.

Elytra has an explicit default 8% weekly inclusion chance. When selected, a second deterministic rule has a 90% chance to exclude both Mace restriction modifiers, leaving Maces fully unrestricted. Both values are configurable from 0 through 100; `enabled: false` overrides the inclusion chance.

Example valid weeks include:

```text
Cobwebs + 5s Pearl Cooldown
No Lunge + 10s Wind Charge Cooldown
Mace Cooldown + No Ender Pearls
Elytra, No Rockets + Cobwebs
Elytra, No Rockets + 5s Pearl Cooldown
```

The effective scope is the configured outer `warzone` region minus every configured exclusion. The defaults remain `spawn` and `market`. Missing required geometry makes gameplay scope inactive and never broadens enforcement to the whole world.

Fresh installations ship with `enabled: false`. Create and review `warzone`, `spawn`, and `market`, run `/warzone validate`, then enable and reload the module.

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

`skip`, `force`, and `set` preserve the current effective transition. Persisted selections that become disabled or invalid are rerolled while retaining their established weekly boundary.

Status placeholders:

```text
%warzone_mace_status%
%warzone_ender_pearl_status%
%warzone_wind_charge_status%
%warzone_spear_lunge_status%
%warzone_elytra_status%
```

Machine-readable placeholders:

```text
%warzone_mace_disabled%
%warzone_mace_cooldown_seconds%
%warzone_ender_pearl_disabled%
%warzone_ender_pearl_cooldown_seconds%
%warzone_wind_charge_disabled%
%warzone_wind_charge_cooldown_seconds%
%warzone_spear_lunge_disabled%
%warzone_elytra_gliding_allowed%
%warzone_firework_boost_blocked%
```

Existing `%warzone_*%` placeholders remain supported, and `%warzone_restrictions%` includes Pearl and Wind Charge restrictions.

## Block policies

The WorldGuard string flag `maceguard-block-policy` associates a region with a named policy from `config.yml`. WorldGuard must first allow the action; MaceGuard only adds restrictions and never un-cancels another plugin's event.

The bundled `cobweb-box` policy permits players to place and break cobwebs and ice, use and collect water, confines liquids to the region, blocks infinite-water source creation, and denies unlisted materials. Pistons, dispensers, fluid flow, and non-player block sources are checked explicitly.

## Reset profiles

MaceGuard supports `FULL_SNAPSHOT` for bounded areas and `FILTERED_SNAPSHOT` for a large, vertically limited reset cuboid. Capture, preflight, and restore preserve the existing fail-closed lifecycle, batching, exclusions, journals, checksums, arming, and one-use confirmation tokens. MaceGuard never automatically captures, arms, or enables a reset schedule.

## Explosion behavior

`maceguard-explosives deny` remains intentionally broad. It blocks end crystals, respawn anchors, TNT, TNT minecarts, beds, creepers, and other block or entity explosions at the effective WorldGuard location.

## Migration

Main configuration schema remains version 8; warzone configuration schema is version 5. Schema-4 `warzone.yml` files are backed up and migrated while preserving the explicit module enabled value, world and region IDs, exclusions, weekly schedule, warning times, messages, cobweb settings, restriction policies, compatible modifier text/restrictions, and persisted weekly state. New outcome toggles, weights, count weights, Pearl/Wind definitions, conflict groups, and Elytra rules receive defaults.

The migration never creates or changes WorldGuard regions, enables the module automatically, captures snapshots, arms profiles, or enables reset schedules. The standalone `plugins/WarzoneRotator` directory remains untouched for rollback.

See [warzone configuration](docs/WARZONE.md), [deployment and staging](docs/DEPLOYMENT.md), and [migration behavior](docs/MIGRATION.md).
