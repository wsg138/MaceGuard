# Integrated weekly Warzone configuration

MaceGuard 5 uses `plugins/MaceGuard/warzone.yml` schema 5. The module remains disabled on fresh installations until the configured WorldGuard scope validates. WorldGuard owns all region geometry; MaceGuard never creates or alters `warzone`, `spawn`, or `market`.

## Effective scope and schedule

The default effective scope is:

```text
warzone - spawn - market
```

All required regions must resolve in the configured world. Missing the outer region or either exclusion makes gameplay scope inactive instead of falling back to the whole world.

The weekly boundary remains Sunday at 04:00 in `America/Indiana/Indianapolis`. One to three outcomes are selected, persisted, and restored across restart. Manual rerolls and a reroll caused by newly disabled persisted IDs preserve the established transition boundary.

## Restriction targets

```yaml
restriction-targets:
  MACE:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
  ENDER_PEARL:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
  WIND_CHARGE:
    can-disable: true
    can-cooldown: true
    maximum-cooldown: 60s
  SPEAR_LUNGE:
    can-disable: true
    can-cooldown: false
```

A cooldown starts only after a real successful player action. Pearl and Wind Charge decisions are made during `PlayerLaunchProjectileEvent`, then committed only after an uncancelled final projectile launch. Cancelled launches, failed spawn attempts, and actions cancelled by another plugin do not start cooldowns. Mace cooldowns start only after uncancelled applied damage.

The `wind-charge-disabled` outcome also cancels a dispenser firing a Wind Charge when the dispenser source or projected launch point is inside the exact effective scope. Automated sources have no player cooldown owner, so Wind Charge cooldown outcomes intentionally allow dispensers.

## Enabled outcomes and weights

Every modifier has:

```yaml
enabled: true
weight: 10
```

An enabled modifier requires a positive integer weight. A disabled modifier is ignored by random selection, rejected by `/warzone set`, and may retain its previous weight.

Default outcomes:

| ID | Weight |
| --- | ---: |
| `cobwebs` | 10 |
| `no-lunge` | 8 |
| `mace-disabled` | 4 |
| `mace-cooldown` | 8 |
| `ender-pearl-disabled` | 3 |
| `ender-pearl-cooldown-5` | 9 |
| `ender-pearl-cooldown-10` | 6 |
| `wind-charge-disabled` | 3 |
| `wind-charge-cooldown-5` | 9 |
| `wind-charge-cooldown-10` | 6 |
| `elytra-no-rockets` | 1 |

Disable one outcome without affecting the others:

```yaml
modifiers:
  ender-pearl-disabled:
    enabled: false
```

This still permits both Pearl cooldown outcomes. Disable all three Pearl outcomes to leave Pearls unrestricted every week. The same rule applies to Wind Charges and both Mace outcomes.

## Weighted selection

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

Selection performs bounded work:

1. Validate every probability branch that can be rolled.
2. Roll among feasible configured modifier counts using `count-weights`.
3. Select enabled modifiers without replacement using their relative weights.
4. Keep only choices that can still complete a valid combination.
5. Enforce conflict groups and conditional rules.
6. Avoid the exact previous combination when an alternative exists.

A combination is feasible only when its modifier count has a positive configured count weight. Configuration validation rejects any enabled probability branch that has no feasible combination; startup, reload, force, restore, and natural transition never rely on a silent fallback.

## Conflict groups

```yaml
conflict-groups:
  mace-mode:
    - mace-disabled
    - mace-cooldown
  ender-pearl-mode:
    - ender-pearl-disabled
    - ender-pearl-cooldown-5
    - ender-pearl-cooldown-10
  wind-charge-mode:
    - wind-charge-disabled
    - wind-charge-cooldown-5
    - wind-charge-cooldown-10
```

No week or manual set can contain multiple modes for the same item.

## Elytra rarity

`rotation.special-rules` currently supports only the `elytra-no-rockets` entry:

```yaml
rotation:
  special-rules:
    elytra-no-rockets:
      weekly-inclusion-chance-percent: 8
      unrestricted-mace-chance-percent: 90
```

The inclusion chance controls whether Elytra is selected for that week. When selected, gliding is allowed and firework boosting is blocked. The default unrestricted-Mace roll selects only Elytra combinations without any modifier that restricts the `MACE` target 90% of the time; the remaining rolls apply no extra Mace exclusion.

