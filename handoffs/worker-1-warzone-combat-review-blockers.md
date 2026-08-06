# Worker #1 handoff: Warzone combat review blockers

## Repository state

- Repository: `wsg138/MaceGuard`
- Starting `main`: `f823ae7041c3072c6e853a0606419646a9bbbb05`
- Branch: `agent/fix-warzone-combat-review-blockers`
- Draft PR: `#18`
- Previous remote head at completion start: `23965fd28662c0b5dccd5cb1e372363c7b491ea6`
- Final branch head: `SELF` — the single repair commit containing this file; resolve it from PR #18.
- Version: `6.1.1`
- Configuration schema: `7`
- PR status: draft, open, and unmerged

The final commit cannot contain its own literal SHA or workflow/artifact IDs created after it. The repository therefore uses `SELF`; exact branch SHA, Build run, Codacy CLI run, Codacy Cloud result, candidate filename, size, SHA-256, and source SHA are recorded in PR #18's final description and Worker #1 completion comment.

## History decision

At completion start, PR #18 still targeted `main`, remained draft/open/unmerged, contained one commit, and still pointed to `23965fd28662c0b5dccd5cb1e372363c7b491ea6`. `main` remained `f823ae7041c3072c6e853a0606419646a9bbbb05`. No contributor commit had replaced the reported branch head. The final tree was rebuilt as one intentional repair commit whose sole parent is the verified `main`; temporary staging commits used by the connector are not part of final history. The destructive ref update is guarded by an immediate head comparison, providing force-with-lease-equivalent protection.

## Defects reproduced and repairs retained

### Legacy migration

Schema 4, 5, and 6 migrations inspect the original legacy modifier node rather than a defaults-merged node. Missing `combat-carryover` becomes `false`; explicit `true` and explicit `false` remain unchanged. Bundled schema-7 defaults cannot leak into legacy built-in or custom modifier definitions, and unrelated custom fields remain preserved. Migration still validates the complete temporary candidate before atomic replacement.

### Exact-one pearl consumption

One `PlayerTeleportEvent` performs one destructive correlation and consumes at most one exact or overflow impact. A teleport already canceled by another plugin consumes its relevant record but receives no MaceGuard enforcement or warning. There is no second destructive canceled-event handler. PDC remains authoritative after cache eviction and reload, launch and impact caps cannot make a marked pearl ordinary, and owner/world overflow is bounded. Ordinary-only ambiguity remains allowed; ordinary-plus-aged or invalid marked ambiguity follows the owner-scoped conservative policy. Correlation is owner/world scoped, preventing cross-world use. A 5,000-tick delay does not create a fail-open path. Missing or malformed marked metadata cannot bypass enforcement.

Authoritative PDC keys remain:

- `maceguard:stasis-pearl-marker` (`BYTE`)
- `maceguard:stasis-pearl-format` (`INTEGER`, format `1`)
- `maceguard:stasis-pearl-owner` (`STRING` UUID)
- `maceguard:stasis-pearl-launched-at` (`LONG` epoch milliseconds)

### Elapsed-time semantics

Same-runtime age uses monotonic nanoseconds. Cache-loss/reload recovery uses persisted epoch milliseconds. Threshold comparison is inclusive. Entity ticks are not authoritative. Backward/future or unreasonable persisted timestamps fail closed, while forward clock movement can only conservatively age recovered pearls.

No deployed Paper/Leaf pearl callback order is claimed as verified. The diagnostic command remains available for live confirmation:

```text
/maceguardpearltrace on <player>
/maceguardpearltrace show <player>
/maceguardpearltrace off <player>
```

### CombatLogX lifecycle

Direct listeners unregister on disable. Integration generations fence stale delayed callbacks. Closed direct adapters release their `ICombatManager` reference. A compatible re-enable creates one fresh adapter and reconciles online players; incompatible re-enable disables only combat integration. No old classloader-owned API object is intentionally retained.

### Visual cooldown reload

The replacement runtime adopts authoritative cooldown expiries and owned visual overlays before old-runtime shutdown. Successful transfer prevents the old runtime from clearing adopted overlays. Failed replacement startup preserves the old runtime and overlays. Removed targets clear, changed limits clamp, and plugin disable clears owned overlays once.

## PMD `UseConcurrentHashMap` review

Three findings are suppressed only at their exact declarations/methods:

1. `VisualCooldownService.owned`, a private insertion-ordered overlay ownership map.
2. The private ordered `desired` map inside `VisualCooldownService.reapply(...)`.
3. The private sorted map inside `WarzoneRuntime.currentCooldownDurations()`.

The ownership claim was reviewed through all production callers:

- Bukkit plugin enable/disable/reload lifecycle calls are synchronous.
- `/warzone reload` executes through Bukkit's command executor on the primary thread.
- restriction and player callbacks are ordinary synchronous Bukkit listeners.
- periodic reconciliation uses `runTaskTimer`, not an asynchronous scheduler API.
- no `CompletableFuture`, async Bukkit/Paper event, database callback, or storage-executor path calls these collections.
- the single storage executor is passed to persistence services, not to `VisualCooldownService` or the runtime duration snapshot.
- maps remain private and are not returned as mutable maps; snapshots are immutable copies.
- insertion/sorted order is used for deterministic overlay reconciliation, ownership transfer/removal, and reload-state construction.

