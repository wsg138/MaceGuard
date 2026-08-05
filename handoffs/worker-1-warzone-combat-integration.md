# Worker #1 handoff: Warzone combat integration

## Repository state

- Repository: `wsg138/MaceGuard`
- Branch: `agent/warzone-combat-integration`
- Pull request: `#17`
- Starting main SHA: `9bcb24bbaef6daf2deecdd45979c64e26dc310d8`
- Final branch head SHA: `SELF` — the commit containing this file; resolve with `git rev-parse HEAD`. The exact immutable SHA is duplicated in PR #17's final Worker #1 comment.
- Version: `6.1.0`
- Configuration schema: `7`
- PR status: draft, open, and unmerged

## Implemented architecture

CombatLogX remains authoritative for combat state, timers, bypass, ordinary combat teleport prevention, logout punishment, and Ender Pearl retagging. MaceGuard adds only Warzone-specific policy: effective WorldGuard flags, a transient combat latch, live modifier carryover, Elytra policy, and aged-stasis-pearl handling.

The reflective CombatLogX bridge was removed. The optional integration now uses the published public API through an integration-specific `DirectCombatLogXGateway` boundary. That class is instantiated only after the `CombatLogX` soft dependency is present, enabled, and compatible, preventing eager JVM linkage when CombatLogX is absent. Unrelated MaceGuard functionality remains enabled when the gateway is unavailable; combat-dependent Warzone behavior disables itself safely with a clear reason.

The build uses these provided dependencies from `https://nexus.sirblobman.xyz/public/`:

- `com.github.sirblobman.combatlogx:api:11.6-SNAPSHOT` (CI resolved `api-11.6-20251210.005328-47.jar`)
- `com.github.sirblobman.api:core:2.9-SNAPSHOT` (CI resolved `core-2.9-20260720.221205-67.jar`)

No CombatLogX fork, copied source, or internal reflection is required. Neither API is shaded.

## Event and lifecycle semantics

`PlayerTagEvent` and `PlayerReTagEvent` are pre-commit upstream events. Their callbacks capture the event location and defer reconciliation to the next server task, after CombatLogX commits state. `PlayerUntagEvent` is handled immediately after removal. Reload closes the old gateway and listeners before replacement, reconciles currently tagged players, and avoids duplicate registration. Disable retires listeners and clears transient latch, Elytra, and pearl state.

CombatLogX bypass remains authoritative. Bypassed players do not retain MaceGuard combat enforcement. Complete untag, death/logout cleanup, reload, and disable clear transient player state.

## WorldGuard and latch behavior

MaceGuard registers and queries the effective custom state flags:

- `warzonerotator-combat-zone`
- `warzonerotator-stasis`

Queries honor WorldGuard priority, inheritance, overlaps, and effective flag resolution. An unset combat-zone flag never grants a latch. An unset or allowed stasis flag never blocks a teleport. Query or flag-registration failure does not expand behavior to the whole world.

A tag or retag inside an effective combat-zone flag acquires the Warzone latch. A player already tagged who enters the flag also acquires it. The latch survives leaving and re-entering only while CombatLogX still reports the player tagged, then clears on complete untag or lifecycle cleanup. Outside opponents are not latched merely because the other participant was inside.

## Carryover behavior

Inside the effective Warzone, the current live Rotator meta applies normally. Outside, only restrictions from exact currently active modifiers with `combat-carryover: true` apply to a player who is both tagged and latched. State is not snapshotted at combat start, so live rotations, modifier enablement, and reloads immediately change the decision. One modifier's carryover setting cannot authorize another modifier. Building, cobweb placement, and world-mutation restrictions are rejected as carryover configuration.

## Elytra and fireworks

Outside combat, Elytras and fireworks remain normal. When combat begins during an existing glide, MaceGuard does not force-stop the glide. A new glide is denied in ordinary combat. In latched Warzone combat, a new glide is allowed only when the current live Elytra-allowing modifier applies in scope, including its carryover setting outside the zone. Landing ends the continued-glide allowance. Actual Elytra boosts are blocked while combat policy denies boosts, but ordinary firework launching is not canceled.

## Stasis pearls

Each thrown pearl is tracked independently by entity UUID and owner. Age is measured from the tracked throw; normal and aged pearls are not conflated. Impact-to-teleport correlation is bounded and short-lived, including simultaneous impacts. A teleport is blocked only for a tagged, latched player when the correlated pearl meets the configured age and the captured effective stasis policy is denied. The implementation does not delete suspended pearls early or attempt a consumed-item refund. Trackers are cleared on unload, death/logout, reload, and disable, and denial messages are emitted only when a teleport is actually blocked.

