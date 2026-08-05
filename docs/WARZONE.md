# Integrated Warzone schema 7

MaceGuard 6.1 reads `plugins/MaceGuard/warzone.yml`. Fresh installations remain disabled until the exact WorldGuard scope passes `/warzone validate`.

## Final selection model

The runtime publishes one final validated active set with one source:

```text
RANDOM
KIT
SCHEDULED_MODIFIERS
CUSTOM_OVERRIDE
NONE
```

The automatic slot and optional manual override are persisted separately. A manual override changes gameplay but not the schedule’s anchor, slot index, or phase. Automatic boundaries under an override are persisted without a public transition. `/warzone info` and `/warzone debug` expose both the final selection and the suppressed automatic slot.

## Kits

A kit contains an ID, enabled state, display name, description, Bukkit material icon, and ordered modifier IDs.

```yaml
kits:
  mace:
    enabled: true
    display-name: "<gold>Mace"
    description: "<gray>Mace-focused combat with spear lunge disabled."
    icon: MACE
    modifiers:
      - mace-cooldown
      - no-lunge
```

Validation rejects an enabled kit when:

- its ID is invalid;
- its icon is not a valid Bukkit material;
- it references an unknown or disabled modifier;
- a modifier appears more than once;
- its modifiers conflict by group or target;
- a logically contradictory conditional rule is produced.

Normal kit activation selects exactly one kit and replaces the complete active selection. A schedule entry cannot combine a kit with additional modifiers.

An administrator may detach a kit into a custom selection by adding or removing modifiers. The confirmation GUI names the kit and displays exact before/after sets. The kit definition remains unchanged.

## Anchored repeating schedule

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
        modifiers:
          - cobwebs
          - no-lunge
      - type: NONE
```

Cadence units are `DAYS`, `WEEKS`, and `MONTHS`. `every` must be positive. The schedule is calculated in the configured IANA timezone using calendar arithmetic:

- daily and weekly entries preserve local transition time across DST;
- a nonexistent local time moves forward according to `ZoneId` rules;
- an overlap uses Java’s earlier offset deterministically;
- a monthly anchor on day 29, 30, or 31 clamps to the last valid day in shorter months;
- later months derive from the original anchor, not from a previously clamped date.

The cycle repeats indefinitely. Startup computes the slot due at the current instant directly, even after several missed boundaries.

### Entry behavior

- `RANDOM` uses the existing weighted selector. Its result is saved for the exact slot and reused after restart.
- `KIT` composes the named enabled kit in configured order.
- `MODIFIERS` composes the exact listed collection in stable order.
- `NONE` composes an empty active set.

`/warzone schedule advance` persists a cycle phase offset and applies the next cycle entry to the current calendar slot. The new phase continues at future boundaries. Schedule enable/disable is persisted in state; disabling freezes the current automatic selection while an override, if present, remains independent.

## Manual override durations

Every manual kit, random, modifier-add, modifier-remove, and modifier-clear operation produces a complete proposed active set before duration selection.

- `ONE_HOUR`: expires exactly one hour after confirmation.
- `UNTIL_NEXT_SCHEDULED_CHANGE`: stores the exact next automatic boundary at confirmation. A later config reload does not change that expiration.
- `UNTIL_CLEARED`: has no expiration and survives restart/reload.

On expiration or `/warzone override clear`, the automatic slot currently due is applied. A random automatic slot is not rerolled merely because an override ends.

## Weighted random selection

The existing schema-5 selector remains the source for `RANDOM` entries:

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
```

Random selection enforces enabled states, positive weights, count weights, conflict groups, target conflicts, and the configured Elytra probability branches. It remains bounded by the existing combination limits.

Exact scheduled sets, kits, and authorized custom combinations may exist outside random minimum/maximum counts. They still reject unknown or disabled IDs, duplicate IDs, target capability violations, conflicts, and contradictory effects. Duplicate custom input is removed while preserving first occurrence order.

