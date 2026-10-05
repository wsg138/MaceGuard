# Warzone combat and batch modifier verification

## Local evidence

### Codacy findings cleanup, 2026-10-05

- Spec/prove: captured 29 confirmed and 12 potential findings from #46 plus the #47 fixture-length finding. These are static-analysis source findings; no historical gameplay red/green result is claimed. See `review-findings.md` for each disposition.
- Engine/architecture: split eligibility/rendering/validation/setup methods, preserve adapter signatures and suppression rollback, name repeated literals, use concurrent maps for the three flagged caches, and parameterize water test cases. Bukkit operations remain on the server thread. Strict config scalar readers retain the same error paths and defaults.
- Refine: final Java 21 canonical Maven-wrapper `clean verify` passed **634 tests, zero failures/errors/skips**. Local Lizard 1.24.0 checks of reported functions/helpers meet complexity/length/parameter limits; `git diff --check` passed. No lint settings or thresholds were weakened. Project-local EARS/state tooling remains absent.
- Infrastructure: Build/Codacy pull-request branch filters now include #46's canonical continuation branch so #47 can receive hosted checks. Behavioral proof does not apply to this trigger-only change; validation consists of the explicit branch filter and actual hosted dispatch/approval state.
- Delivery remains via #47; direct push permission on #46 is unavailable. Hosted final-head analysis, canonical integration and real player acceptance remain separate. Production was not changed.
- Hosted refine: initial cleanup head `0bda431` dispatched both workflows, but GitHub requires maintainer approval for this fork. Codacy's separate service completed and identified two remaining findings; nested duplicate constants were removed, and the local ordered/null-capable parser map uses Java 21's sized factory. New parsing regressions verify order, nulls, independent copy and invalid keys. Canonical clean verification passes 636 tests, zero failures/errors/skips.

### Wind-charge combat retag, 2026-10-05

- Spec: MG-WZ-13 follows the user's correction: disabled wind charges must never retag Warzone Combat. Only successful enabled launches inside refresh existing Warzone tags; successful outside launches refresh existing ordinary/carried CombatLogX tags. No new combat tags or latches are created by this service.
- Prove: a new outside-launch regression failed behaviorally against the previous handler (12 tests, 1 failure, no errors): CombatLogX retag was never called. A subsequent missing-import error in the expanded fixtures was corrected before final verification.
- Engine/architecture: reuse the existing deferred CombatLogX refresh and its lifecycle/bypass/expiry fences. Capture effective scope at launch, verify inside enablement against the active restriction, and ignore cancelled events and non-player shooters. Register the listener even when Warzone gameplay is disabled for global outside behavior. No new CombatLogX API signatures or operator settings.
- Refine: Java 21 canonical Maven-wrapper `clean verify` passed 618 tests, zero failures/errors/skips; `git diff --check` passed. Existing item-denial tests still pass. Manual SPEAR records are maintained because project-local EARS/state helpers are absent.
- Delivery continues on fork PR #47 targeting canonical #46's branch. Production was not changed. Real player acceptance still needs enabled/disabled/cooldown wind charges inside and ordinary/carried/absent combat outside, including bar duration, runtime reload and dependency disable.

### Lunge combat retag, 2026-10-05

