# Worker #2 — Post-merge final-review follow-up

## Why this follow-up exists

PR #18 was auto-merged at reviewed head `d0bd89774feb83f12664f5b23f05f7e6911fc250` while Worker #2 was publishing the final independent review state. Once merged, PR #18 was technically inaccessible for further repairs. This follow-up is limited to two confirmed defects that remained after merge; it is not a replacement implementation.

- Corrective base: `cf7cfbf9b0a54e0070a75bdbcb39b83445841df4`
- Corrective branch: `agent/fix-worker2-final-review-followup`
- Corrective commit containing this handoff: `SELF`
- Version: `6.1.1`
- Configuration schema: `7`

The corrective base includes the post-merge automated-publish path repair and final PR #18 provenance documentation.

## Confirmed defects

### Important correctness — replacement runtime authority after old cleanup failure

Merged `WarzoneModule.reload(...)` started the replacement and then called `old.shutdown(false)` before assigning `runtime = replacement`. If old shutdown threw, the replacement continued running while the module still referenced the old runtime.

Repair: the old runtime relinquishes transferred cooldown ownership, then the replacement becomes authoritative before any old-runtime cleanup. Old shutdown and conditional old cobweb cleanup are each exception-contained and logged as requiring a full restart; neither can restore old authority or abort replacement reconciliation. `WarzoneModuleReloadHandoffTest` forces both failures and verifies replacement authority and visual reconciliation.

### PMD contract — additional tracker suppressions remained

Merged `StasisPearlTracker` retained three field-level `PMD.UseConcurrentHashMap` suppressions beyond the three PR #18 ordered cooldown-map suppressions specifically required by the review prompt.

Repair: tracker lookup maps and nested owner/world buckets now use `ConcurrentHashMap`. Per-owner impact ordering remains in `ArrayDeque` and explicit sequence numbers, so map iteration order is not part of enforcement. Cleanup uses conditional map removal instead of unsupported iterator removal. The three specifically reviewed cooldown suppressions remain unchanged and justified: `VisualCooldownService.owned`, `VisualCooldownService.reapply(...)`, and `WarzoneRuntime.currentCooldownDurations()`.

The merged `Long.MAX_VALUE` extreme tick-delay recovery fix and its regression test are preserved.

## Verification gate

The exact `SELF` Build, Codacy CLI, Codacy Cloud, dependency tree, test totals, and JAR provenance must pass before corrective merge. Live Leaf/Paper, CombatLogX, BlueSlimeCore, WorldGuard, Java, and Bedrock/Geyser staging remains unperformed and mandatory before production deployment.

Repository verdict before corrective merge: **READY AFTER EXACT-SELF CHECKS**.

Production verdict: **STAGING REQUIRED**.
