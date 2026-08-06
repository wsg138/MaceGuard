# Worker #2 — Warzone combat review blockers

## Final outcome

This was an independent final review of PR #18, not a continuation of Worker #1's implementation reasoning.

- Repository: `wsg138/MaceGuard`
- Verified starting `main`: `f823ae7041c3072c6e853a0606419646a9bbbb05`
- Verified initial PR head: `ce693db7bde72fb6f7529bb53527543558907d4e`
- Reviewed and merged PR head: `d0bd89774feb83f12664f5b23f05f7e6911fc250`
- Merge commit: `a6aff2f877debdd16446c8098e6bb7b5072ed6dd`
- Final main containing this report: `SELF`
- PR #18: closed as merged
- Version: `6.1.1`
- Configuration schema: `7`
- Repository verdict: **READY — MERGED**
- Production verdict: **STAGING REQUIRED**

`CHATGPT_START_HERE.md` and `REQUIREMENTS.md` were requested by the review prompt but are not present in the repository. The available current and historical handoffs, architecture plan, README, Warzone/deployment/dependency/migration/repair documentation, complete PR diff, changed tests, workflows, reviews, comments, checks, and exact-head artifacts were inspected through GitHub.

A local Git checkout could not be obtained because the worker runtime could not resolve GitHub over normal network access. GitHub Actions evidence below is exact-head CI evidence, not a separately reproduced local build. The repository administration endpoint for branch-protection details was inaccessible to the connector; the repository rulesets endpoint returned no rulesets.

## First independent review findings

### 1. Release blocker — dependency hashes were recorded but not enforced

`.github/workflows/build.yml` printed SHA-256 values for the timestamped Paper, BlueSlimeCore, and CombatLogX API JARs but never compared them with the documented values. A changed artifact at the expected Maven path could still pass.

Repair: require exact hash equality, verify the checked-out source SHA in Build as well as Codacy, preserve `pipefail`, retain dependency-tree and failed-test evidence, require the JAR, and strengthen package/descriptor inspection.

Regression/provenance evidence: the exact-head and merged-main Build jobs resolved all three documented timestamped dependencies and matched their expected hashes before accepting the JAR.

### 2. Important correctness defect — impact cleanup could erase enforcement authority

`StasisPearlTracker.cleanup(...)` converted an unusual exact-impact expiry to overflow, but later timer cleanup deleted that overflow. A sufficiently delayed teleport could outlive both windows and fail open.

Repair: unusual expiry becomes bounded owner/world count state that remains until one matching event consumes each count or normal owner/runtime lifecycle cleanup clears it. Repeated time cleanup cannot erase it.

Regression tests: durable overflow through repeated cleanup, 5,000-tick recovery, count-by-count consumption, and owner/world scoping.

### 3. Release blocker — class-wide PMD suppressions contradicted the ownership contract

The changed pearl tracker and diagnostic class used class-wide `PMD.UseConcurrentHashMap` or synchronized-method suppressions while the handoff claimed narrow declaration/method ownership suppressions.

Repair: remove class-wide suppressions. Suppress only exact private/local maps whose primary-thread ownership or deterministic iteration semantics require it; use an explicit private lock for diagnostics.

### 4. Important correctness/performance defect — disabled tracing still formatted hot-path details

Pearl launch, impact, and teleport handlers assembled trace strings and formatted locations before diagnostics determined that tracing was disabled.

Repair: diagnostic details are supplied lazily and evaluated only for an active selected-player session.

Regression tests: disabled tracing performs zero detail evaluations; enabled tracing evaluates and retains one record.

## Second independent review and subsequent findings

### 5. Documentation-only issue — recovery documentation still described a short window

`docs/WARZONE.md` and `docs/REPAIR-6.1.1.md` did not match the durable bounded recovery model.

Repair: document that unusual impact expiry becomes count-based owner/world state that survives timer cleanup, and document the conservative false-positive tradeoff when an expected teleport callback never arrives.

### 6. Release blocker — Codacy found a nested `HashMap` allocation

After the first repaired tree was analyzed, Codacy Cloud reported high-severity `PMD.UseConcurrentHashMap` on the nested owner/world overflow-map allocation.

Repair: use deterministic `LinkedHashMap` storage for the private nested world buckets without adding a broader suppression or asynchronous access.

### 7. Important correctness defect introduced during Worker #2 repair — residual integer tick horizon

The first durable-recovery repair used `Integer.MAX_VALUE` for maximum tick lag. That passed the required 5,000-tick scenario but still created a deterministic upper bound.

Repair: represent lag as a saturating `long` and use `Long.MAX_VALUE` for unusual-expiry recovery.

Regression test: `StasisPearlExtremeDelayTest` verifies recovery beyond the signed-integer tick range.

### 8. Post-merge workflow correctness defect — automated publish used the wrong artifact path

