# MaceGuard

MaceGuard 6.1 provides WorldGuard-scoped Warzone kits, anchored repeating schedules, persistent manual overrides, configurable combat restrictions, tracked temporary cobwebs, block policies, explosion controls, and fail-safe region resets for Paper/Leaf 1.21.11.

WorldGuard remains the authority for region geometry, membership, inheritance, priorities, and ordinary protection. MaceGuard never broadens an unresolved Warzone to the whole world.

## Requirements

- Java 21
- Paper or Leaf 1.21.11-compatible server
- WorldGuard 7.0.17 with its matching WorldEdit dependency
- PlaceholderAPI 2.11.6+ is optional
- CombatLogX 11.6 is optional; combat-dependent Warzone features disable safely when its public API is unavailable

No NMS, reflection into CombatLogX, copied CombatLogX source, or private-plugin internals are used. MaceGuard compiles against the published `com.github.sirblobman.combatlogx:api:11.6-SNAPSHOT` API (and its BlueSlimeCore API requirement) with `provided` scope. The direct listener boundary is loaded only after the soft dependency is confirmed present and compatible, so unrelated MaceGuard functionality still loads without CombatLogX. No CombatLogX fork is required.

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

CombatLogX remains authoritative for tagging, timers, bypass, logout punishment, ordinary teleport prevention, Ender Pearl retagging, and keeping combat active while gliding. Its direct public tag/re-tag callbacks are reconciled after CombatLogX commits tag state, while untag cleanup occurs after removal. MaceGuard controls dynamic combat Elytra starts, cancels only actual Elytra boosts, and can block an aged Ender Pearl teleport when the acquired latch retained a denied stasis policy. The default stasis threshold is `60s`. `warzonerotator.bypass` bypasses all MaceGuard Rotator restrictions, including stasis; CombatLogX's API bypass prevents acquiring or retaining the latch.

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

PlaceholderAPI support uses the `warzone` identifier. The table below lists every supported placeholder in MaceGuard 6.1.0. Example outputs are illustrative and depend on the live configuration, active selection, schedule, and player location.

Status and restriction placeholders describe the active Warzone gameplay scope/meta; they do not directly expose an individual player's CombatLogX latch. Values that do not apply return an empty string. Boolean outputs are lowercase `true` or `false`.

### Rotation and schedule
| Placeholder | What it returns | Example output |
|---|---|---|
| `%warzone_current_meta%` | Plain-text display name of the current active modifier set. | `Cobwebs + No Lunge` |
| `%warzone_current_modifiers%` | Alias of `%warzone_current_meta%`. | `Cobwebs + No Lunge` |
| `%warzone_current_meta_id%` | Stable `+`-joined IDs of the current active modifiers. | `cobwebs+no-lunge` |
| `%warzone_current_modifier_ids%` | Alias of `%warzone_current_meta_id%`. | `cobwebs+no-lunge` |
| `%warzone_description%` | Plain-text description of the current active set. | `Temporary cobwebs are enabled and Spear Lunge is disabled.` |
| `%warzone_time_left%` | Time remaining until the next effective change in clock format. | `01:23:45` |
| `%warzone_time_left_words%` | Time remaining using compact day/hour/minute/second units. | `1h 23m 45s` |
| `%warzone_time_left_seconds%` | Whole non-negative seconds remaining. | `5025` |
| `%warzone_changes_at%` | Formatted time of the next effective transition. Empty for an indefinite override or when no schedule can change the selection. | `Sun, Aug 9 4:00 AM EDT` |
| `%warzone_next_meta%` | Display name of the next automatic schedule entry. Empty when scheduling is disabled. | `Mace` |
| `%warzone_next_meta_id%` | Stable identifier for the next entry: kit ID, `+`-joined modifier IDs, `random`, or `none`. | `mace` |
| `%warzone_source_type%` | How the current final selection was produced. | `KIT` |
| `%warzone_active_kit%` | Current kit ID when the source type is `KIT`; otherwise empty. | `smp` |
| `%warzone_override_active%` | Whether a manual override currently controls gameplay. | `true` |
| `%warzone_override_mode%` | Current override duration mode; empty when no override exists. | `UNTIL_NEXT_SCHEDULED_CHANGE` |
| `%warzone_override_ends_at%` | Formatted override expiration; empty for no override or an indefinite override. | `Sun, Aug 9 4:00 AM EDT` |
| `%warzone_override_time_left%` | Override time remaining in clock format; empty when it has no fixed expiration. | `42:17` |
| `%warzone_schedule_slot%` | Persisted identity of the current automatic slot: start epoch milliseconds plus cycle index. | `1786262400000:2` |
| `%warzone_schedule_cycle_position%` | Current automatic cycle position, starting at 1. | `3` |
| `%warzone_next_source_type%` | Source type of the next automatic entry. | `SCHEDULED_MODIFIERS` |
| `%warzone_next_name%` | Display name of the next automatic entry. | `Cobwebs + No Lunge` |
| `%warzone_next_changes_at%` | Formatted end of the current automatic slot, even while an override is active. | `Sun, Aug 16 4:00 AM EDT` |

