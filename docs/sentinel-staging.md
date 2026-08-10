# Enthusia Sentinel staging

MaceGuard is onboarded as a dependency-aware Sentinel target through `.enthusia-test.yml`.

## Why the dependency profile is required

MaceGuard declares WorldGuard as a hard Bukkit dependency. Sentinel's ordinary bare-Paper startup/restart profiles do not inherit production plugins, so running them without WorldGuard would only prove that Bukkit correctly rejects a missing hard dependency. That is not a meaningful MaceGuard smoke test.

The repository manifest therefore requests the `dependencies` profile and the trusted dependency ID `worldguard` with `kind: hard`. The profile also executes `warzone debug` after startup so staging exercises MaceGuard command registration and Warzone runtime diagnostics after both plugins enable.

## Control-plane onboarding required

Before this profile can run, the private `wsg138/EnthusiaStaff-Staging` control plane must contain both of the following:

1. A policy entry for MaceGuard's immutable GitHub repository ID `1209823832`, canonical name `wsg138/MaceGuard`, with the `dependencies` profile allowed.
2. An enabled `worldguard` entry in `config/sentinel-dependencies.toml` locked to an exact repository ID/name, commit SHA, successful workflow run, unexpired artifact ID/name, JAR path, SHA-256, plugin name, and main class.

Do not put a WorldGuard URL, version, checksum, repository, or artifact identity in `.enthusia-test.yml`; Sentinel deliberately keeps those values in the trusted private dependency registry.

The GitHub App `Enthusia Sentinel` must also have selected-repository access to MaceGuard. If that installation permission is missing, repository onboarding is incomplete even when this manifest is present.

## Running the staging test

After the control-plane policy, dependency registry, and GitHub App access are ready, use the exact same-repository PR comment:

```text
@enthusia-sentinel test dependencies
```

A successful dependency staging run must finish with:

```text
PAPER_DEPENDENCIES_OK
```

That result should show WorldGuard and MaceGuard both enabled, the exact MaceGuard PR-head artifact, successful execution of `warzone debug`, normal Paper shutdown, process cleanup, and sandbox cleanup.

Keep a gameplay-changing PR in staging until the Sentinel dependency run is green. For Warzone behavior changes, live production-equivalent checks are still required for WorldGuard region geometry, Java/Bedrock event ordering, and mechanics that a no-client Sentinel profile cannot exercise.

## Warzone regression checks for this change

In addition to Sentinel startup evidence, manually verify on the staging server:

- A Wind Charge cooldown starts only after a successful player launch.
- During that cooldown, right-clicking air and blocks does not create a Wind Charge entity and does not consume the item.
- When the cooldown expires, Wind Charges work normally on the ground and in the air.
- The `wind-charge-disabled` modifier prevents player use before entity creation.
- Dispenser Wind Charges retain the existing automated-projectile behavior.
- `/warzone menu` main buttons open their intended screens.
- GUI names and lore render with explicit colors and without default purple/italic lore styling.
- `/warzone info`, `/warzone items`, `/warzone modifiers`, `/warzone schedule`, `/warzone next`, `/warzone help`, and `/warzone debug` remain readable and accurate.
- A temporary failure writing `warzone-state.yml` reports unhealthy persistence, retains the newest accepted state, and writes that state after storage recovers.
- WorldGuard `build`/placement restrictions are tested separately from MaceGuard: MaceGuard must not un-cancel cobweb, water, or lava placement that WorldGuard already denied.
