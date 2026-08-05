# MaceGuard

MaceGuard 6.1 provides WorldGuard-scoped Warzone kits, anchored repeating schedules, persistent manual overrides, configurable combat restrictions, tracked temporary cobwebs, block policies, explosion controls, and fail-safe region resets for Paper/Leaf 1.21.11.

WorldGuard remains the authority for region geometry, membership, inheritance, priorities, and ordinary protection. MaceGuard never broadens an unresolved Warzone to the whole world.

## Requirements

- Java 21
- Paper or Leaf 1.21.11-compatible server
- WorldGuard 7.0.17 with its matching WorldEdit dependency
- PlaceholderAPI 2.11.6+ is optional
- CombatLogX 11.6 is optional; combat-dependent Warzone features disable safely when its public API is unavailable

No NMS or private-plugin internals are used. The optional CombatLogX bridge resolves only its documented public API so MaceGuard can still load without CombatLogX.

## Warzone schema 7

`plugins/MaceGuard/warzone.yml` now combines the existing weighted modifier system with:

- named, ordered kits;
- an anchored repeating `DAYS`, `WEEKS`, or `MONTHS` cycle;
- `RANDOM`, `KIT`, exact `MODIFIERS`, and explicit `NONE` schedule entries;
- one-hour, next-boundary, and indefinite manual overrides;
- inventory menus for status, kits, modifiers, schedules, confirmation, and duration;
- versioned automatic-slot and manual-override persistence.

Gameplay always consumes one validated final active set. A manual override takes precedence without pausing or shifting the automatic cycle. Boundaries that occur under an override update the background slot silently. Clearing or expiring the override applies the automatic slot currently due.

A random schedule result is persisted against its exact slot identity, so restarting in the same slot does not reroll it. If the server was offline across several boundaries, startup calculates the current slot directly instead of replaying missed announcements.

## Schedule example

```yaml
rotation:
  schedule:
    enabled: true
    timezone: America/Indiana/Indianapolis
    anchor-date: "2026-08-09"
    time: "04:00"
    cadence:
      every: 1
      unit: WEEKS
    cycle:
      - type: KIT
        kit: smp
      - type: RANDOM
      - type: KIT
        kit: mace
      - type: MODIFIERS
        modifiers: [cobwebs, no-lunge]
```

Month cadence uses calendar arithmetic. An anchor on the 29th, 30th, or 31st clamps to the target month’s final valid day. Timezone transitions use Java’s deterministic `ZoneId` behavior.

## Kits

```yaml
kits:
  smp:
    enabled: true
    display-name: "<green>SMP"
    description: "<gray>General-purpose mobility and utility rules."
    icon: GRASS_BLOCK
    modifiers:
      - cobwebs
      - ender-pearl-cooldown-5
      - wind-charge-cooldown-5
```

Kit modifier order is preserved in menus and placeholders. Enabled kits must reference only known, enabled, unique, mutually compatible modifiers. Activating a kit replaces the full active selection.

Adding or removing a modifier from a kit-derived selection does not alter the kit. It detaches the active selection into a `CUSTOM_OVERRIDE`. Count limits may be bypassed only by `warzonerotator.manage.custom-combinations`; unknown IDs, disabled modifiers, target capabilities, conflict groups, and contradictory restrictions are never bypassed.

## Spear controls

The built-in spear controls are independent:

- `spear-disabled` targets `SPEAR` and blocks spear launches and both melee and correlated thrown-spear damage;
- `spear-damage-cooldown-10` targets `SPEAR_DAMAGE` and begins only after confirmed positive direct damage;
- `no-lunge` disables only the correlated Lunge velocity;
- `lunge-cooldown-10` begins only after an accepted Lunge velocity event.

A spear damage cooldown is not started merely by launching a spear; it starts only after accepted positive melee or correlated thrown-spear damage. A Lunge restriction does not block ordinary spear use. All three target policies and cooldown durations remain configurable.


## Warzone combat integration

WorldGuard custom flags define where a CombatLogX-tagged player can acquire transient Warzone combat scope:

```text
warzonerotator-combat-zone: allow
warzonerotator-stasis: deny
```

The latch is evaluated from the player's own effective WorldGuard flags, respects region priorities and inheritance, survives leaving the region only until CombatLogX ends combat, and is never persisted. Existing modifiers opt into outside-region enforcement individually with `combat-carryover: true`; Mace, Ender Pearl, Wind Charge, Spear, Spear damage, Spear Lunge, and the Elytra allowance are eligible. Building, crystal, anchor, cobweb, reset, and other world-mutation policies are rejected for carryover.