### Active restrictions and scope
| Placeholder | What it returns | Example output |
|---|---|---|
| `%warzone_disabled_items%` | Comma-separated disabled item/ability targets in the active set, or `None`. | `Mace, Spear Lunge` |
| `%warzone_disabled_items_count%` | Number of disabled targets in the active set. | `2` |
| `%warzone_cooldown_items%` | Comma-separated cooldown targets and configured durations, or `None`. | `Ender Pearl — 5s, Wind Charge — 10s` |
| `%warzone_cooldown_items_count%` | Number of cooldown targets in the active set. | `2` |
| `%warzone_restrictions%` | All active restrictions with `disabled` or cooldown wording. | `Mace — disabled, Ender Pearl — 5s cooldown` |
| `%warzone_gameplay_scope_active%` | Whether the configured effective Warzone scope resolved and gameplay enforcement is active. | `true` |
| `%warzone_cobwebs_allowed%` | Whether the Cobwebs effect is active while gameplay scope is active. | `true` |
| `%warzone_cobweb_clear_time%` | Configured lifetime of temporary Warzone cobwebs. | `30s` |
| `%warzone_inside_effective_scope%` | Whether the online player being parsed is currently inside the effective Warzone scope. | `false` |

### Item and ability status
| Placeholder | What it returns | Example output |
|---|---|---|
| `%warzone_mace_status%` | Human-readable Mace state: `Inactive`, `Allowed`, `Disabled`, or a cooldown. | `10s cooldown` |
| `%warzone_mace_disabled%` | Whether Mace is fully disabled by the active scoped selection. | `false` |
| `%warzone_mace_cooldown_seconds%` | Configured Mace cooldown seconds, or `0` when none/inactive. | `10` |
| `%warzone_ender_pearl_status%` | Human-readable Ender Pearl state. | `5s cooldown` |
| `%warzone_ender_pearl_disabled%` | Whether Ender Pearls are fully disabled. | `false` |
| `%warzone_ender_pearl_cooldown_seconds%` | Configured Ender Pearl cooldown seconds, or `0`. | `5` |
| `%warzone_wind_charge_status%` | Human-readable Wind Charge state. | `10s cooldown` |
| `%warzone_wind_charge_disabled%` | Whether Wind Charges are fully disabled. | `false` |
| `%warzone_wind_charge_cooldown_seconds%` | Configured Wind Charge cooldown seconds, or `0`. | `10` |
| `%warzone_spear_status%` | Human-readable general Spear-use state. | `Disabled` |
| `%warzone_spear_disabled%` | Whether general Spear use and correlated damage are disabled. | `true` |
| `%warzone_spear_damage_status%` | Human-readable independent Spear Damage state. | `7s cooldown` |
| `%warzone_spear_damage_cooldown_seconds%` | Configured Spear Damage cooldown seconds, or `0`. | `7` |
| `%warzone_spear_lunge_status%` | Human-readable independent Spear Lunge state. | `Disabled` |
| `%warzone_spear_lunge_disabled%` | Whether Spear Lunge is disabled. | `true` |
| `%warzone_spear_lunge_cooldown_seconds%` | Configured Spear Lunge cooldown seconds, or `0`. | `9` |
| `%warzone_elytra_status%` | Human-readable Elytra effect state for the active scoped meta. | `Gliding allowed; rockets disabled` |
| `%warzone_elytra_gliding_allowed%` | Whether the active scoped meta includes the Elytra gliding effect. | `true` |
| `%warzone_firework_boost_blocked%` | Whether the active scoped meta blocks Elytra firework propulsion. | `true` |

### Indexed active modifiers
| Placeholder | What it returns | Example output |
|---|---|---|
| `%warzone_modifier_1%` | Plain display name of active modifier slot 1; empty when unused. | `No Lunge` |
| `%warzone_modifier_1_id%` | Configured ID of active modifier slot 1. | `no-lunge` |
| `%warzone_modifier_1_description%` | Plain description of active modifier slot 1. | `The Spear Lunge effect is disabled without blocking normal spear use.` |
| `%warzone_modifier_2%` | Plain display name of active modifier slot 2; empty when unused. | `Cobwebs` |
| `%warzone_modifier_2_id%` | Configured ID of active modifier slot 2. | `cobwebs` |
| `%warzone_modifier_2_description%` | Plain description of active modifier slot 2. | `Temporary cobweb placement is available in the effective warzone.` |
| `%warzone_modifier_3%` | Plain display name of active modifier slot 3; empty when unused. | `5s Pearl Cooldown` |
| `%warzone_modifier_3_id%` | Configured ID of active modifier slot 3. | `ender-pearl-cooldown-5` |
| `%warzone_modifier_3_description%` | Plain description of active modifier slot 3. | `Successful Ender Pearls receive a five-second cooldown.` |

Total documented placeholders: **59**.


## Migration

Schema 6 is backed up and migrated to schema 7 without replacing operator-defined kits, modifiers, weights, schedules, messages, restriction targets, conflict groups, or GUI settings. Existing modifiers receive `combat-carryover: false` unless explicitly configured after migration, and `combat.stasis.minimum-age` defaults to `60s`.

Historical schema 5 and schema 4 files continue through the existing validated migration path into schema 7. A failed migration never partially replaces the active file. Incompatible older files are backed up and replaced with a disabled clean schema-7 file. WorldGuard regions, custom flag assignments, reset snapshots, arming state, and reset schedules are never created or enabled by migration.

See [Warzone configuration](docs/WARZONE.md), [deployment and staging](docs/DEPLOYMENT.md), and [migration](docs/MIGRATION.md).
