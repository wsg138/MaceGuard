# MaceGuard 6.1.2 player feedback handoff

## Repository state

- Repository: `wsg138/MaceGuard`
- Starting `main`: `70b447c0cb6fe2d4a0d6f9634b6d910c86d6af64`
- Branch: `agent/player-cooldown-feedback-6.1.2`
- Draft pull request: `#20`
- Initial PR head: `dfffb9f1cd0a1438bb4533bd3bafbf8e5a34df32`
- Repaired implementation head: `49f6baa72b0a49c3c32e893488793f2d35133d16`
- Completion-handoff commit: `SELF`; resolve the exact final branch head from live GitHub.
- Version: `6.1.2`
- Configuration schema: `7`
- Java: `21`
- Target server: Paper/Leaf `1.21.11`
- Pull-request state: **DRAFT, OPEN, UNMERGED — DO NOT MERGE**.

## Implemented feedback contract

MaceGuard explains player-triggered actions it cancels while unrestricted and bypassed actions remain silent. `WarzoneMessageService` centrally formats disabled restrictions, active cooldown attempts, successful cooldown starts, Elytra starts, actual Elytra boosts, stasis cancellations, temporary cobweb restrictions, and block-policy denials.

- Disabled actions identify the item or ability without fabricating a countdown or vanilla cooldown overlay.
- Active cooldown attempts report authoritative rounded-up remaining time without restarting or extending cooldown authority.
- Successful actions send one start message only after a finalized projectile launch, positive accepted damage, or accepted Lunge velocity.
- Rapid duplicate denials are throttled per player and restriction target for approximately one second. The first denial is delivered and unrelated targets remain independent.
- `warzonerotator.bypass`, unrestricted actions, dispenser launches, ordinary fireworks, canceled launches, missing projectiles, missed attacks, and zero-damage attacks do not receive a MaceGuard cooldown-start message.
- MaceGuard-owned projectile cancellation preserves the item; a prior third-party cancellation does not trigger MaceGuard restoration, duplication, or start feedback.

## Target matrix

| Target | Start trigger | Feedback wording | Vanilla overlay |
|---|---|---|---|
| Ender Pearl | finalized successful player launch | throw another Ender Pearl | `ENDER_PEARL` |
| Wind Charge | finalized successful player launch | use another Wind Charge | `WIND_CHARGE` |
| Mace | confirmed positive direct Mace damage | use your Mace again | `MACE` |
| whole Spear | finalized launch or confirmed positive direct damage | use your Spear again | actual concrete successful Spear material only |
| Spear Damage | confirmed positive direct or correlated projectile damage | deal Spear damage again | none |
| Spear Lunge | accepted Lunge velocity | Lunge again | none |
| Elytra / rocket policy | denied glide start or actual boost | direct policy explanation | none |
| blocks, buckets, cobwebs, stasis | canceled player action | direct policy explanation | none |

The whole-Spear cooldown remains authoritative under shared target `SPEAR`. Concrete material is stored only for visual ownership and reconciliation. Switching materials cannot create a second authority or leave stale ownership. `SPEAR_DAMAGE` and `SPEAR_LUNGE` remain effect-only and never shade an entire Spear item.

## Codacy reconciliation and triage

The initial Codacy Cloud check on `dfffb9f1cd0a1438bb4533bd3bafbf8e5a34df32` reported 11 new issues: 2 high and 9 medium. The green Codacy workflow was not treated as sufficient while the Cloud PR report remained red.