## Configuration migration and regression fixtures

Schema 6 migrates explicitly to schema 7. Existing kits, custom modifiers, schedules, messages, restrictions, weights, and override-compatible data survive. The migration adds `combat.stasis.minimum-age: 60s` and defaults missing modifier carryover to `false` without overwriting custom combat values.

The five previously failing fixtures were corrected behaviorally:

1. The enabled-modifier weight fixture now mutates parsed schema-7 YAML at the exact modifier path and still requires a positive weight.
2. The disabled legacy-weight fixture mutates parsed schema-7 YAML to `enabled: false` and `weight: 0`, preserving the valid intended scenario.
3. The Elytra-only fixture disables each modifier structurally, enables only the Elytra modifier, and verifies the missing non-Elytra branch.
4. The disabled-kit-member fixture structurally disables `cobwebs` while retaining an otherwise complete schema-7 file and verifies kit rejection.
5. The bundled configuration fixture is now explicitly schema 7 and expects version 7.

Additional tests require the schema-7 combat section and stasis age, validate useful errors for incomplete files, verify schema-6-to-7 preservation, preserve custom stasis values, add missing defaults, and reject unsupported carryover targets.

## Deployment requirements

MaceGuard does not edit CombatLogX configuration automatically. Apply these manual Cheat Prevention 17.8 settings on the production-equivalent server.

`plugins/CombatLogX/expansions/CheatPrevention/items.yml`:

```yaml
prevent-elytra: false
force-prevent-elytra: false
elytra-retag: true
prevent-fireworks: false
prevent-riptide: true
riptide-retag: false
```

`plugins/CombatLogX/expansions/CheatPrevention/teleportation.yml`:

```yaml
prevent-portals: true
prevent-teleportation: true
allowed-teleport-cause-list:
  - ENDER_PEARL
ender-pearl-retag: true
untag: false
```

The complete production-equivalent Leaf/Paper staging matrix remains in `docs/DEPLOYMENT.md`. No live-server testing is claimed.

## Verification and candidate

Clean CI verification before this handoff commit:

- Command: `./mvnw -B clean verify`
- Java: Temurin `21.0.11+10`
- Maven: `3.9.11`
- Main source files compiled: `108`
- Test source files compiled: `58`
- Tests: `330` run, `330` passed, `0` failed, `0` errors, `0` skipped
- Result: `BUILD SUCCESS`
- Maven duration: `01:03 min`
- GitHub Actions: green
- Codacy: green; no new findings attributable to PR #17

Candidate filename: `MaceGuard-6.1.0-candidate.jar`
Candidate size: `834317` bytes
Candidate SHA-256: `9c4cd59dcc404ba5e9b4e280f704fca45c5f91ce70164c3125abedf00d45c19a`
Candidate source commit: `SELF`

The final-head CI run must confirm that this documentation-only handoff commit produces the identical reproducible JAR before the reviewer begins. The inspected JAR contains version 6.1.0, schema-7 resources, messages, the main plugin class, and one plugin descriptor. It contains no CombatLogX, BlueSlimeCore, WorldGuard, WorldEdit, PlaceholderAPI, test, documentation, transport, or reflection-only implementation packages.

## Independent reviewer instructions

Begin from the live PR #17 state, not from this summary alone.

1. Independently inspect the entire diff.
2. Re-run the complete Java 21 verification from a clean checkout.
3. Inspect the direct CombatLogX API class-loading boundary and event ordering.
4. Inspect effective WorldGuard flag semantics, priorities, inheritance, and failure behavior.
5. Adversarially test latch acquisition/cleanup, Elytra continuation/restart/boosts, stasis pearl correlation, and exact modifier carryover.
6. Inspect every unresolved review thread, requested change, GitHub Actions result, and Codacy result.
7. Fix every confirmed issue on the same `agent/warzone-combat-integration` branch and PR #17; do not create a replacement branch or PR.
8. Perform a second independent review after any repair.
9. Merge only when the full diff and all checks are completely green.
10. Rebuild and inspect the final JAR from the merged commit or the final reviewed commit, and publish that final artifact separately from this Worker #1 candidate.

PR #17 must remain draft and unmerged at this handoff.