- Spec: MG-WZ-12 refreshes existing combat on permitted eligible Lunge use, with Warzone-only tagging inside and ordinary CombatLogX tagging outside. Source inspection showed the accepted Jab path previously started only its item cooldown and never requested a combat refresh.
- Prove: newly added service regressions initially failed test compilation because the accepted-Lunge entry point and runtime-close fence did not exist. This is missing-interface evidence, not a historical behavioral red run. After implementation, the initial 15 focused tests passed; the final suite adds bypass, duplicate timer, and unready-Jab cases.
- Engine/architecture: reuse existing 1.21.11 Jab recognition and validated CombatLogX retag adapter. No new runtime API signatures or configuration keys. Deferred work checks connection, tag, bypass and runtime lifetime. No Warzone latch is created by the retag service.
- Refine: canonical Java 21 Maven-wrapper `clean verify` passes 613 tests with zero failures/errors/skips. No project-local EARS/state helpers exist; manual requirements/task/evidence records are maintained.
- Current authoritative main `38e4255cf1940c2397da5a4e2cecc6c56498c8b4` was fetched, inspected, and safely merged into the ongoing candidate. GitHub reports #45 closed and #46 as its canonical continuation at the same former head; delivery follows #46. Existing cumulative Codacy findings remain release blockers.
- Production was not changed. Acceptance still requires real Lunge uses inside with enabled/disabled/cooldown modifiers and outside with ordinary/carried/absent combat, verifying CombatLogX duration, bar continuity, bypass and reload behavior on the deployed server.
- Delivery: direct canonical-branch push was denied to the signed-in FainNeito account (403). Follow-up PR #47 targets #46's canonical source branch and is mergeable; it preserves #46 as the combined candidate. Exact gameplay head `18842e3c8d8383e22be9154fcee63848cafa3e26` had no Actions runs, commit statuses or inline review threads at inspection. The Build workflow only triggers for PRs targeting main; CodeRabbit skipped review for a non-default base. Local tests do not replace CI/review of the combined #46 head after integration.

### Water/flint report follow-up, 2026-10-04

- Production inspection remained read-only. Saved Warzone region flags include `water-flow: allow`, `build: deny`, `block-place: deny`, `interact: deny`, and `lighter: deny`; global `block-lighter` is false. The enabled COBWEBS/CARTS definitions and effective-world exclusions are present. Saved settings do not prove an individual player's action path.
- Inspected WorldGuard's runtime source revision `f395a16`, matching production's reported 7.0.19 build. `EventAbstractionListener.handleBlockRightClick` emits a WATER placement delegate from `PlayerInteractEvent` before the bucket-empty event. The old exception covered only the latter. `onPlayerInteract` separately uses block/item results; the old flint gate rejected an item action whenever `isCancelled()` reported block-use DENY.
- Prove: two new routing regressions failed against the previous source (36 focused tests, 2 failures, no errors). A preceding fixture compile error was corrected before that behavioral red run. After the implementation, the focused 36 tests passed.
- Engine/architecture: added the initial water delegate grant, confined to active COBWEBS, one replaceable target, effective Warzone player/target scope, and no explicit policy reference. Flint/TNT-cart interaction checks now honor `useItemInHand() == DENY` rather than the deprecated aggregate cancellation result. Original Bukkit events are never uncancelled by these changes.
- Refine: canonical Java 21 Maven-wrapper `clean verify` passed **604 tests, 0 failures/errors/skips**. Tests also cover protected map destinations, item-use denial, excluded destinations, inactive COBWEBS, and missing explicit policies. `git diff --check` passed. Local unmerged review JAR SHA-256: `764FFDC347DD1C7C53241B32B98BA3D8A43C7E8CF4AE9501FDF450ACFBEB8E7E`; descriptor remains 6.1.8.
- This fixes reproduced source defects; the reported live actions still need acceptance at the affected coordinates after an authorized deployment of reviewed merged source. No production file, flag, runtime, or console command was changed.

### Cumulative PR preparation, 2026-10-04