| # | Severity / category | Rule | File / line | Why flagged | Validity and production impact | Repair | Regression proof |
|---|---|---|---|---|---|---|---|
| 1 | Medium / Complexity | Cyclomatic complexity | `WarzoneMessageService.java:224` | `feedback` complexity 9 exceeded limit 8 | Valid. Dense branching could hide target-specific wording or duplicate behavior changes. | Split feedback selection into target-kind and material-specific switches. | Existing exact wording tests plus new whole-Spear/generic-material test. |
| 2 | Medium / Complexity | NPath complexity | `WarzoneMessageService.java:224` | `feedback` NPath 256 exceeded 200 | Valid for the same maintainability and event-feedback risk. | Same structured switch refactor. | Exact wording and null-context tests. |
| 3 | Medium / ErrorProne | Avoid literals in conditional statements | `WarzoneMessageService.java:252`, later `:265` after refactor | Duration arithmetic used raw numeric thresholds and a raw zero comparison. | Valid. Hidden units make rounding boundaries easier to break. | Added named duration/zero/one constants and removed the final raw comparison after Codacy re-reported it. | Existing rounding test covers subsecond, singular, decimal, and ten-second output. |
| 4 | Medium / ErrorProne | Field name matching method name | `CooldownService.java:19` | field `active` collided with method `active(...)` | Valid. Ambiguous ownership naming impairs review of authoritative state. | Renamed field to `activeCooldowns`. | Entire cooldown, reload, concrete-material, and visual ownership suites passed. |
| 5 | High / Performance | PMD `UseConcurrentHashMap` | `CooldownService.java:19` | PMD syntactically flagged a non-concurrent `Map`. | False positive. State is owned by Bukkit listeners and scheduled maintenance on the primary server thread; adding concurrent semantics would obscure the lifecycle contract without solving a real race. | Exact field-declaration suppression with primary-thread ownership comment. No collection or authority change. | Full cooldown/reload/reconnect/ownership suites; independent reviewer must verify the lifecycle claim. |
| 6 | High / Performance | PMD `UseConcurrentHashMap` | `CooldownService.java:63` | PMD flagged a method-local `LinkedHashMap`. | False positive. The map is local, deterministic aggregation and never shared. | Exact local-declaration suppression with method-local ownership comment. | Concrete overlay aggregation tests and full suite. |
| 7 | Medium / ErrorProne | Avoid duplicate literals | `WarzoneMessageServiceTest.java:74` | `"MACE"` repeated four times | Valid test maintainability issue; no direct runtime impact. | Added test target constant. | Test suite compiles and passes. |
| 8 | Medium / ErrorProne | Field name matching method name | `WarzoneMessageServiceTest.java:184` | mutable clock field `millis` matched override method `millis()` | Valid test clarity issue. | Renamed field to `currentMillis`. | Throttle and duration tests pass. |
| 9 | Medium / ErrorProne | Avoid duplicate literals | `ItemRestrictionListenerFeedbackTest.java:38` | `"ENDER_PEARL"` repeated seven times | Valid test maintainability issue. | Added Pearl target constant. | Projectile success/cancellation/item-preservation tests pass. |
| 10 | Medium / ErrorProne | Avoid duplicate literals | `ItemRestrictionListenerFeedbackTest.java:111` | `"MACE"` repeated five times | Valid test maintainability issue. | Added Mace target constant. | Positive/zero/disabled Mace tests pass. |
| 11 | Medium / BestPractice | Avoid unused method parameters | `ItemRestrictionListenerFeedbackTest.java:181` | harness helper accepted unused `material` | Valid. Misleading helper signature could conceal test setup assumptions. | Removed the parameter and updated callers. | Listener feedback suite passes. |

No Codacy rule was disabled globally. No class-wide suppression, NOSONAR marker, blanket exclusion, concurrent collection replacement, cooldown-authority change, feedback removal, or console-spam path was introduced.

## Files changed by the remediation pass

- `src/main/java/com/lincoln/maceguard/warzone/message/WarzoneMessageService.java`
- `src/main/java/com/lincoln/maceguard/warzone/restriction/CooldownService.java`
- `src/test/java/com/lincoln/maceguard/warzone/message/WarzoneMessageServiceTest.java`
- `src/test/java/com/lincoln/maceguard/warzone/restriction/ItemRestrictionListenerFeedbackTest.java`
- `handoffs/CURRENT.md`
- `handoffs/player-cooldown-feedback-6.1.2.md`

## Regression tests added in remediation

Two focused tests were added to `WarzoneMessageServiceTest`:

- whole-Spear wording remains distinct from generic concrete-material wording after the complexity refactor;
- null target/material context produces safe generic placeholders rather than throwing.

The feature branch retains or expands coverage for first denial, duplicate throttle, independent target throttle, exactly-once start feedback, canceled launches, projectile path deduplication, MaceGuard item preservation, third-party cancellation, positive and zero Mace damage, concrete whole-Spear overlays, effect-only Spear restrictions, Elytra and ordinary fireworks, block placement/break/bucket feedback, foreign cooldown ownership, reload transfer, reconnect restoration, bypass silence, unrestricted silence, and strict backward-compatible message loading.

## Exact implementation-head verification

Implementation SHA: `49f6baa72b0a49c3c32e893488793f2d35133d16`