## Restriction targets and spear behavior

```yaml
restriction-targets:
  SPEAR:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
  SPEAR_DAMAGE:
    can-disable: false
    can-cooldown: true
    maximum-cooldown: 60s
  SPEAR_LUNGE:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
```

`SPEAR` is the whole-item target. `DISABLED` blocks both projectile launch and direct damage. A whole-item cooldown uses the existing success capabilities for projectile and direct attack actions.

`SPEAR_DAMAGE` is a damage-success target. Its cooldown starts after uncancelled positive melee or correlated thrown-spear damage and never from merely throwing a spear.

`SPEAR_LUNGE` is effect-only. Its disabled mode cancels only a correlated Lunge velocity. Its cooldown begins only after that velocity remains accepted at the final event stage. The 1.21.11 compatibility detector remains a narrow `PrePlayerAttackEntityEvent` to immediate aligned `PlayerVelocityEvent` correlation; no NMS is used.

The default conflict groups prevent incompatible whole-spear/damage modes and incompatible Lunge modes from coexisting.


## Combat scope, carryover, Elytra, and stasis

MaceGuard registers these WorldGuard `StateFlag` values during `onLoad`, before the registry locks:

```text
warzonerotator-combat-zone
warzonerotator-stasis
```

An effective `warzonerotator-combat-zone: allow` can give a CombatLogX-tagged player a transient latch. Acquisition occurs when the player is tagged or retagged while inside the flag, or when an already-tagged player moves or teleports into it. Only the player's own location is evaluated. The latch remains after region exit until untag, death, logout, reload replacement, or plugin disable. A CombatLogX bypassed player cannot acquire or retain it.

The effective `warzonerotator-stasis` value is captured when the latch is acquired. A denied result remains denied for that latch even after the player leaves. Absent or allowed does not prohibit stasis. Flag lookup failures fail closed to *no additional MaceGuard restriction*; they never expand to a world-wide fallback.

Each modifier has an independent carry decision:

```yaml
modifiers:
  wind-charge-cooldown-5:
    combat-carryover: true
  wind-charge-cooldown-10:
    combat-carryover: false
```

Inside the configured Warzone, active modifiers continue to work normally. Outside it, an exact modifier applies only while the player is still CombatLogX-tagged, holds the latch, that modifier has carryover enabled, and no explicit bypass applies. The runtime always uses the current live Rotator selection; it does not snapshot the selection when combat begins. Cooldown policy changes are reconciled by target so an old variant cannot enforce a newly active variant.

Only combat item and ability targets can carry. Validation rejects carryover for cobwebs, crystals, respawn anchors, block/environment rules, reset behavior, and other world mutation.

During combat, a player cannot normally begin gliding. A player already gliding when tagged is not forced down. A latched player may start gliding only while the live `ELYTRA_NO_ROCKETS` effect applies in the current scope, including its carryover rule after region exit. `PlayerElytraBoostEvent` is canceled during combat, so actual propulsion is blocked without canceling ordinary firework use. CombatLogX remains responsible for holding the combat timer while gliding.

Ender Pearls are tracked individually. At teleport time, a pearl is aged when its own lifetime is at least `combat.stasis.minimum-age` (default `60s`). MaceGuard cancels only the correlated Ender Pearl teleport when the pearl is aged, the owner is still tagged, the owner holds a latch whose captured stasis policy is denied, and no MaceGuard bypass applies. The throw is not canceled, suspended pearls are not removed early, and no pearl is refunded or respawned. Tracking is per owner and per projectile, short-lived after impact, globally bounded, and cleared on death, logout, reload, and disable.

## Commands

### Read commands

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

`/warzone info` shows final source, active kit where applicable, ordered modifiers, override mode/expiry, suppressed automatic slot, and next boundary. `/warzone next` always describes the next automatic entry, including while an override is active.

### Management commands

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