- Fetched authoritative `wsg138/MaceGuard` main: `e18093c612eb0c7b58c29c229ac83c751fc97309`. The ongoing isolated branch already contains that base; existing work was preserved. PR #44 remains open and overlaps the older water/fire commits included in this cumulative branch.
- Prove: the existing `DirectCombatLogXGatewayTest` suite failed with 7 errors out of 8 tests because the fixture lacked the new retag API. Added test-only enum/method fixtures matching the CombatLogX API and added adapter retag, PvP/projectile/pearl, timer-duplicate, disconnect/expiry, and safe-region movement/teleport coverage. No tests are excluded from the final build.
- Canonical verification: `mvnw.cmd -B clean verify`, Temurin JDK 21.0.12.1, Maven wrapper 3.9.11, pinned Paper API 1.21.11; 602 tests passed, 0 failures/errors/skips. Enforcer remains enabled. There are no additional POM API profiles.
- JAR inspection: one `plugin.yml`, version `6.1.8`, expected entry point/API version; no test fixtures, Bukkit/Paper, WorldGuard, or CombatLogX classes packaged. Pinned Paper dependency SHA-256 matches canonical CI: `7ec623c368f72a6a7326a2d903397a537b0c4cad86d6f63f65dc77606a29809b`. Dependency tree resolution and `git diff --check` passed.
- The review build is unmerged and not a production release artifact. Existing staged test.8 is a separate historical local build; it does not establish acceptance of these added tests or the new canonical PR head.
- Read-only production inspection: test.7 and test.8 are stored under `/plugins/chapter 2 staging`; test.8 is also under `/plugins`. Downloaded `/logs/latest.log` shows `Enabling MaceGuard v6.1.8-water-riptide-test.8` at 09:36:18, CombatLogX integration available, and the integrated Warzone module started with active gameplay scope. Runtime: Paper 26.2-129, WorldGuard 7.0.19, CombatLogX 11.6.0.0.1286, Boss Bar expansion 17.1. No upload, restart, reload, or server command was performed in this inspection.
- SPEAR requirements/task evidence was reviewed manually; local EARS/state helpers are absent. Real player acceptance remains open for exclusive bars, safe-region entry, retag timing, batch GUI behavior, and NotBounties kill/reward claims. An enabled module is startup evidence only.

### Spawn and Market entry restriction, 2026-09-28

- Added configurable `combat.warzone-tag.blocked-region-ids` with `spawn` and `market` defaults. The entry check covers walking and teleportation, including Ender Pearls, for the duration of the Warzone combat latch.
- Maven `compile` and `package` completed with Java release 21 targeting. Packaging used JDK 22 with Maven's JDK 21 enforcer skipped. No tests were run for this change.
- Local test artifact: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-6.1.8-warzone-safe-region-test.3.jar` (1,002,078 bytes). SHA-256: `80F0B58070CA89DBDFBC8A2ABD6DDB3AA01CB6346D618F5FD10ACA73ECD87543`.
- Live WorldGuard region geometry, event ordering, and client behavior remain unverified.

### Boss bar handoff follow-up, 2026-09-28

- The CombatLogX Boss Bar expansion source inspected for compatibility was `SirBlobman/CombatLogX` `main`: `BossBarUpdater` uses the cached per-player `bossbar` setting and exposes `remove(Player)` through its timer updater. The installed server expansion binary was not inspected.
- Remote Desktop Commander focused run: 26 tests passed for bar ownership, direct and managed gateway, and combat scope. The first targeted red run confirmed that MaceGuard previously showed its bar without suppressing CombatLogX's bar.
- Remote Desktop Commander final `clean verify`: 551 tests passed, 0 failures, 0 errors, 0 skipped. JDK 22 was used with Java release 21 and `-Denforcer.skip=true`; JDK 21 enforcement remains unverified.
- Shaded test artifact: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-6.1.8-warzone-bossbar-handoff-test.2.jar` (999,500 bytes). SHA-256: `7B85558DD2FC5D393BD934736F702E630FE9B13A7F160D0D08995573A590C55E`.
- Live Paper/Leaf and client testing remains open. In particular, confirm the deployed CombatLogX Boss Bar expansion's updater identity, no duplicate bars through handoffs, and preference restoration after death, quit, reload, and dependency disable. An abrupt process crash while another CombatLogX operation has saved the temporary suppressed setting is not covered by the normal restoration path.

### Prior Warzone combat and batch modifier build