- Java: Temurin `21.0.11`
- Maven: `3.9.11`
- Main source count: `112`
- Test source count: `74`
- `./mvnw -B clean verify`: **BUILD SUCCESS**
- Checkstyle: passed
- PMD: passed
- SpotBugs: passed
- Tests: `406` run; `406` passed; `0` failures; `0` errors; `0` skipped
- Build duration: `53.100 s`
- Warnings: `11`, limited to Maven Shade duplicate `META-INF/MANIFEST.MF` and Java 9 `module-info` resource warnings; no static-analysis or test warning failed the build
- `./mvnw -B dependency:tree`: **BUILD SUCCESS** in `5.031 s`
- Build workflow: `31105061314` — success
- Codacy Analysis workflow: `31105061567` — success
- Codacy Cloud check: `92628253600` — success, 0 new issues, 0 high, 0 medium, 0 annotations

## Inspected implementation-head artifact

Downloaded only artifact ID `8969178402` from Build run `31105061314`.

- Workflow artifact name: `MaceGuard`
- Artifact ZIP size: `1,125,191` bytes
- Artifact ZIP SHA-256: `60a54ad3f77cc9fa3d7a8e679f78b1c02b8a0950cde9829463349944646e46d0`
- Plugin filename: `MaceGuard.jar`
- Plugin size: `890,072` bytes
- Plugin SHA-256: `a5cf70107037a97faaaff2ad4ae79b94ae4a19da4f4a28bcd68b8d06408ded6d`
- Artifact source SHA: `49f6baa72b0a49c3c32e893488793f2d35133d16`
- Version: `6.1.2`
- Configuration schema: `7`
- Exactly one `plugin.yml`
- Main: `com.lincoln.maceguard.MaceGuardPlugin`
- Hard dependency: WorldGuard
- Soft dependencies: PlaceholderAPI, CombatLogX
- No provided API implementation classes
- No tests, Java source, handoff files, temporary transport files, or duplicate descriptors

The documentation-only completion commit is represented as `SELF`. Its exact successful Build/Codacy runs and final downloadable artifact are recorded in PR #20 after those live checks complete, because embedding a run ID or the commit's own SHA in this file would create another commit and invalidate the provenance being recorded.

## Message/configuration compatibility

`warzone-messages.yml` adds backward-compatible optional defaults for cooldown-start and block-policy feedback. Existing customized files are not overwritten, omitted new keys use defaults, and unknown top-level keys remain invalid. Documented placeholders include `<item>`, `<ability>`, `<action>`, `<ready_action>`, `<cooldown>`, `<cooldown_remaining>`, `<meta>`, `<meta_id>`, `<modifiers>`, `<time_left>`, `<changes_at>`, `<next_meta>`, `<next_meta_id>`, and `<cobweb_clear_time>` where applicable.

## Independent reviewer requirements

Independently inspect:

1. all eleven Codacy findings and both narrow PMD suppressions;
2. event priority/finalization and duplicate suppression across interact, player-launch, projectile-launch, direct-damage, pre-attack, velocity, and Paper finalized paths;
3. Pearl, Wind Charge, and Spear item preservation when MaceGuard cancels versus a third party canceling first;
4. whole-Spear shared `SPEAR` authority, concrete visual material ownership, material switching, reload, and reconnect;
5. no whole-item overlay for Spear Damage or Lunge;
6. first-denial delivery, one-second per-player/per-target throttling, cleanup, and no throttling of successful start messages;
7. upward duration rounding and configured-total versus authoritative-remaining placeholders;
8. backward-compatible customized message loading and null item/material handling;
9. Java and Geyser/Bedrock chat and vanilla cooldown presentation.

## Required live staging

Still required on production-equivalent Paper/Leaf `1.21.11`:

- Java and Bedrock/Geyser confirmation of chat wording and visual shaded cooldowns.
- Pearl, Wind Charge, Mace, every supported Spear material, Spear Damage, and Lunge timing.
- Another-plugin cancellation ordering and item preservation.
- CombatLogX Elytra start/rocket behavior and carryover outside the region.
- Reload success/failure, reconnect, region entry/exit, changed/removed limits, longer foreign cooldown ownership, and plugin-disable cleanup.
- High-rate duplicate-click behavior at normal latency.
- WorldGuard overlap, priority, inheritance, border, and query-failure behavior.

Repository verification is not production deployment approval. Worker #2 should begin with an independent code review and production-equivalent staging, not a redesign of unrelated Warzone, stasis, migration, CombatLogX, or rotation systems.
