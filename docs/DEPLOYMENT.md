# MaceGuard 5 deployment and staging

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

Do not set `maceguard-block-policy` on `warzone`, `spawn`, or `market` unless a matching deliberate policy exists. A missing referenced policy fails closed.

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

## Required Paper/Leaf 1.21.11 staging checks

1. Restart midweek repeatedly and confirm the selected modifier set does not reroll.
2. Simulate an offline weekly boundary and confirm recovery selects once and preserves the next Sunday 04:00 boundary.
3. Test `skip`, `force`, `set`, and `extend`; confirm none corrupts the calendar boundary.
4. Enter `spawn` and `market` from every warzone edge and confirm all weekly restrictions and cooldown overlays disappear.
5. Test mace-disabled and mace-cooldown separately. Cancelled or zero-damage attacks must not start a cooldown.
6. Confirm no-lunge blocks only the correlated Lunge velocity and does not block holding, swapping, normal attacks, throwing, or unrelated velocity.
7. Confirm elytra-no-rockets allows gliding, blocks actual boosts, does not block moving/holding rockets, and does not consume the rocket on rejection.
8. Confirm cobweb-box works while the weekly cobweb modifier is inactive.
9. Test block placement/breaking, water fill/empty, flow across every boundary, ice melt, infinite sources, pistons, and dispensers.
10. Capture and reset war-pit and cobweb-box with loaded and deliberately unloaded chunks; unloaded chunks must be refused, never force-loaded.
11. Put a normal solid structure block at a filtered coordinate and confirm preflight skips/reports it and reset does not overwrite it.
12. Change market stalls after capture and verify capture/preflight/restore exclusions leave them untouched.
13. Interrupt a staged restore and confirm the journal locks further destructive work for administrator review.
14. Verify overlapping WorldGuard priorities and every direct/inherited custom flag with `/maceguard here` and `/warzone debug`.