Source branch: `codex/warzone-combat-batch-modifiers`, based on `9ea6ad5` (local follow-up tests and SPEAR records added afterward). The implementation predates this SPEAR record, so existing tests are not historical red/green proof.

- Remote Desktop Commander device `Entity` ran Maven 3.9.11 with JDK 22 and Java release 21. Maven enforcer was skipped because the installed JDK is outside the POM's `[21,22)` build contract; JDK 21 verification remains open.
- Focused run: `CombatScopeServiceTest`, `CombatElytraPolicyTest`, `WarzoneConfigLoaderTest`, `WarzoneControlConfigLoaderTest`, and `WarzoneGuiSecurityTest`: 52 passed. A new `WarzoneCombatBarTest` passed in the full run.
- Full `clean verify`: 547 tests passed, 0 failures, 0 errors, 0 skipped; shaded JAR built successfully. The first runs exposed CRLF-sensitive test fixtures and a stale global-combat Elytra expectation; both were updated and the final clean run passed.
- Test artifact: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-6.1.8-warzone-combat-batch-test.1.jar` (994,619 bytes). SHA-256: `CECE52BB0E3DBDAAE46527CA93AF5D93FFCC447AE2D224370AEDF26453451DC7`.
- EARS requirements MG-WZ-01 through MG-WZ-06 were reviewed manually; this repository has no project-local SPEAR validator. No live Paper/Leaf or client acceptance was performed.

## Staging acceptance still required

- Confirm only one combat boss bar exists at each handoff between ordinary CombatLogX combat and Warzone combat. Include combat retags, region exit, untag, death, quit, reload, dependency disable/re-enable, a player with CombatLogX's boss bar turned off, and an interrupted handoff. Inspect the installed Boss Bar expansion version and player preference after the test.

- With CombatLogX tagged inside the effective combat-zone flag, confirm the Warzone bar appears with red fill and orange title, counts down on the CombatLogX timer, survives region exit, and disappears on untag, death, quit, dependency disable, and reload.
- Confirm ordinary combat outside the flag permits Elytra starts, boosts, Riptide, `/tpa`, `/home`, `/spawn`, and portals after the documented CombatLogX settings are applied. Confirm Warzone combat blocks configured actions after exit while carryover is enabled.
- Confirm Ender Pearl teleports retain the separate age and stasis policy, and that another plugin's canceled event remains canceled.
- On SMP Test, the `world/warzone` WorldGuard region has explicit `claim-bounties: allow` for NotBounties 1.22.37. Stage a bounty on a target and have a distinct player claim it with a valid kill while the killer has the Warzone combat bar, then repeat during carryover after the killer leaves the Warzone. Confirm the claim broadcast, balance or item payout, one deduction from the target's bounty, and no duplicate reward. Use accounts with distinct IPs because the installed NotBounties config has `same-ip-claim: false`; its world filter excludes `SafeWorld`. Check NotBounties debug/log output if the claim fails.
- This flag was confirmed in the saved WorldGuard `regions.yml` after refresh on 2026-09-28. SMP Test still had the older `plugins/MaceGuard.jar` (about 985.81 kB) and zero players online at inspection, so this is a configuration check, not a claim acceptance result.
- While Warzone combat-tagged, try walking and pearling into Spawn and Market from outside each region, including after leaving the Warzone. Confirm entry is denied, movement within and out of either region is allowed, the block ends on untag, and ordinary CombatLogX combat and `warzonerotator.bypass` remain unaffected. Repeat after reload and confirm `/warzone validate` reports a missing configured region ID.
- In the modifier GUI, select and deselect several items across pages, preview additions/removals, cancel without changing the live set, then apply once with each supported duration. Exercise conflicts, disabled modifiers, permission changes, count limits, kit detachment, and clear-all.
- Compare Java and Bedrock clients on the target Paper/Leaf build. A local test JAR remains a staging artifact until these checks pass.
