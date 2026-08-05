# Worker #2 — Warzone Combat Integration Final Review

Date: 2026-08-05

Verdict: **READY** for the reviewed repository integration. Production deployment remains gated on the live staging matrix below.

## Verified repository state

- Initial `main`: `9bcb24bbaef6daf2deecdd45979c64e26dc310d8`
- Initial PR #17 head: `0062f5d8f2dd30760600dd5aa3ecc1c88d73fdeb`
- Worker #2 reviewed/fixed head: `c07668b7b5b550280e56b343a2c6560492c510e1`
- Squash merge/product source commit: `2970635e31afc966f7dd4f86d79d2856a958cffc`
- PR #17: merged
- Version: `6.1.0`
- Configuration schema: `7`

The required startup files `CHATGPT_START_HERE.md` and `REQUIREMENTS.md` were not present in the reviewed repository state. The available current handoff, Worker #1 report, implementation plan, `docs/WARZONE.md`, and `docs/DEPLOYMENT.md` were reviewed instead. A local checkout could not be established because the execution environment could not resolve GitHub, so no local clean-tree claim is made; the exact GitHub heads, diffs, checks, sources, and downloaded workflow artifacts were used.

## First-review defects

### 1. High — cross-world stasis-pearl correlation

- Files: `StasisPearlTracker.java`, `StasisPearlListener.java`
- Observed: impact correlation compared owner, tick, and x/y/z but not the world.
- Expected: an impact marker may correlate only with a teleport destination in the same world.
- Production impact: a same-owner pearl event at similar coordinates in another dimension could consume the wrong marker and block an unrelated teleport.
- Correction: added world UUID to tracked positions and made cross-world distance non-matching.
- Test: `sameCoordinatesInAnotherWorldNeverCorrelate`.

### 2. High — delayed tag reconciliation crossed player sessions

- File: `DirectCombatLogXGateway.java`
- Observed: next-tick reconciliation was keyed by UUID without proving that the online player was the same Bukkit player instance that produced the event.
- Expected: delayed tag work must be fenced to the original online session.
- Production impact: a rapid disconnect/reconnect could apply stale event-time latch eligibility to a replacement session.
- Correction: require the original player to remain online and `Server#getPlayer(UUID)` to resolve to that exact instance.
- Test: `deferredTagCannotCrossAReconnectSession`.

### 3. Medium — silent WorldGuard query failure

- File: `CombatScopeService.java`
- Observed: region-query exceptions failed closed but produced no diagnostic.
- Expected: affected behavior remains fail-closed and the failure is diagnosable without log spam.
- Production impact: combat-zone or stasis enforcement could appear inactive with no useful operator evidence.
- Correction: added one-time warnings per query type while retaining fail-closed behavior and later retries.
- Test: `worldGuardQueryFailureDoesNotBroadenRestrictionAndWarnsOnce`.

## Worker #2 commit

`c07668b7b5b550280e56b343a2c6560492c510e1` — `fix: harden combat latch and stasis correlation`

Changed files:

- `src/main/java/com/lincoln/maceguard/warzone/combat/DirectCombatLogXGateway.java`
- `src/main/java/com/lincoln/maceguard/warzone/combat/CombatScopeService.java`
- `src/main/java/com/lincoln/maceguard/warzone/combat/StasisPearlTracker.java`
- `src/main/java/com/lincoln/maceguard/warzone/combat/StasisPearlListener.java`
- `src/test/java/com/lincoln/maceguard/warzone/combat/DirectCombatLogXGatewayTest.java`
- `src/test/java/com/lincoln/maceguard/warzone/combat/CombatScopeServiceTest.java`
- `src/test/java/com/lincoln/maceguard/warzone/combat/StasisPearlTrackerTest.java`

## Compatibility and behavior conclusions

### CombatLogX

The integration calls only the published `ICombatLogX`, `ICombatManager`, `TagInformation`, and public event API. The directly used `ICombatLogX` and `ICombatManager` source blobs in the production 11.6 release source matched the later official snapshot source used by Maven. Required methods include `isInCombat`, `canBypass`, `getMaxTimerSeconds`, `getTagInformation`, and `getMillisLeftCombined`.

Resolved provided artifacts:

- `api-11.6-20251210.005328-47.jar`
- `core-2.9-20260720.221205-67.jar`

The optional-dependency boundary is isolated behind the gateway factory; linkage and cast failures disable the combat integration rather than MaceGuard as a whole. The final JAR does not embed CombatLogX or BlueSlimeCore classes. This is sufficient source/binary-surface evidence for the merge, but an actual server startup against production CombatLogX `11.6.0.0.1286` and its installed BlueSlimeCore remains a staging requirement.

### WorldGuard

