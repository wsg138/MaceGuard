# MaceGuard 6.1.2 player-feedback handoff

## Repository state

- Repository: `wsg138/MaceGuard`
- Verified review base: `70b447c0cb6fe2d4a0d6f9634b6d910c86d6af64`
- Branch: `agent/player-cooldown-feedback-6.1.2`
- Pull request: `#20`
- Initial feature head: `dfffb9f1cd0a1438bb4533bd3bafbf8e5a34df32`
- Worker #1 reviewed candidate: `7437d0b5cfc920443825f4960fe9a23567143763`
- Worker #2 final implementation head: `ff26ff9ac7b29fa3246c7cdd7e85f2fac52c213a`
- Completion documentation commit: `SELF`; resolve the exact final head from live GitHub.
- Version: `6.1.2`
- Configuration schema: `7`
- Target: Java 21, Paper/Leaf 1.21.11

## Feature contract

Unrestricted and bypassed actions remain untouched and silent. Fully disabled actions are canceled with an immediate explanation and no fabricated timer or item overlay. Successful timed actions begin authority only after finalized success, apply an owned concrete-material overlay only where the entire item is actually restricted, and send one start message. Active attempts report upward-rounded authoritative remaining time without restarting or extending authority.

The implementation covers Ender Pearls, Wind Charges, Maces, whole-Spear use, Spear Damage, Spear Lunge, Elytra glide starts, actual boosts, cobwebs, block placement/breaking, bucket use, and stasis pearls. Automated launches do not invent a player recipient.

## Worker #1 Codacy remediation

The initial feature head produced eleven Codacy Cloud findings. Nine valid complexity, naming, duplicate-literal, and test-helper findings were repaired. Two PMD `UseConcurrentHashMap` reports were retained as narrow declaration-scoped suppressions:

- authoritative cooldown state is private and Bukkit-primary-thread-owned;
- the second map is method-local deterministic aggregation and cannot escape.

Worker #2 independently traced production callers and repository asynchronous code. No asynchronous access, mutable reference escape, callback capture, class-wide suppression, package exclusion, global ruleset change, or behavioral weakening was found.

## Worker #2 first review findings and repairs

| Classification | Location | Observed behavior and impact | Repair and regression coverage |
|---|---|---|---|
| Important correctness | `DenialThrottle` | Backward wall-clock movement could treat a denial as a recent duplicate until time caught up. | Rebase on a backward clock and purge future timestamps; injected-clock regression test. |
| Important correctness | `BlockPolicyListener`, `CobwebListener` | Player block, bucket, and policy-cobweb restrictions did not honor `warzonerotator.bypass`. | Early bypass exits; place/break/empty/fill/cobweb silence and non-cancellation tests. |
| Important correctness | `WarzoneMessageService` | Null policy material context could reach target parsing and throw instead of providing safe feedback. | Safe generic fallback rendering and regression test. |
| Important correctness | `WarzoneMessageService.playerDuration` | `Duration.toMillis()` could overflow for extreme positive values. | Seconds/nanos formatting with `BigInteger` only at the whole-second boundary; required duration matrix and overflow tests. |
| Test coverage | `CooldownConcreteMaterialTest` | Only selected Spear materials were asserted. | Enumerate every target-API `Material` ending in `_SPEAR` and prove shared authority plus concrete overlay ownership. |

The first repair introduced two Codacy findings: duration-method cyclomatic complexity and object construction inside a test loop. Both were repaired without suppression by splitting bounded helpers and extracting the per-material assertion.

Commits:

- `9af3f56277cb8e8b743dff7034f56e4181357900`
- `744e2b75419ec629e8fd5b3bcdb93dcc964580de`

## Worker #2 second review finding and repair

A fresh complete pass found that stasis-pearl feedback and ordinary Ender Pearl cooldown feedback shared the same throttle key. A recent denial in one channel could silence the first denial in the other. Commit `ff26ff9ac7b29fa3246c7cdd7e85f2fac52c213a` assigns stasis a distinct semantic throttle key while retaining per-channel duplicate suppression. A regression test proves both first denials are delivered independently.

No additional repository defect was confirmed after re-reading the final implementation diff.

## Independent behavior conclusions

- Pearl and player Wind Charge launches start once only after uncanceled finalized launch; active denials preserve the item; prior third-party cancellation does not restore, duplicate, message, or start authority.
- Mace authority starts only after accepted positive final damage, not a swing, miss, zero damage, or previously canceled event.
- Whole-Spear launch and melee use share `SPEAR` authority. The actual successful material is stored only for owned overlay presentation. Material switching cannot bypass authority or create parallel authorities.
- Spear Damage starts only on accepted positive direct or correlated projectile damage and never applies a whole-item overlay.
- Spear Lunge starts only after a narrow correlated velocity remains accepted at final event priority and never applies a whole-item overlay.
- Allowed glide starts and ordinary fireworks remain silent. Blocked glide starts and actual boosts explain the cause without a fake timer or overlay.
- Block/cobweb/bucket actions are canceled and messaged only when MaceGuard is responsible. Allowed, bypassed, prior-canceled, and automated actions remain silent.
- Denial throttling is per player and semantic target, preserves the first denial, excludes successful-start messages, cleans expired and future-clock entries, and is reset safely on reload/disable.
- Existing customized `warzone-messages.yml` files retain existing values and receive defaults for omitted new keys. Unknown top-level keys remain invalid and failed reload preserves the previous active messages.
- Visual overlays are presentation only. MaceGuard does not shorten authority, overwrite or clear a longer foreign cooldown, or project `SPEAR_DAMAGE`/`SPEAR_LUNGE` as whole-item cooldowns.
- Build provenance checks assert exact source SHA, version 6.1.2, schema 7, one `plugin.yml`, correct main/dependencies, dependency hashes, no provided API shading, and no tests/source/handoffs/temp files in the JAR.

## Verification and artifact provenance

Exact successful implementation-head and final documentation-head Build, Codacy Analysis, Codacy Cloud, test totals, source counts, dependency-tree result, warnings, artifact metadata, and expected-head merge evidence are recorded in PR #20 because embedding the documentation commit's own SHA or workflow run IDs here would create another commit and invalidate the recorded provenance.

## Staging status

Live production-equivalent staging was not available. The remaining matrix includes Paper/Leaf 1.21.11 lifecycle, WorldGuard effective regions, CombatLogX/BlueSlimeCore carryover, Java and Bedrock/Geyser clients, all concrete Spear materials, third-party cancellation, item preservation, foreign cooldown ownership, reload success/failure, reconnect, modifier removal, disable cleanup, and high-rate duplicate clicks.

Repository verdict: **READY**, subject to the final exact-head automated/artifact/merge gates.

Production verdict: **LIVE STAGING REQUIRED**.
