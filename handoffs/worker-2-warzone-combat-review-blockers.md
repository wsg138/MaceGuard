# Worker #2 — Warzone combat review blockers

## Scope and provenance

This is an independent final review of PR #18, not a continuation of Worker #1's implementation reasoning.

- Repository: `wsg138/MaceGuard`
- Verified starting `main`: `f823ae7041c3072c6e853a0606419646a9bbbb05`
- Verified initial PR head: `ce693db7bde72fb6f7529bb53527543558907d4e`
- Branch: `agent/fix-warzone-combat-review-blockers`
- Review commit containing this handoff: `SELF`
- Version: `6.1.1`
- Configuration schema: `7`

`CHATGPT_START_HERE.md` and `REQUIREMENTS.md` are not present in the repository. The current and historical handoffs, architecture plan, README, Warzone/deployment/dependency/migration/repair documentation, complete PR diff, tests, workflows, exact-head artifacts, reviews, comments, and checks were inspected through GitHub.

A local Git checkout could not be obtained because the worker runtime could not resolve GitHub over normal network access. GitHub Actions is therefore reported as exact-head CI evidence, not as a separately reproduced local build.

## First independent review findings

### 1. Release blocker — dependency hashes were recorded but not enforced

`.github/workflows/build.yml` printed SHA-256 values for the timestamped Paper, BlueSlimeCore, and CombatLogX API JARs but never compared them with the documented values. A changed artifact at the expected Maven path could still pass.

Repair: require exact hash equality, verify the checked-out source SHA in Build as well as Codacy, preserve `pipefail`, and strengthen forbidden-package/JAR descriptor checks.

### 2. Important correctness defect — impact cleanup could erase enforcement authority

`StasisPearlTracker.cleanup(...)` converted an unusual exact-impact expiry to overflow, but later timer cleanup deleted that overflow. A sufficiently delayed teleport could therefore deterministically outlive both windows and fail open.

Repair: unusual expiry is converted into bounded owner/world count state that remains until one matching event consumes each count or normal owner/runtime lifecycle cleanup clears it. Repeated timer cleanup and a 5,000-tick or longer delay cannot erase enforcement authority.

Tradeoff: when the expected teleport callback never arrives, the conservative count can block one later same-owner/same-world pearl. This is documented and remains a mandatory live-staging observation.

### 3. Release blocker — class-wide PMD suppressions contradicted the review contract

The changed pearl tracker and diagnostic class used class-wide `PMD.UseConcurrentHashMap` or synchronized-method suppressions even though the handoff claimed only narrow declaration/method suppressions.

Repair: remove class-wide suppressions. Suppress only the exact private maps whose ownership/order semantics require it, use explicit private locks for diagnostic state, and retain the previously reviewed ordered-map suppressions in `VisualCooldownService` and `WarzoneRuntime`.

### 4. Important correctness/performance defect — disabled tracing still formatted hot-path details

Pearl launch, impact, and teleport handlers assembled trace strings and formatted locations before diagnostics determined that tracing was disabled.

Repair: diagnostic detail is supplied lazily and evaluated only for an active session. Regression tests prove disabled tracing performs no detail evaluation.

## Worker #2 changes

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
- this handoff and `handoffs/CURRENT.md`

Focused tests added or changed:

- durable overflow authority through repeated cleanup;
- durable overflow authority beyond the signed-integer tick range;
- one matching event consumes the durable overflow count;
- disabled tracing does not evaluate details;
- enabled tracing evaluates and retains details.

## Second independent review

The complete final diff was re-read from the verified `main` merge base after the repairs. The second pass again traced migration source nodes, exact-one correlation, entry-canceled events, PDC recovery, cache/overflow bounds, ambiguity, elapsed-time semantics, PMD ownership, CombatLogX adapter release/generation fencing, visual cooldown transfer/rollback, Trident rejection, the 59-placeholder runtime/documentation set, dependency resolution, and exact-head workflow provenance.

The second pass found a documentation-only mismatch: `docs/WARZONE.md` and `docs/REPAIR-6.1.1.md` still described unusual impact expiry as a short recovery window. Both now document the durable bounded count state and its conservative false-positive tradeoff.

After the reviewed tree was squashed to one commit, Codacy Cloud identified one remaining high-severity `PMD.UseConcurrentHashMap` finding at the nested owner/world overflow-map allocation. The allocation now uses `LinkedHashMap`, preserving deterministic main-thread iteration without adding a broader suppression or changing enforcement behavior. This was a valid static-analysis release blocker.

A final source pass then found a Worker #2 defect: durable recovery used `Integer.MAX_VALUE` as its maximum tick lag. That was sufficient for the required 5,000-tick test but still created a deterministic horizon. The lag representation is now a saturating `long` with `Long.MAX_VALUE` for expired-impact recovery, and an extreme-delay regression test proves correlation beyond the signed-integer range.

No additional source blocker was confirmed after that remediation.

## Conclusions

- Migration: source-node-aware for schema 4, 5, and 6; missing carryover becomes false; explicit true/false and supported fields remain preserved; invalid candidates do not replace the active file.
- Exact-one consumption: one Ender Pearl teleport calls one destructive correlation and consumes no more than one exact or overflow count; entry-canceled events receive no MaceGuard warning or extra cancellation decision.
- PDC authority: stable MaceGuard keys remain authoritative after cache loss/reload; malformed marked metadata fails closed only for the affected owner/event.
- Overflow: launch caches are bounded accelerators; impact overflow is count-bounded per owner/world; neither timer cleanup nor tick advancement erases enforcement authority.
- Ambiguity: ordered owner/world candidates are consumed one at a time; an ambiguity containing an aged/invalid candidate fails closed for the affected event; normal-only ambiguity remains allowed.
- Elapsed time: monotonic nanoseconds in-runtime, persisted epoch fallback after cache loss, inclusive threshold, no `ticksLived()` authority.
- PMD/threading: no hidden asynchronous production caller was found for the ordered cooldown maps or pearl tracker state; class-wide suppressions were removed; nested overflow buckets use deterministic `LinkedHashMap` storage.
- CombatLogX: direct listeners and `ICombatManager` are released on close; retired callbacks are generation-fenced; compatible re-enable creates one fresh adapter.
- Visual cooldown reload: authoritative cooldowns and owned overlays transfer before old ownership is released; failed replacement keeps the old runtime authoritative.
- Dependencies: exact timestamped provided-scope API JARs are now hash-asserted and remain unshaded.
- Placeholders: runtime and README agree on exactly 59; item-only disabled output excludes effect-only `SPEAR_LUNGE`.
- Trident: remains a valid location-bound target and is rejected for combat carryover.
- Workflow provenance: Build and Codacy check out the exact PR head; Build verifies source SHA, dependency hashes, dependency tree, test reports, JAR existence, descriptors, and forbidden packages.

## Verification and operational gate

The immutable exact-`SELF` Build, Codacy, test, dependency-tree, and artifact values are recorded in PR #18 after the final handoff commit completes CI. A commit cannot contain its own SHA or future workflow/artifact IDs.

No production-equivalent Leaf/Paper server, CombatLogX 11.6.0.0.1286 installation, matching BlueSlimeCore, WorldGuard configuration, Java client, or Bedrock/Geyser client was available in this worker environment. The live matrix in `docs/DEPLOYMENT.md` remains unperformed.

Repository verdict before merge: **READY after exact-SELF checks**.

Production verdict: **STAGING REQUIRED**.