The two state flags are registered during `onLoad`, reuse an existing same-type flag, and reject conflicting types or a locked registry safely. Effective `RegionQuery#queryState` semantics are used with player associability; no world-wide fallback or outer-region-ID shortcut was found. Query failures now fail closed with bounded diagnostics.

### Latch and carryover

Tag and retag events capture event-time location and reconcile on the next server task after CombatLogX commits state. Canceled retags do not acquire a latch. Untag, death, logout, reload/runtime replacement, disable, and bypass remove or invalidate transient scope. The reconnect race was fixed.

Outside-region carryover requires current CombatLogX combat, an active latch, the exact live modifier marked for carryover, and no bypass. Live modifier changes take effect without retaining a combat-start snapshot. Building, block breaking, crystals, anchors, cobweb/environment mutation, reset, and temporary-block policies cannot be configured for carryover. The 5-second and 10-second wind-charge variants retain independent modifier decisions.

### Elytra

Ordinary fireworks are not blanket-blocked. Only Paper's Elytra boost event is canceled for actual boosting. Existing glide is not forcibly stopped; ordinary combat prevents a new glide, while the Warzone exception requires current combat, latch, and an eligible live Elytra modifier. CombatLogX remains authoritative for timer retention and per-player duration.

### Stasis pearls

Only the matched Ender Pearl teleport is eligible for cancellation. Age is based on the exact pearl entity and the configured threshold. The implementation does not cancel launch, detect chamber structure, use launch/landing policy, refund pearls, or broadly block all pearls. Correlation is bounded by owner, tick, distance, world, TTL, and per-owner/global limits.

### Schema and regressions

Schema 6-to-7 migration preserves existing configuration, defaults missing `combat-carryover` to false, and defaults stasis age to 60 seconds. Illegal world-mutating carryover targets fail validation. Invalid reloads preserve the prior valid runtime. Existing rotation, schedule, override, placeholder, Mace, Spear, Wind Charge, reset, temporary-block, persistence, and safe-scope tests remained green.

## Verification

### Exact final PR head

- Head: `c07668b7b5b550280e56b343a2c6560492c510e1`
- Build run: `31052186123` — success
- Codacy run: `31052186142` — success
- No unresolved review threads
- Mergeable before merge

### Merged main

- Product source: `2970635e31afc966f7dd4f86d79d2856a958cffc`
- Build run: `31052589256` — success
- Codacy run: `31052589251` — success
- Java: Temurin `21.0.11+10`
- Maven wrapper: `3.9.11`
- Main source files: `108`
- Test source files: `58`
- Tests run: `332`
- Passed: `332`
- Failures: `0`
- Errors: `0`
- Skipped: `0`
- Maven build duration: `01:03`

Non-blocking warnings were limited to action/runtime deprecations, javac annotation-processing notice, Mockito dynamic-agent warnings, and Maven Shade overlap for manifests and Java 9 module metadata between Gson and Error Prone annotations. JAR inspection confirmed these did not add conflicting plugin descriptors or dependency packages.

## Final artifact

- Filename: `MaceGuard-6.1.0.jar`
- Size: `835,816` bytes
- SHA-256: `632ff19cf6757649bd886e4a0bdb61bd812104a98a381e7ba69922bbf4031c27`
- Source commit: `2970635e31afc966f7dd4f86d79d2856a958cffc`

Inspection confirmed version 6.1.0, one `plugin.yml`, correct main class, hard dependency on WorldGuard, soft dependencies on CombatLogX and PlaceholderAPI, schema-7 `warzone.yml`, 60-second stasis default, correct message resources, and no tests, source, handoffs, workflows, CombatLogX, BlueSlimeCore, WorldGuard/WorldEdit, PlaceholderAPI, Mockito, JUnit, or Byte Buddy packages.

## Live staging

Performed: none. No production-equivalent server or Java/Bedrock client environment was available.

Required before production deployment:

- Leaf/Paper 1.21.11 startup, reload, disable, missing/incompatible CombatLogX handling;
- CombatLogX 11.6.0.0.1286 plus installed BlueSlimeCore and Cheat Prevention 17.8;
- melee/projectile/retag/death/logout/untag/bypass/timer UI lifecycle;
- WorldGuard flag priority, overlap, inheritance, border entry/exit, and absent flags;
- Elytra start/continued glide/firework boost/ordinary fireworks/Riptide/live-meta transitions;
- real 60-second bubble-column stasis chamber, multiple pearls/owners, another-plugin cancellation, chunk unload, and message rate limit;
- all carryover targets inside/outside with and without latch and across live rotations;
- Java and Bedrock/Geyser clients.

## Final decision

PR #17 was reviewed twice, repaired, verified on the exact head, and squash-merged. Repository integration verdict: **READY**. Production deployment must remain blocked until the documented live staging matrix passes.
