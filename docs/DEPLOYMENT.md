# MaceGuard 5 deployment and staging

## Safe weekly Warzone activation

Fresh installations ship with `warzone.yml` set to `enabled: false`. Complete this sequence before activation:

1. Create and review `warzone`.
2. Create and review `spawn`.
3. Create and review `market`.
4. Verify `region.world`, `region.id`, and every `excluded-region-id`.
5. Review every modifier `enabled` value, modifier weight, count weight, conflict group, and Elytra special rule.
6. Run `/warzone validate` and require a successful result.
7. Change the top-level `enabled` value to `true`.
8. Reload or restart.
9. Run `/warzone debug` and verify `Gameplay scope active: true`.
10. Run `/warzone items` and verify the effective state of Maces, Ender Pearls, Wind Charges, Spear Lunge, Elytra, and Cobwebs.

Do not create regions, change region geometry, or set flags automatically. If the world, outer region, or any required exclusion is missing, the selected weekly state remains loaded but effective gameplay scope is inactive. No whole-world fallback is applied.

## Schema-5 selection review

The default count weights are:

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

Every enabled modifier must have a positive integer `weight`. A disabled modifier remains in the file for later reuse but cannot be selected randomly or with `/warzone set`.

Before production activation, verify these conflict groups contain the intended IDs:

```text
mace-mode
ender-pearl-mode
wind-charge-mode
```

Only one mode from each group may be active in a weekly combination.

The default Elytra rules are:

```yaml
rotation:
  special-rules:
    elytra-no-rockets:
      weekly-inclusion-chance-percent: 8
      unrestricted-mace-chance-percent: 90
```

`rotation.special-rules` supports only `elytra-no-rockets`. An inclusion chance from 1 through 99 requires at least one feasible Elytra combination and at least one feasible non-Elytra combination whose size has a configured count weight. Any positive unrestricted-Mace chance also requires at least one feasible Elytra combination without a modifier that restricts the `MACE` target. Validation rejects an impossible branch rather than silently changing its effective probability.

This makes Elytra uncommon and leaves Maces unrestricted in most Elytra weeks. Setting Elytra `enabled: false` overrides the inclusion percentage and still requires a feasible non-Elytra branch.

## Disabling outcomes safely

Disable one outcome without deleting its section:

```yaml
modifiers:
  ender-pearl-disabled:
    enabled: false
```

This still allows both Pearl cooldown outcomes. To leave Pearls unrestricted every week, disable all three Pearl outcomes. Apply the same method to all Wind Charge or Mace outcomes.

After changing toggles or weights:

1. Run `/warzone validate`.
2. Run `/warzone reload`.
3. Confirm the current persisted selection either remains valid or is rerolled.
4. Confirm `/warzone debug` shows the original weekly calendar boundary and transition time after any required reroll.

## WorldGuard regions

Create and review these regions manually from administrator-selected WorldEdit geometry:

```text
warzone
war-pit
cobweb-box
spawn
market
warzone-reset
```

`warzone-reset` must be a separate cuboid covering only the useful terrain height. Do not select the entire Minecraft build height.

Recommended starting priorities:

```text
/rg setpriority warzone 10
/rg setpriority warzone-reset 20
/rg setpriority war-pit 50
/rg setpriority cobweb-box 60
/rg setpriority spawn 100
/rg setpriority market 100
```

Higher-priority overlapping regions control a flag only when they define a value for that flag. Review all existing inherited and direct values with WorldGuard before production deployment.

## Flags

Outer warzone:

```text
/rg flag warzone maceguard-explosives deny
/rg flag warzone maceguard-cobwebs allow
/rg flag warzone warzonerotator-cobwebs allow
```

WorldGuard must permit Elytra gliding in the outer warzone. MaceGuard never un-cancels WorldGuard or another plugin; it controls whether a player may start gliding and whether an actual firework boost is accepted for the active weekly modifier.

War pit:

```text
/rg flag war-pit maceguard-reset-profile war-pit
```

Cobweb box:

```text
/rg flag cobweb-box block-place allow
/rg flag cobweb-box block-break allow
/rg flag cobweb-box maceguard-cobwebs allow
/rg flag cobweb-box maceguard-block-policy cobweb-box
/rg flag cobweb-box maceguard-reset-profile cobweb-box
```

Large environmental reset cuboid:

```text
/rg flag warzone-reset maceguard-reset-profile warzone-environment
```

`maceguard-block-policy` should normally remain unset on `__global__`, `warzone`, `spawn`, `market`, and `war-pit`. An effective policy supplied by `__global__` can intentionally enforce a material whitelist throughout the world. MaceGuard reports that source but never removes or changes operator configuration.

## Capture, validation, and arming

Keep every required chunk loaded operationally; MaceGuard refuses instead of force-loading it.

```text
/maceguard capture <region>
/maceguard validate <region>
/maceguard plan <region>
/maceguard arm <region>
/maceguard schedule <region> on
```

Schedules remain off until an administrator explicitly enables each one. For a manual reset, run `plan` immediately before `reset` and use its one-use token:

```text
/maceguard plan <region>
/maceguard reset <region> <token>
```

The Warzone schema migration never captures, arms, or schedules any reset profile.

## Required Paper/Leaf 1.21.11 staging checks

### Calendar, persistence, and selection