Commands are case-insensitive and tab completion is permission-filtered. Live enabled modifier IDs, kit IDs, duration aliases (`1h`, `next`, `manual`), and schedule operations are supplied. Unknown IDs report close matches where available.

A valid ID bypasses selection menus but not confirmation/duration for players. Console cannot open a GUI and must provide explicit IDs and a duration.

Compatibility commands are retained:

```text
/warzone set <modifier> [modifier...]
/warzone force [1h|next|manual]
/warzone skip [1h|next|manual]
/warzone extend <duration>
```

## GUI ownership and sessions

Managed inventories use dedicated inventory holders. The title is presentation only.

A player session stores operation type, original source/modifiers, proposed source/ID/modifiers, open time, and page. Sessions are cleared on close, quit, reload, plugin disable, completion, timeout, and stale-holder mismatch.

The listener cancels:

- ordinary managed-inventory movement;
- shift-click insertion;
- number-key swaps;
- drag operations;
- double-click collection;
- offhand swaps.

Closing before final confirmation cancels the operation.

The preview screen separates current/proposed source and modifiers, additions, removals, and kit detachment. Confirmation is explicit. The duration screen uses a clock, compass, and lever, and includes the exact next boundary and next entry in the next-schedule option.

## Permissions

```text
warzonerotator.command.info
warzonerotator.command.modifiers
warzonerotator.command.kits
warzonerotator.command.next
warzonerotator.command.schedule
warzonerotator.command.menu

warzonerotator.manage.modifier
warzonerotator.manage.kit
warzonerotator.manage.random
warzonerotator.manage.override
warzonerotator.manage.schedule
warzonerotator.manage.custom-combinations
```

`warzonerotator.admin` inherits every management and compatibility permission. Existing command permissions remain as compatibility parents/aliases. Custom count-limit bypass and detaching a kit require `warzonerotator.manage.custom-combinations`.

## Persistence

`state/warzone-state.yml` stores a versioned record containing slot identity/index/cycle position/phase, automatic start/end/source/ID/modifiers, optional override source/ID/modifiers/mode/activation/expiration, warning state, schedule enable override, and selection sequence.

Writes remain atomic and ordered through the existing storage executor. Invalid state is backed up and rejected with a logged reason. It never expands scope or restrictions.

Startup performs one publication sequence:

1. load and validate state;
2. compute the due automatic slot;
3. reuse a matching persisted random slot result;
4. load and validate an override;
5. discard an expired override;
6. publish exactly one final active set.

## Transition cleanup

Every effective change passes through the existing runtime transition coordinator. It clears transient Lunge/projectile state, reconciles owned visual cooldowns for changed targets, clears authoritative cooldown records only for changed restriction targets, performs cobweb clear-on-meta-change when required, emits removed/added modifier messages once, and suppresses announcements when only the background automatic slot changes beneath an override.

## PlaceholderAPI

New values:

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

Spear values:

```text
%warzone_spear_status%
%warzone_spear_disabled%
%warzone_spear_damage_status%
%warzone_spear_damage_cooldown_seconds%
%warzone_spear_lunge_status%
%warzone_spear_lunge_disabled%
%warzone_spear_lunge_cooldown_seconds%
```

All existing generic, restriction, and indexed modifier placeholders remain. Indexed modifiers follow the final active order. Non-applicable source IDs, kits, modes, and expiration fields return an empty string.

## Validation and debug

`/warzone validate` checks schema version, modifier/kit IDs, enabled members, duplicates, conflicts, icon materials, schedule anchor/timezone/cadence/cycle, entry fields, referenced kits/modifiers, random feasibility, special rules, restriction capabilities, illegal combat carryover, stasis duration, CombatLogX availability, custom combat-flag availability, and resolved WorldGuard geometry.

`/warzone debug` includes schema, schedule state, active and carried modifiers, CombatLogX availability, tag/bypass/timer state for a selected player, effective combat-zone status, latch and retained stasis policy, Elytra decision, state-store health, exact scope resolution, cooldown count, and temporary-cobweb status.
