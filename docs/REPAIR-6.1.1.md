# MaceGuard 6.1.1 combat repair contract

This bug-fix release keeps Warzone configuration schema 7.

## Stasis pearl authority

Every launched Ender Pearl is marked in its entity persistent data container with these MaceGuard-owned keys:

```text
maceguard:stasis-pearl-marker       BYTE = 1
maceguard:stasis-pearl-format       INTEGER = 1
maceguard:stasis-pearl-owner        STRING = owner UUID
maceguard:stasis-pearl-launched-at  LONG = launch epoch milliseconds
```

The PDC is authoritative. In-memory launch records are bounded accelerators that supply monotonic elapsed time while available. Cache eviction, cleanup, runtime reload, or server restart does not remove entity PDC. On impact, missing cache state is recovered from PDC and uses elapsed wall-clock time. Entity ticks are never the configured duration authority.

Within one runtime, age is `System.nanoTime()` elapsed time. After cache loss or reload, age is `current epoch milliseconds - persisted launch epoch`. A launch timestamp later than the current wall clock, a non-positive timestamp, unsupported format, malformed owner, owner mismatch, or unreasonably old timestamp is invalid and fails closed for the affected owner/event. This is the conservative fallback for a backward wall-clock jump after cache loss or reload. Diagnostics are rate-limited.

## Correlation and failure policy

The Paper API supplies exact projectile identity to `ProjectileHitEvent`, while `PlayerTeleportEvent` supplies only the player, cause, and locations. MaceGuard therefore stores exact projectile UUIDs in a strict per-owner impact queue in callback order. One teleport event performs one destructive correlation and consumes no more than one impact record. The current/next-tick ordering window is an implementation assumption that still requires confirmation on the exact deployed Paper/Leaf build; the bounded staging trace exists for that purpose.

Ordinary exact and capacity-overflow correlation requires the same owner, world, and current/next server tick. Teleport destination distance is diagnostic only because another plugin may modify the destination. When multiple same-owner candidates are possible, the oldest queued record is consumed. If any candidate in that security-relevant ambiguous set is aged or invalid, the affected teleport fails closed; remaining candidates stay queued for subsequent teleports.

The exact impact queue is bounded to eight records per owner. Additional marked impacts enter count-based owner/world overflow state with one bucket per affected world, so memory does not grow per overflowed impact and cross-world correlation remains impossible. Any exact marked impact that outlives the expected ordinary correlation window is converted into the same bounded owner/world count state rather than silently disappearing. Timer cleanup never deletes that enforcement authority: one count remains until one matching owner/world pearl teleport consumes it or normal lifecycle cleanup clears the affected owner. Its tick-lag bound is intentionally saturated, so low TPS, a stalled tick counter, or repeated cleanup cannot create a deterministic bypass. This conservative recovery can block one later same-owner/same-world pearl when the expected teleport callback never arrives; live staging must measure that tradeoff on the deployed server. Launch-cache limits of 32 per owner and 4,096 globally never remove PDC authority.

MaceGuard does not globally block pearls because of an unrelated tracker error. Failure handling is scoped to a marked pearl's owner/world/event. An unmarked ordinary pearl is not converted into a tracked pearl.

## CombatLogX lifecycle

A dependency-neutral listener owns the current adapter generation. CombatLogX disable closes the direct adapter, unregisters its listeners, drops API/classloader-owned references, clears latches and transient pearl caches, and leaves unrelated MaceGuard systems running. Compatible enable constructs a fresh adapter, registers it exactly once, and reconciles online tagged players. Delayed callbacks from retired generations are ignored. Incompatible enable leaves combat integration unavailable with one concise reason.

Full server restart remains the supported deployment path. The lifecycle handling is safe failure/recovery, not a claim that arbitrary plugin-manager reload tools are supported.

## Reload cooldown handoff

`/warzone reload` snapshots authoritative cooldown expirations and MaceGuard-owned visual overlays. A validated replacement adopts the snapshot, clamps retained cooldowns to any shorter new configured duration, drops removed targets, starts, and reconciles online players. The old runtime then relinquishes ownership without clearing adopted overlays. If replacement construction or startup fails, the replacement relinquishes its tentative bookkeeping and the old runtime reconciles its unchanged state.

Plugin disable still clears only overlays owned by the active MaceGuard runtime.

## Trident decision

Trident restrictions remain valid location-bound material restrictions and cooldown targets, but Trident is not eligible for combat carryover. Mace, Ender Pearl, Wind Charge, and Spear targets retain their documented eligibility.

## Pearl event-order diagnostic

The diagnostic is disabled by default and requires `warzonerotator.command.debug`:

```text
/maceguardpearltrace on <player>
/maceguardpearltrace show <player>
/maceguardpearltrace off <player>
```

A session lasts at most ten minutes, retains at most 128 records, supports at most ten simultaneous traced players, and tracks only the explicitly selected online player. It records launch UUID/owner/time, impact tick/world/position, teleport cause/destination/entry cancellation, selected UUID, candidate count, age source, ambiguity/overflow, and final MaceGuard cancellation. Diagnostic detail strings are created lazily only while a session for that player is active, so the disabled hot path performs no trace formatting.

## Build provenance

The exact-head Build workflow checks out and verifies the intended source SHA, requires a JAR, records the dependency tree, and compares the resolved Paper, BlueSlimeCore, and CombatLogX API JAR SHA-256 values against the documented immutable values. Printing a hash without matching it is not accepted as provenance. JAR inspection rejects server/API packages, test classes, source files, handoff files, and duplicate plugin descriptors.

## Mandatory live staging

Automated tests do not prove the exact deployed Leaf/Geyser behavior. Before production, run the checklist in `docs/DEPLOYMENT.md`, including a real 60-second pearl, same-owner normal/aged pairs, more than 32 live pearls, reload with a live pearl, CombatLogX disable/re-enable, Java and Bedrock Elytra start/continued glide/boost/ordinary firework, and visual cooldown continuity during `/warzone reload`.
