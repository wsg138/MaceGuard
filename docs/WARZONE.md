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

A cooldown starts only after a real successful action. Pearl and Wind Charge decisions are made during `PlayerLaunchProjectileEvent`, then committed only after an uncancelled final projectile launch. Cancelled launches, failed actions, and actions cancelled by another plugin do not start cooldowns. Mace cooldowns start only after uncancelled applied damage.

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

1. Roll among feasible configured modifier counts using `count-weights`.
2. Select enabled modifiers without replacement using their relative weights.
3. Keep only choices that can still complete a valid combination.
4. Enforce conflict groups and conditional rules.
5. Avoid the exact previous combination when an alternative exists.

If one weighted count cannot be filled because of conflicts, another feasible weighted count is used. Configuration validation fails when no valid combination can satisfy the minimum.

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

```yaml
rotation:
  special-rules:
    elytra-no-rockets:
      weekly-inclusion-chance-percent: 8
      unrestricted-mace-chance-percent: 90
```

The inclusion chance controls whether Elytra is considered for that week. When selected, gliding is allowed and firework boosting is blocked. The default unrestricted-Mace roll excludes both Mace restrictions 90% of the time; the remaining rolls apply no extra Mace exclusion.

Values must be from 0 through 100:

- inclusion `0`: Elytra is never selected;
- inclusion `100`: Elytra is selected whenever a valid combination exists;
- modifier `enabled: false`: overrides any inclusion percentage;
- unrestricted-Mace `100`: Elytra is incompatible with both Mace restrictions;
- unrestricted-Mace `0`: no additional Mace preference.

All rolls use the injected random source, including tests.

## Commands and diagnostics

`/warzone items` displays the effective state of Maces, Ender Pearls, Wind Charges, Spear Lunge, Elytra, and Cobwebs.

`/warzone modifiers` and `/warzone debug` display configured enabled states, weights, conflict groups, count weights, Elytra percentages, current selected IDs, and effective gameplay state.

`/warzone validate` reports invalid modifier weights, all outcomes disabled, impossible minimums, invalid count weights, invalid percentages, unknown rule or conflict references, and unsupported cooldown modes.

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

Boolean placeholders return `true` or `false`. Cooldown placeholders return `0` or the configured number of seconds. Existing generic placeholders remain backward compatible and `%warzone_restrictions%` includes all new restriction targets.

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

Before rewriting a schema-4 file, MaceGuard creates a timestamped backup. It then preserves the explicit enabled value, scope IDs, exclusions, weekly schedule, warning times, messages, cobweb settings, restriction policies, compatible modifier text/restrictions, and weekly state. New fields and outcomes receive bundled defaults.

If a persisted active ID is disabled or invalid after migration, a valid replacement is selected without moving its stored weekly transition boundary. Migration never creates WorldGuard regions, enables the module automatically, captures or arms reset profiles, or changes reset schedules.