The merged source Build, tests, dependency checks, and JAR inspection passed, but `publish-latest` addressed `dist/MaceGuard.jar`. `actions/download-artifact` retained the uploaded `target/MaceGuard.jar` path at `dist/target/MaceGuard.jar`, so the publish job exited with code 1.

Repair in `SELF`: set `JAR=dist/target/MaceGuard.jar`, require the file with `test -f`, and pass that exact path to the existing `gh release upload/create` commands. This preserves the repository's established automated `latest` prerelease behavior; no manual release was created.

## Files changed by Worker #2

- `.github/workflows/build.yml`
- `docs/DEPENDENCIES.md`
- `docs/REPAIR-6.1.1.md`
- `docs/WARZONE.md`
- `src/main/java/com/lincoln/maceguard/warzone/combat/StasisPearlTracker.java`
- `src/main/java/com/lincoln/maceguard/warzone/combat/PearlEventDiagnostics.java`
- `src/main/java/com/lincoln/maceguard/warzone/combat/StasisPearlListener.java`
- `src/test/java/com/lincoln/maceguard/warzone/combat/StasisPearlTrackerTest.java`
- new `src/test/java/com/lincoln/maceguard/warzone/combat/StasisPearlExtremeDelayTest.java`
- new `src/test/java/com/lincoln/maceguard/warzone/combat/PearlEventDiagnosticsTest.java`
- `handoffs/CURRENT.md`
- this handoff

## Technical conclusions

### Legacy migration

Schema 4 -> 5 -> 7, schema 5 -> 7, and schema 6 -> 7 read each original source modifier node before schema-7 defaults are applied. Existing bundled and custom modifiers with a missing carryover field become `false`; explicit `false` remains false; explicit `true` remains only when the target is legally eligible. Bundled schema-7 defaults cannot leak `true` into an existing legacy modifier. Supported display, description, enabled state, weight, restrictions, messages, and supported custom values are preserved. Unknown fields remain invalid under the strict schema. Failed migration does not replace the active file; backup and temporary-file behavior remains atomic.

### Exact-one pearl correlation

One `PlayerTeleportEvent` with `ENDER_PEARL` cause invokes one destructive correlation and consumes no more than one exact impact or one overflow count. There is no second canceled-event handler performing another removal. An event canceled before MaceGuard consumes one relevant correlation but receives no MaceGuard warning and no additional MaceGuard cancellation decision. Two teleports consume two records. Non-pearl teleports consume none.

### PDC authority

The stable MaceGuard keys are authoritative: marker byte, format integer, owner UUID string, and launch epoch long. The format, owner, shooter relationship, and timestamp are validated. In-memory launch state is a bounded accelerator; PDC recovers marked pearls after cache eviction or runtime replacement. Missing cache state does not make a marked pearl ordinary. Unmarked vanilla pearls remain ordinary. Invalid marked metadata fails closed only for the affected owner/event and cannot poison unrelated players globally. Operator diagnostics are bounded and rate-limited.

### Cache and overflow bounds

Launch caches remain bounded at 32 per owner and 4,096 globally without erasing entity PDC authority. Exact impact queues remain bounded at eight per owner. Additional and unusually delayed impacts become count-based owner/world buckets; memory does not grow per overflowed impact. Neither repeated cleanup nor extreme tick advancement erases the count. One matching event consumes one count. Normal lifecycle cleanup clears affected owner state. The conservative tradeoff is that a missing expected teleport callback can cause one later same-owner/same-world pearl to fail closed; this remains a live-staging observation.

### Correlation ambiguity

Exact projectile UUID is retained at impact. Player/world and callback order are authoritative; destination distance is diagnostic because another plugin may modify the destination before MaceGuard. Cross-world records never correlate. Normal-only ambiguity remains allowed. Ambiguity containing aged or invalid marked state fails closed for the affected event, and only one queued record is consumed. A suspended non-impacted old pearl does not block an unrelated new normal impact.

### Elapsed-time semantics

Live records use monotonic nanoseconds. Cache-loss/runtime-replacement recovery uses the persisted epoch timestamp. The threshold is inclusive: 60 seconds means at least 60 elapsed seconds. `getTicksLived()` is not duration authority. A backward wall-clock result, future/non-positive/malformed timestamp, unsupported format, owner mismatch, or unreasonable timestamp follows the owner-scoped conservative policy. The unreasonable-age validation does not convert a genuinely long-lived valid marked pearl into an ordinary pearl.

### PMD and threading

No hidden asynchronous production caller was found for the reviewed cooldown, runtime, pearl, or diagnostic maps. The maps are private or local, do not escape mutably, are not captured by asynchronous callbacks, and are driven by Bukkit event/lifecycle/main-thread paths. Class-wide and global suppressions were removed. Remaining `PMD.UseConcurrentHashMap` suppressions are attached only to exact declarations/methods whose ownership/order semantics were reviewed. The nested overflow world map uses `LinkedHashMap` and requires no suppression.

### CombatLogX lifecycle