CombatLogX remains authoritative for tagging, timers, bypass, logout punishment, ordinary teleport prevention, Ender Pearl retagging, and keeping combat active while gliding. MaceGuard controls dynamic combat Elytra starts, cancels only actual Elytra boosts, and can block an aged Ender Pearl teleport when the acquired latch retained a denied stasis policy. The default stasis threshold is `60s`. `warzonerotator.bypass` bypasses all MaceGuard Rotator restrictions, including stasis; CombatLogX's API bypass prevents acquiring or retaining the latch.

## Commands

Player-facing commands:

```text
/warzone
/warzone info
/warzone modifiers
/warzone modifier list
/warzone kit
/warzone kits
/warzone items
/warzone next
/warzone schedule
/warzone menu
/warzone help
```

Administrative commands:

```text
/warzone modifier set [modifier-id]
/warzone modifier remove [modifier-id]
/warzone modifier clear
/warzone kit set [kit-id]
/warzone kit remove
/warzone kit list
/warzone random
/warzone override clear
/warzone override status
/warzone schedule enable
/warzone schedule disable
/warzone schedule preview
/warzone schedule advance
/warzone reload
/warzone validate
/warzone debug
```

A player who omits a required kit or modifier ID is sent to the relevant GUI. Console use requires an explicit ID and duration:

```text
/warzone kit set <kit> <1h|next|manual>
/warzone modifier set <modifier> <1h|next|manual>
/warzone modifier remove <modifier> <1h|next|manual>
/warzone random <1h|next|manual>
```

Compatibility aliases remain: `/warzone set`, `/warzone force`, `/warzone skip`, `/warzone extend`, and the command aliases `/warzonerotator` and `/wzr`.

## GUI safety

Managed inventories use dedicated holders and short-lived per-player sessions, not inventory titles. Managed clicks, shift insertion, number-key swaps, drag, double-click collection, and offhand swaps are cancelled. Closing before final confirmation, quitting, reloading, disabling the plugin, completing an operation, timing out, or presenting a stale session clears the pending action.

Every mutating GUI shows current and proposed source/modifiers, additions, removals, and kit detachment before an explicit confirmation. Duration is selected separately.

## Effective scope and safety

The default effective scope is the configured `warzone` region minus `spawn` and `market`. The world, outer region, and every required exclusion must resolve before restrictions, positive effects, cooldown overlays, Warzone cobweb behavior, or Warzone-only announcements apply.

Existing transition safety remains centralized: modifier additions/removals are calculated once, start/end messages are sent once, stale item cooldowns and transient Lunge/projectile state are cleared, cobweb clear-on-meta-change remains enforced, state is persisted in order, and suppressed automatic transitions are not publicly announced.

## Placeholders

New selection and schedule values:

```text
%warzone_source_type%
%warzone_active_kit%
%warzone_override_active%
%warzone_override_mode%
%warzone_override_ends_at%
%warzone_override_time_left%
%warzone_schedule_slot%
%warzone_schedule_cycle_position%
%warzone_next_source_type%
%warzone_next_name%
%warzone_next_changes_at%
```

Spear status values:

```text
%warzone_spear_status%
%warzone_spear_disabled%
%warzone_spear_damage_status%
%warzone_spear_damage_cooldown_seconds%
%warzone_spear_lunge_status%
%warzone_spear_lunge_disabled%
%warzone_spear_lunge_cooldown_seconds%
```

All existing placeholders remain supported. Indexed modifier placeholders continue to follow the final active order:

```text
%warzone_modifier_1% ... %warzone_modifier_3%
%warzone_modifier_1_id% ... %warzone_modifier_3_id%
%warzone_modifier_1_description% ... %warzone_modifier_3_description%
```

Fields that do not apply return an empty string. Boolean values are stable lowercase plain text.

## Migration

Schema 6 is backed up and migrated to schema 7 without replacing operator-defined kits, modifiers, weights, schedules, messages, restriction targets, conflict groups, or GUI settings. Existing modifiers receive `combat-carryover: false` unless explicitly configured after migration, and `combat.stasis.minimum-age` defaults to `60s`.

Historical schema 5 and schema 4 files continue through the existing validated migration path into schema 7. A failed migration never partially replaces the active file. Incompatible older files are backed up and replaced with a disabled clean schema-7 file. WorldGuard regions, custom flag assignments, reset snapshots, arming state, and reset schedules are never created or enabled by migration.

See [Warzone configuration](docs/WARZONE.md), [deployment and staging](docs/DEPLOYMENT.md), and [migration](docs/MIGRATION.md).