Values must be from 0 through 100:

- inclusion `0`: at least one feasible non-Elytra combination must exist;
- inclusion `100`: at least one feasible Elytra combination must exist;
- inclusion `1` through `99`: both a feasible Elytra branch and a feasible non-Elytra branch must exist;
- modifier `enabled: false`: overrides any inclusion percentage and requires a feasible non-Elytra branch;
- unrestricted-Mace greater than `0`: at least one feasible Elytra combination without a `MACE` restriction must exist;
- unrestricted-Mace `100`: every selected Elytra combination is Mace-unrestricted;
- unrestricted-Mace `0`: no additional Mace preference.

A custom modifier that restricts the `MACE` target counts as a Mace restriction even when its ID is not one of the bundled Mace IDs. Invalid branches are rejected by configuration validation rather than renormalized or silently ignored.

All rolls use the injected random source, including tests.

## Commands and diagnostics

`/warzone items` displays the effective state of Maces, Ender Pearls, Wind Charges, Spear Lunge, Elytra, and Cobwebs.

`/warzone modifiers` and `/warzone debug` display configured enabled states, weights, conflict groups, count weights, Elytra percentages, current selected IDs, and effective gameplay state.

`/warzone validate` reports invalid modifier weights, all outcomes disabled, impossible probability branches, invalid count weights, invalid percentages, unsupported special-rule IDs, unknown conflict references, and unsupported cooldown modes.

## Placeholders

Human-readable status placeholders return `Allowed`, `Disabled`, `<n>s cooldown`, `Inactive`, or `Gliding allowed; rockets disabled`:

```text
%warzone_mace_status%
%warzone_ender_pearl_status%
%warzone_wind_charge_status%
%warzone_spear_lunge_status%
%warzone_elytra_status%
```

Machine-readable values:

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

Boolean placeholders return `true` or `false`. Cooldown placeholders return `0` or the configured number of seconds.

The currently selected weekly modifiers are also available by ordered position:

| Position | Display name | Internal ID | Configured description |
| ---: | --- | --- | --- |
| 1 | `%warzone_modifier_1%` | `%warzone_modifier_1_id%` | `%warzone_modifier_1_description%` |
| 2 | `%warzone_modifier_2%` | `%warzone_modifier_2_id%` | `%warzone_modifier_2_description%` |
| 3 | `%warzone_modifier_3%` | `%warzone_modifier_3_id%` | `%warzone_modifier_3_description%` |

These placeholders follow the active weekly modifier list in its stored order and do not perform alphabetical sorting. Display names and descriptions use the plain-text form of the active runtime configuration; IDs are returned exactly as stored. A position beyond the current modifier count returns an empty string. The values continue to describe the selected week when gameplay scope is inactive, while all nine return an empty string if no active selection or Warzone runtime is available.

Existing generic placeholders remain backward compatible, including:

```text
%warzone_current_modifiers%
%warzone_current_modifier_ids%
%warzone_description%
```

`%warzone_restrictions%` continues to include all restriction targets.

## Example weeks

```text
Cobwebs + 5s Pearl Cooldown
No Lunge + 10s Wind Charge Cooldown
Mace Cooldown + No Ender Pearls
Elytra, No Rockets + Cobwebs
Elytra, No Rockets + 5s Pearl Cooldown
```

With the default Elytra rule, most Elytra weeks leave Maces fully unrestricted.

## Schema-4 migration

Before rewriting a schema-4 file, MaceGuard creates a timestamped backup. It then preserves the explicit enabled value, scope IDs, exclusions, weekly schedule, warning times, messages, cobweb settings, restriction policies, built-in modifier settings, valid custom modifier definitions, conflict groups, and weekly state. New bundled fields and outcomes receive defaults.

A custom schema-4 modifier receives `enabled: true` and `weight: 10` only when those fields were absent. If a custom definition or conflict group makes the migrated schema invalid, migration stops before replacing the original `warzone.yml`; the backup and validation error remain available for manual correction.

If a persisted active ID is disabled or invalid after migration, a valid replacement is selected without moving its stored weekly transition boundary. Migration never creates WorldGuard regions, enables the module automatically, captures or arms reset profiles, or changes reset schedules.
