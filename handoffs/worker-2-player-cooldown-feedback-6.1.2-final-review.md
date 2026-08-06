# Worker #2 final review — MaceGuard 6.1.2 player feedback

## Scope and evidence

Worker #2 independently reviewed PR #20 from verified `main` `70b447c0cb6fe2d4a0d6f9634b6d910c86d6af64` through implementation head `ff26ff9ac7b29fa3246c7cdd7e85f2fac52c213a`. Two complete source passes were performed. The final documentation commit is represented as `SELF`; resolve it from live GitHub.

A local checkout and Maven installation were unavailable. Source review, repository writes, workflow inspection, review-thread inspection, and artifact retrieval used the GitHub connector. Duration boundaries were independently reproduced with Java 21. CI evidence is not presented as local reproduction. Live Paper/Leaf and client staging were unavailable.

## First review findings

### Backward-clock denial suppression

- Classification: important correctness issue
- File: `DenialThrottle.java`
- Behavior: a backward wall-clock change could classify a denial as a recent duplicate until time caught up.
- Impact: missing player feedback for an unbounded period.
- Repair: negative elapsed values start a new window; cleanup removes future timestamps with overflow-safe arithmetic.
- Test: an injected clock moves backward and the next first denial is delivered.

### Block-policy bypass violation

- Classification: important correctness issue
- Files: `BlockPolicyListener.java`, `CobwebListener.java`
- Behavior: place, break, bucket empty/fill, and policy-cobweb paths did not honor `warzonerotator.bypass`.
- Impact: privileged players could remain blocked and messaged.
- Repair: exact early bypass exits before policy resolution and cancellation.
- Tests: every player block/bucket path and policy-cobweb path proves no cancellation or message for bypass.

### Null policy-message context

- Classification: important correctness issue
- File: `WarzoneMessageService.java`
- Behavior: null material could reach material-name parsing.
- Impact: feedback handling could throw.
- Repair: safe generic fallback rendering.
- Test: null block-place material renders without failure.

### Extreme-duration overflow

- Classification: important correctness issue
- File: `WarzoneMessageService.java`
- Behavior: total-millisecond conversion could overflow.
- Impact: denial rendering could fail for extreme positive configuration values.
- Repair: format from seconds and nanos; use tenths below ten seconds and `BigInteger` for the final whole-second value.
- Tests: all required subsecond, singular, decimal, ten-second, minute, and extreme positive boundaries.

### Concrete-Spear API coverage

- Classification: test coverage issue
- File: `CooldownConcreteMaterialTest.java`
- Behavior: selected Spear materials were tested rather than every target API material.
- Repair: enumerate all API materials recognized by `RestrictionTarget.isSpear`.
- Test: each material shares `SPEAR` authority and owns only its concrete overlay.

The first repair produced two Codacy findings: duration-method complexity and object creation inside the Spear test iteration. Both were fixed without suppression by splitting bounded helpers and extracting the per-material assertion.

Commits:

- `9af3f56277cb8e8b743dff7034f56e4181357900`
- `744e2b75419ec629e8fd5b3bcdb93dcc964580de`

## Second review finding

### Stasis and ordinary Pearl throttle collision

- Classification: important correctness issue
- Files: `DenialThrottle.java`, `WarzoneMessageService.java`
- Behavior: stasis and ordinary Ender Pearl feedback shared one throttle key.
- Impact: one denial could hide the first explanation for the other cause.
- Repair: stasis uses the distinct semantic key `STASIS_PEARL`; ordinary restriction paths retain target IDs.
- Test: both first denials are delivered during one configured interval.

Commit: `ff26ff9ac7b29fa3246c7cdd7e85f2fac52c213a`.

A fresh re-read after this repair did not confirm another repository defect.

## Final conclusions

- The original eleven Codacy findings were rechecked. Nine repairs preserve behavior. The two PMD `UseConcurrentHashMap` suppressions remain exact declaration-level suppressions justified by primary-thread ownership and method-local deterministic aggregation. No broad suppression exists.
- Message total and remaining placeholders, disabled/start/active separation, whole-Spear wording, null fallback, and unparsed item/action placeholders are correct.
- Prior third-party cancellation remains silent and does not trigger MaceGuard restoration, duplication, or cooldown start.
- Pearl and Wind Charge authority starts once at finalized uncanceled launch. Mace starts only on accepted positive final damage.
- Whole-Spear authority remains shared under `SPEAR`; only the successful concrete Spear material owns an overlay. Spear Damage and Spear Lunge remain overlay-free.
- Allowed Elytra and ordinary fireworks remain silent. Blocked glide starts and actual boosts explain policy without an invented timer.
- Block, bucket, cobweb, and stasis feedback is delivered only when MaceGuard is responsible; allowed and bypassed paths are silent.
- Denial throttling preserves the first denial, isolates players and semantic targets, excludes start messages, cleans expired/future entries, and is reset safely on reload/disable.
- Existing customized message values remain intact, omitted new keys default, unknown top-level keys fail validation, and failed reload preserves the old runtime.
- Visual overlays remain presentation only and do not shorten authority or overwrite/clear a longer foreign cooldown.
- Workflow provenance continues to assert the exact source SHA, version 6.1.2, schema 7, dependency hashes, JAR metadata/content, and absence of provided API implementations or temporary files.

## Verification record

Exact final-head Build, Codacy Analysis, Codacy Cloud, test/source counts, dependency-tree result, warnings, artifact metadata, and merge evidence are recorded on PR #20. Embedding the documentation commit's own SHA or workflow IDs here would create another commit and invalidate that provenance.

## Staging and verdict

Live production-equivalent staging was not performed. The remaining matrix includes Paper/Leaf 1.21.11 lifecycle, WorldGuard, CombatLogX/BlueSlimeCore, Java, Bedrock/Geyser, every concrete Spear, third-party cancellation, item preservation, foreign cooldown ownership, reload success/failure, reconnect, modifier removal, shutdown, and repeated-input behavior.

Repository verdict: **READY**, subject to the exact `SELF` automated, artifact, review-thread, mergeability, and expected-head gates.

Production verdict: **LIVE STAGING REQUIRED**.
