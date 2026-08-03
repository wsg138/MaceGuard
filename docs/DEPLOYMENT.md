# MaceGuard 5 deployment and staging

## Safe weekly warzone activation

Fresh installations ship with `warzone.yml` set to `enabled: false`. Complete this sequence before activation:

1. Create and review `warzone`.
2. Create and review `spawn`.
3. Create and review `market`.
4. Verify `region.world`, `region.id`, and every `excluded-region-id`.
5. Run `/warzone validate` and require a successful result.
6. Change `enabled` to `true`.
7. Reload or restart.
8. Run `/warzone debug` and verify `Gameplay scope active: true`.

Do not create regions, change region geometry, or set flags automatically. If the world, outer region, or any required exclusion is missing, the selected weekly state remains loaded but effective gameplay scope is inactive. No world-wide fallback is applied.

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

WorldGuard must permit elytra gliding in the outer warzone. MaceGuard never un-cancels WorldGuard or another plugin; it controls whether a player may start gliding and whether an actual firework boost is accepted for the active weekly modifier.

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

The intended block-policy assignment is:

```text
/rg flag cobweb-box maceguard-block-policy cobweb-box
```

`maceguard-block-policy` should normally remain unset on:

```text
__global__
warzone
spawn
market
war-pit
```

An effective policy supplied by `__global__` can intentionally enforce a material whitelist throughout the world. MaceGuard warns and reports the global source, but it never removes or changes that operator configuration. A valid-schema reference to a missing named policy fails closed. An invalid main schema disables policy enforcement and temporary cobweb behavior entirely.

## Capture, validation, and arming

Keep every required chunk loaded operationally; MaceGuard refuses instead of force-loading it.

War pit:

```text
/maceguard capture war-pit
/maceguard validate war-pit
/maceguard plan war-pit
/maceguard arm war-pit
/maceguard schedule war-pit on
```

Cobweb box:

```text
/maceguard capture cobweb-box
/maceguard validate cobweb-box
/maceguard plan cobweb-box
/maceguard arm cobweb-box
/maceguard schedule cobweb-box on
```

Environmental profile:

```text
/maceguard capture warzone-reset
/maceguard validate warzone-reset
/maceguard plan warzone-reset
/maceguard arm warzone-reset
/maceguard schedule warzone-reset on
```

Schedules remain off until an administrator explicitly enables each one. For a manual reset, run `plan` immediately before `reset` and use its one-use token:

```text
/maceguard plan <region>
/maceguard reset <region> <token>
```

## Emergency operator mitigation

When players report unexpected world-wide build or interaction restrictions, do not grant bypass as the primary fix. First inspect the deployed version and WorldGuard flag sources:

```text
/version MaceGuard
/rg info warzone
/rg flags warzone
/rg flags __global__
/warzone validate
/warzone debug
/maceguard here
```

For MaceGuard 4.0.1, disable the warzone module or restore the missing region before returning the server to normal gameplay. For MaceGuard 5.0.0, unresolved required geometry automatically makes effective gameplay scope inactive. If `/maceguard here` identifies `__global__` as the policy source, manually review and unset that WorldGuard flag only when it was not intended.

## Required Paper/Leaf 1.21.11 staging checks

1. Restart midweek repeatedly and confirm the selected modifier set does not reroll.
2. Simulate an offline weekly boundary and confirm recovery selects once and preserves the next Sunday 04:00 boundary.
3. Test `skip`, `force`, `set`, and `extend`; confirm none corrupts the calendar boundary.
4. Enter `spawn` and `market` from every warzone edge and confirm all weekly restrictions and cooldown overlays disappear.
5. Remove or rename the outer region, then each exclusion in turn; verify right clicks, placement, attacks, projectiles, Lunge, Elytra, rockets, cooldowns, overlays, and warzone cobweb behavior remain unrestricted everywhere.
6. Recreate each region and confirm exact membership returns without restarting.
7. Test mace-disabled and mace-cooldown separately. Cancelled or zero-damage attacks must not start a cooldown.
8. Confirm no-lunge blocks only the correlated Lunge velocity and does not block holding, swapping, normal attacks, throwing, or unrelated velocity.
9. Confirm elytra-no-rockets allows gliding, blocks actual boosts, does not block moving/holding rockets, and does not consume the rocket on rejection.
10. Confirm cobweb-box works only when both `maceguard-block-policy cobweb-box` and `maceguard-cobwebs allow` are effective.
11. Deliberately invalidate the main schema and verify cobweb placement is not cancelled or tracked by MaceGuard and no TTL is written.
12. Test block placement/breaking, water fill/empty, flow across every boundary, ice melt, infinite sources, pistons, and dispensers.
13. Capture and reset war-pit and cobweb-box with loaded and deliberately unloaded chunks; unloaded chunks must be refused, never force-loaded.
14. Put a normal solid structure block at a filtered coordinate and confirm preflight skips/reports it and reset does not overwrite it.
15. Change market stalls after capture and verify capture/preflight/restore exclusions leave them untouched.
16. Interrupt a staged restore and confirm the journal locks further destructive work for administrator review.
17. Verify overlapping WorldGuard priorities and every direct/inherited/global custom flag with `/maceguard here` and `/warzone debug`.
