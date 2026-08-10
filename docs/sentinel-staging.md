# Enthusia Sentinel staging

MaceGuard uses the existing production Enthusia Sentinel shared service in `wsg138/EnthusiaStaff-Staging`. Its Sentinel target contract is declared by `.enthusia-test.yml` and uses the manual-only `dependencies` profile.

## Why the dependency profile is required

MaceGuard declares WorldGuard as a hard Bukkit dependency. Sentinel's ordinary bare-Paper startup/restart profiles do not inherit production plugins, so running them without the trusted dependency closure would only prove that Bukkit rejects a missing hard dependency. That is not a meaningful MaceGuard smoke test.

The repository manifest therefore requests the `dependencies` profile and the direct trusted dependency ID `worldguard` with `kind: hard`. Trusted Sentinel registry state expands that declaration to the dependency-first enable/startup order: WorldEdit, then WorldGuard, then MaceGuard. The manifest intentionally does not duplicate WorldEdit or choose dependency source/version/artifact coordinates.

The profile also executes `warzone debug` after startup so staging exercises MaceGuard command registration and Warzone runtime diagnostics after the approved dependency closure enables.

## Control-plane trust boundary

Production Sentinel must contain all of the following before a MaceGuard dependency test is accepted:

1. MaceGuard's immutable GitHub repository ID `1209823832` and canonical name `wsg138/MaceGuard` in `config/sentinel-policy.toml`, configured with `automatic_transitions = false` and only the manual `dependencies` profile allowed.
2. Reviewed WorldEdit and WorldGuard entries in `config/sentinel-dependencies.toml` locked to exact source repository identity, producer commit SHA, successful workflow run, artifact ID/name, JAR path, SHA-256, plugin identity, with WorldGuard's registry entry explicitly depending on WorldEdit.
3. The existing `Enthusia Sentinel` GitHub App installation covering the repositories required by those operations.

The owner intentionally keeps the existing GitHub App installed with **All repositories**. That visibility is not Sentinel execution authorization and must not be changed to selected-repository mode for MaceGuard onboarding. Sentinel execution remains fail-closed through immutable policy, and production uses short-lived installation tokens scoped to the exact enabled execution repository IDs. Dependency acquisition uses a separate short-lived token scoped only to the exact trusted artifact-source repository IDs.

Do not put WorldGuard/WorldEdit URLs, versions, checksums, repository identities, artifact identities, or credentials in `.enthusia-test.yml`; those values remain control-plane trust state.

## Running the staging test

After the control-plane policy, dependency registry, runtime, and token boundaries are canonically deployed, use the exact standalone comment on an open, non-draft, same-repository MaceGuard PR whose current head has a successful exact-SHA `MaceGuard` Actions artifact:

```text
@enthusia-sentinel test dependencies
```

For the SEN-R02 acceptance target, Sentinel captures the current PR head while processing the command, fetches the manifest from that SHA, and revalidates the PR head and source comment before durable admission. A head change before admission fails closed. Once admitted, the manual job remains bound to that captured immutable SHA; a later PR head change does not retarget or cancel already admitted legitimate work. Therefore terminal SEN-R02 evidence must name the exact admitted SHA, and a newer PR head requires its own fresh command and acceptance result.

A successful production dependency staging run must finish exactly with:

```text
PAPER_DEPENDENCIES_OK
```

That result and all SEN-R02 acceptance evidence must bind the exact admitted MaceGuard PR head and artifact, resolve and verify the locked WorldEdit/WorldGuard provenance, stage only the target plus approved closure, enable WorldEdit then WorldGuard then MaceGuard, execute `warzone debug`, stop Paper normally, reap processes, clean the disposable sandbox, and report terminal evidence through the established GitHub path.

A queued or resource-gated job is not a pass. Do not cancel unrelated legitimate Sentinel work to obtain the shared lane, and do not weaken resource, authorization, artifact, isolation, credential, or cleanup controls.

For Warzone behavior changes, live production-equivalent checks are still required for WorldGuard region geometry, Java/Bedrock event ordering, and mechanics that this no-client dependency profile cannot exercise.

## Warzone regression checks for gameplay changes

In addition to Sentinel dependency evidence, manually verify when a change touches these behaviors:

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