Using `ConcurrentHashMap` would discard those ordering semantics without fixing a real cross-thread access. The final code uses only `@SuppressWarnings("PMD.UseConcurrentHashMap")` at the field or method that owns the finding, with a concise Bukkit-primary-thread explanation. There is no class-wide suppression, no global ruleset exclusion, and no analyzer disablement.

## Dependencies

All compile-time server/integration APIs remain `provided` and unshaded:

- Paper API `1.21.11-R0.1-20251209.165129-1` — SHA-256 `7ec623c368f72a6a7326a2d903397a537b0c4cad86d6f63f65dc77606a29809b`
- BlueSlimeCore API `2.9-20260720.221205-67` — SHA-256 `c98c8fbeecca618c3021ea7d116ef21f5e17632cf8ea235b0905ac6fafa0c33e`
- CombatLogX API `11.6-20251210.005328-47` — SHA-256 `cf57523967ec8778a2164a3fbd2f468efe6797da78c9c0dc125cf3606f35015c`

The POM fixes `project.build.outputTimestamp` for reproducible packaging and enforces Java 21 plus Maven 3.9.x.

## Documentation contract

The README documents exactly 59 runtime placeholders. `%warzone_disabled_items%` and its count exclude effect-only targets such as `SPEAR_LUNGE`; the example is item-only (`Mace, Ender Pearl`). Trident is explicitly location-bound and rejected for combat carryover. Documentation does not claim deployed Paper/Leaf callback ordering or live Java/Bedrock compatibility. Dependency versions/hashes and pearl diagnostic syntax are recorded in repository documentation.

## Verification convention

The last locally reported edited candidate before this completion pass used OpenJDK `21.0.10+7`, Maven wrapper `3.9.11`, 112 main sources, 66 test sources, and passed 371/371 tests with zero failures, errors, or skips. It is not final evidence because it was not the final committed head.

Final evidence must be taken only from exact `SELF`:

- `./mvnw -B clean verify`
- `./mvnw -B dependency:tree`
- exact-source Build workflow
- exact-source Codacy CLI workflow
- Codacy Cloud result for the same SHA
- exact-source `MaceGuard-6.1.1.jar` inspection

The immutable run IDs, conclusions, command logs, source/test counts, test totals, Java/Maven versions, build durations, dependency resolution, warnings, candidate size/hash, and artifact source SHA are published in PR #18. Prior artifact hashes `87c6f157...` and `36b054a9...` are superseded and must not be deployed.

## Exact-head JAR requirements

The final candidate must contain exactly one `plugin.yml`, version `6.1.1`, schema `7`, and main class `com.lincoln.maceguard.MaceGuardPlugin`. It must retain expected runtime resources and descriptor dependencies/soft-dependencies. It must contain no Paper, CombatLogX, BlueSlimeCore, WorldGuard, WorldEdit, or PlaceholderAPI classes; no tests or Java source; no handoff, diagnostic-transfer, patch, or temporary workflow files; and no duplicate descriptors. Exact inspection evidence is in PR #18.

## Mandatory independent Worker #2 review

Perform two independent review passes and exact-head checks before considering merge. Specifically inspect:

1. Whether each PMD suppression is a valid Bukkit-main-thread ownership claim.
2. Hidden asynchronous access to the ordered cooldown maps.
3. Schema 4/5/6 source-node migration detection.
4. Explicit `true`/`false` carryover preservation.
5. One-event/one-impact consumption.
6. Entry-canceled teleport handling.
7. PDC authority after cache eviction and reload.
8. Owner/world overflow accounting.
9. Ordinary-plus-aged ambiguity.
10. Missing/malformed owner and timestamp metadata.
11. 5,000-tick delayed recovery.
12. Monotonic and epoch clock behavior, inclusive comparison, and clock jumps.
13. CombatLogX adapter generation fencing.
14. Release of old `ICombatManager` and other classloader-owned API references.
15. Visual overlay adoption, removed/changed targets, rollback, and disable cleanup.
16. Immutable dependency binary compatibility and resolved hashes.
17. Exact runtime/README consistency for all 59 placeholders.
18. Trident carryover rejection.
19. Exact-head artifact provenance: branch head, workflow checkout SHA, artifact source SHA, PR evidence, and `SELF` must agree.

Do not merge until both review passes and exact-head checks are complete.

## Mandatory live staging still unperformed

Do not treat any of the following as passed:

- Leaf/Paper 1.21.11 startup, reload, and disable
- exact pearl callback ordering
- CombatLogX 11.6.0.0.1286 with deployed BlueSlimeCore
- CombatLogX disable/re-enable and stale callback behavior
- WorldGuard overlap, priority, inheritance, border, absent/query-failure behavior
- Java Elytra start, continuation, rockets, and ordinary fireworks
- Bedrock/Geyser equivalents
- a real 60-second pearl
- live-pearl reload and PDC recovery
- more than 32 live pearls
- simultaneous pearl owners
- visual cooldown transfer, rollback, changed limits, removed targets, and plugin disable

PR #18 must remain draft, open, and unmerged through this handoff.