1. Restart midweek repeatedly and confirm the selected modifier set does not reroll.
2. Simulate an offline weekly boundary and confirm recovery selects once and preserves the next Sunday 04:00 boundary.
3. Test `skip`, `force`, `set`, and `extend`; confirm none corrupts the calendar boundary.
4. Disable one currently persisted modifier, reload, and confirm it rerolls without moving the established boundary.
5. Set count weights to force one, two, and three outcomes in separate staging runs and confirm each count is selected deterministically.
6. Confirm a disabled outcome never appears in random rolls, tab completion, or `/warzone set`.
7. Confirm the Mace, Pearl, and Wind Charge conflict groups never produce two incompatible modes from the same group.
8. Set Elytra inclusion to `0`, `1`, `8`, `50`, `99`, and `100`; confirm valid configurations preserve those branches and impossible Elytra or non-Elytra branches are rejected by `/warzone validate`.
9. Set Elytra unrestricted-Mace chance to `0`, `1`, `90`, and `100`; confirm every positive value is rejected when no Mace-unrestricted Elytra combination exists.
10. Add a custom modifier ID that restricts `MACE` and confirm the unrestricted-Mace rule recognizes it by target rather than by bundled ID.
11. Add a `rotation.special-rules` entry for another known modifier and confirm validation rejects it as unsupported.
12. Test fresh startup, invalid-state restoration, `force`, and a natural weekly transition after every valid branch configuration.

### Runtime restrictions

13. Test `mace-disabled` and `mace-cooldown` separately. Cancelled or zero-damage attacks must not start a cooldown.
14. Test all three Ender Pearl modes from main hand and offhand. A disabled launch must be cancelled without consumption; successful cooldown launches must start the configured five- or ten-second cooldown exactly once; cancellation at either projectile event must not start one.
15. Test all three Wind Charge modes from main hand and offhand with the same successful/cancelled-launch checks.
16. During `wind-charge-disabled`, fire Wind Charges from dispensers inside the effective scope, outside pointing inward, and inside pointing outward. The dispense or finalized projectile launch must be cancelled. During either cooldown outcome, dispensers must remain allowed because no player owns the cooldown.
17. Confirm `no-lunge` blocks only correlated Lunge velocity and does not block holding, swapping, normal attacks, throwing, or unrelated velocity.
18. Confirm `elytra-no-rockets` allows gliding, blocks actual boosts, does not block moving or holding rockets, and does not consume a rejected rocket.
19. Confirm the bypass permission remains unrestricted for every player-driven mode.
20. Repeat player projectile checks from a Geyser/Bedrock client.

### Scope and exclusions

21. Enter `spawn` and `market` from every warzone edge and confirm all restrictions and cooldown overlays disappear.
22. Remove or rename the outer region, then each exclusion in turn; verify attacks, projectiles, Lunge, Elytra, rockets, cooldowns, overlays, dispenser Wind Charges, and warzone cobweb behavior remain unrestricted everywhere.
23. Recreate each region and confirm exact membership returns without restarting.
24. Verify the human-readable status placeholders return `Inactive` whenever required scope geometry is unresolved.
25. Verify machine-readable booleans return `false` and cooldown seconds return `0` while scope is inactive.

### Migration

26. Migrate the default schema-4 file and confirm the schema-5 result validates.
27. Migrate customized built-in modifiers and confirm their compatible fields and explicit enabled/weight values are preserved.
28. Migrate a valid custom modifier and custom conflict group; confirm the custom modifier receives only missing `enabled: true` and `weight: 10` defaults.
29. Make the custom modifier invalid and confirm migration leaves the original active file unchanged, removes the temporary output, and retains the backup and error report.
30. Confirm an existing weekly state and transition boundary survive migration or a required invalid-ID reroll without schedule drift.

### Placeholders and diagnostics

31. Exercise every new status placeholder in allowed, disabled, cooldown, inactive, and Elytra-active states.
32. Confirm `%warzone_restrictions%` lists Pearl and Wind Charge restrictions.
33. Confirm `/warzone modifiers` reports each enabled state, weight, conflict group, count weights, and Elytra percentages.
34. Confirm `/warzone items` matches actual runtime enforcement.

### Existing block and reset safety

35. Confirm `cobweb-box` works only when both `maceguard-block-policy cobweb-box` and `maceguard-cobwebs allow` are effective.
36. Deliberately invalidate the main schema and verify cobweb placement is not cancelled or tracked by MaceGuard and no TTL is written.
37. Test block placement/breaking, water fill/empty, boundary flow, ice melt, infinite sources, pistons, and dispensers.
38. Capture and reset bounded profiles with loaded and deliberately unloaded chunks; unloaded chunks must be refused, never force-loaded.
39. Put a normal solid structure block at a filtered coordinate and confirm preflight skips/reports it and reset does not overwrite it.
40. Interrupt a staged restore and confirm the journal locks further destructive work for administrator review.

## Emergency operator mitigation

When players report unexpected restrictions, first inspect the deployed version, exact scope resolution, selected modifiers, and WorldGuard flag sources:

```text
/version MaceGuard
/rg info warzone
/rg flags warzone
/rg flags __global__
/warzone validate
/warzone debug
/warzone items
/maceguard here
```

Do not grant bypass as the primary fix. On MaceGuard 5, unresolved required geometry automatically makes effective gameplay scope inactive. If `/maceguard here` identifies `__global__` as the policy source, manually review that WorldGuard flag before changing it.
