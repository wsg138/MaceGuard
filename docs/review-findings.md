# Codacy review cleanup, 2026-10-05

## Scope and source evidence

Reviewed the public Codacy issue pages for PR #46 at `4434cdc` and #47 at
`8750f98`. #46 reports 29 confirmed new findings and 12 potentially new findings;
#47 reports one fixture-length finding. Potential findings are included below
so the combined candidate preserves a complete disposition.

## Confirmed findings (30)

| Count | Finding | Source disposition |
|---|---|---|
| 3 | Concurrent-access map recommendation | Use ConcurrentHashMap for CombatPositionListener denial timestamps, WarzoneCombatBar shown bars, and DirectCombatLogXGateway suppressed preferences. This does not make Bukkit operations safe off the main thread. |
| 2 | Object allocation in test loops | Parameterize BlockPolicyWorldGuardGrantTest material and transformation cases; retain all case inputs. Also parameterize its remaining water-destination loop. |
| 7 | JUnit tests do not directly invoke an assertion | Assert that the existing denial-verification helper completes without assertion failure in WarzoneWaterBucketTest; retain both Mockito absence checks. |
| 5 | Duplicated string literals | Constants for bossbar (adapter and test), /g vault, spawn, and warzonerotator.admin. |
| 3 | Literal comparisons | Name BossBarUpdater, the bossbar setting and GUI batch-review action; also name expansion ENABLED. |
| 1 | Branch as the final statement in a loop | Test the matching expansion in a conditional, preserving first-match enablement behavior. |
| 1 | Field shares a method name | Rename the test updater's stored removed player while preserving its public accessor. |
| 7 | Complexity / parameter limits | Extract fluid transformation checks, boss-bar suppression/rollback, bar update, region geometry, PvP scheduling, and flint eligibility; bundle validated adapter methods/event contracts into records so its constructor has eight parameters. |
| 1 | Lunge test fixture length | Extract runtime, scheduler and player setup; fixture is 45 non-comment lines. |

## Potential findings (12)

| Count | Finding | Source disposition |
|---|---|---|
| 1 | WarzoneModule.validateFiles NPath | Extract region validation without changing error accumulation, order, or dependency warnings. |
| 1 | BlockPolicyListener.onWorldGuardPolicyPlace NPath | Separate bucket and flow delegate grants; preserve explicit-policy and destination checks. |
| 2 | WarzoneGuiManager.modifierClick NPath / cyclomatic complexity | Extract draft review and permission-checked draft toggle. |
| 1 | WarzoneRuntime.startInternal NPath | Extract periodic maintenance; preserve listener registration, cadence and staged recovery gates. |
| 2 | WarzoneGuiManager.openModifiers NPath / length | Extract page population and action hints; preserve selected state, pagination and navigation. |
| 1 | BlockPolicyListener.onFlow cyclomatic complexity | Extract the effective-Warzone boundary check. |
| 1 | WarzoneConfigLoader file length | Move stateless strict mapping/scalar readers into package-private StrictConfigValues; preserve parsing errors and fallbacks. |
| 1 | CobwebListener initial placement cyclomatic complexity | Extract initial water delegate eligibility; preserve item-use veto and one-target scope. |
| 1 | ExplosiveControlListener cart damage cyclomatic complexity | Extract managed-cart eligibility; preserve shooter/vehicle-damage and WorldGuard checks. |
| 1 | ExplosiveControlListener cart placement NPath | Separate rail placement and flint ignition grants. |

## Verification and delivery boundaries

- First hosted refresh at `0bda431` reported two remaining findings: duplicate
  bossbar constants in nested test helpers and a map-constructor warning in the
  extracted scalar reader. Remove nested duplicate constants. For the local,
  unshared parser map, use Java 21's sized LinkedHashMap factory, preserving
  insertion order and YAML nulls; ConcurrentHashMap would change this contract.
  Dedicated regressions verify order, nulls, independent copy and invalid keys.
  No rule suppression is used.

- Existing canonical Java 21 Maven-wrapper verification is the behavioral regression
  guard. Parameterization adds separately reported cases, not new gameplay behavior.
- Final local `mvnw.cmd -B clean verify` passed 634 tests, zero failures/errors/skips.
- Local Lizard 1.24.0 checks of the reported methods/extracted helpers meet the
  reported limits: cyclomatic complexity <= 8, parameters <= 8, fixture/method
  length <= 50. Hosted analyzer versions/settings can differ; its fresh report
  remains the authority for closing the quality gate.
- No quality rules or thresholds were disabled. Build/Codacy pull-request triggers
  include the canonical continuation branch so #47 can receive checks. Fork
  approval and access constraints still apply.
- Changes are delivered through #47 because the signed-in account cannot push
  #46's canonical branch. #46's old head/report remains unchanged until integration.
- No merge, production upload, activation, restart, or live-player acceptance is
  established by this cleanup.