Always-loaded classes remain dependency-neutral. The direct adapter owns direct API references. Disable unregisters listeners, releases API and `ICombatManager` references, clears latches/transient state, and retires the generation. Delayed callbacks are generation-fenced. Compatible re-enable creates one fresh adapter; incompatible re-enable disables only combat integration. Repeated cycles do not duplicate listeners. Plugin disable closes the active adapter once.

### Visual cooldown reload handoff

Successful reload snapshots authoritative cooldowns and MaceGuard-owned overlays, prepares the replacement, adopts ownership, clamps shorter durations, removes stale targets, reconciles online players, and only then shuts down the old runtime without clearing transferred overlays. Failed replacement leaves the old runtime authoritative and reconciles its existing overlays. Exported maps are immutable, and deterministic order is preserved.

### Trident and placeholders

Trident remains a valid location-bound restriction/cooldown target and is rejected for combat carryover in validation/runtime/migration. Documentation does not imply Trident carryover.

The runtime-owned PlaceholderAPI set and README agree on exactly 59 placeholders, including aliases and all nine indexed modifier fields. Item-only disabled output/count exclude effect-only targets such as `SPEAR_LUNGE`; dedicated and general restriction output may include it. Boolean output is lowercase, non-applicable values are empty strings, and no runtime placeholder is undocumented.

### Dependency reproducibility and packaging

Paper API, BlueSlimeCore API, and CombatLogX API resolve to the documented timestamped JARs and exact SHA-256 values. All are `provided` and absent from the shaded JAR. The dependency tree is retained. Compile immutability does not prove production binary compatibility with the deployed CombatLogX/BlueSlimeCore pair; that remains a live-staging requirement.

The candidate and merged-main JAR contain one `plugin.yml`, version `6.1.1`, schema `7`, the correct main class, `api-version: 1.21`, WorldGuard hard dependency, and CombatLogX/PlaceholderAPI soft dependencies. No Paper/Bukkit, CombatLogX, BlueSlimeCore, WorldGuard/WorldEdit, PlaceholderAPI, Floodgate, Essentials, test, source, handoff, or temporary transfer classes/files are packaged. Gson is the intentional shaded library.

## Verification evidence

### Exact reviewed PR head `d0bd89774feb83f12664f5b23f05f7e6911fc250`

- Build run: `31075590692` — success
- Codacy CLI run: `31075590680` — success
- Codacy Cloud: success, zero findings/annotations
- Java: OpenJDK `21.0.11` LTS
- Maven: `3.9.11`
- Main sources: `112`
- Test sources: `68`
- Tests: `375`
- Passed: `375`
- Failures: `0`
- Errors: `0`
- Skipped: `0`
- Maven clean-verify total: `01:08 min`
- Dependency tree: success
- Artifact ID: `8957377975`
- Candidate JAR size: `881,544` bytes
- Candidate JAR SHA-256: `4fb8744eede094300833f0120529b5638bbde91e2c7bf55ea8d26ac7044c55da`

### Merge commit `a6aff2f877debdd16446c8098e6bb7b5072ed6dd`

- Build run: `31076016921`
- Compile/test/dependency/JAR-inspection job: success
- Codacy run: `31076016911` — success
- Tests: `375` passed, `0` failures/errors/skips
- Merged artifact ID: `8957547059`
- Merged JAR size: `881,544` bytes
- Merged JAR SHA-256: `4fb8744eede094300833f0120529b5638bbde91e2c7bf55ea8d26ac7044c55da`
- Merged JAR is byte-identical to the exact reviewed candidate
- Separate `publish-latest` job: failed due to the corrected artifact-path defect described above

### Final main `SELF`

The exact Build, successful automated publish, Codacy, source SHA, final artifact ID, test report, dependency tree, JAR size, SHA-256, and package inspection for `SELF` are the final provenance and supersede the merge-commit workflow's failed publish job. The commit cannot embed its own SHA or descendant workflow/artifact IDs; those immutable values are recorded in GitHub Actions and the final Worker #2 response.

## Review and staging status

- First independent review: complete
- Second independent review: complete
- Final post-repair review: complete
- Unresolved inline review threads: none
- Submitted reviews/requested changes: none
- Live production-equivalent staging performed: no

Still required before production deployment:

- Leaf/Paper 1.21.11 startup, enable, reload, disable
- CombatLogX 11.6.0.0.1286 with production-compatible BlueSlimeCore
- CombatLogX disable/re-enable and repeated lifecycle cycles
- WorldGuard overlap, priority, inheritance, borders, and query failures
- Exact deployed pearl callback order and diagnostic trace
- Real 60-second stasis pearl, normal plus aged, two same-owner, more than 32, simultaneous owners, and live pearl through reload
- Java Elytra start, continued glide, boost, and ordinary fireworks
- Bedrock/Geyser equivalents
- Visual cooldown transfer, rollback, changed limits, removed targets, and disable cleanup

Repository source, tests, packaging, documentation, dependency provenance, checks, and final deliverable are ready. Production deployment remains blocked on the live staging matrix.
