# Warzone combat and batch modifier verification

## Local evidence

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
