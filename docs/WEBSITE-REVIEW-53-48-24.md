# Website export review, 13 October 2026

## Spec and proof

PR 48 at 1380a10 has nine Codacy annotations: three mutable snapshot map
builders, five unmanaged worker-thread references, and one repeated test string.
PR 53 and Duels PR 24 have no current Codacy findings. Plugin Actions await
maintainer approval; this is not a test failure or a successful hosted test.

Acceptance: use Paper-owned asynchronous scheduling for database reads; never
overlap reads, even if a delivery future times out; close prevents new reads and
delivery. Snapshot maps remain immutable. Preserve unavailable versus empty duel
results, UTC week boundaries, signed payloads, default-disabled configuration,
and main-thread rotation capture. No production activation is authorized.

Prove the read lifecycle with a controlled scheduler, including timeout,
cancellation, scheduling failure and shutdown. Existing payload and signed ingest
tests cover the unchanged integration contract. Do not fabricate a historical
red test for analyzer-only refactors. Record completed verification below.

## Engine, architecture and refine

Paper owns asynchronous read tasks; a synchronized lifecycle gate bounds queued
and running reads independently of future timeout/cancellation. Game state and
plugin discovery remain synchronous, JDBC and HTTP remain asynchronous, and
public payloads are immutable. No permissions/configuration defaults change.

Clean Java 21 offline Maven verify: 544 tests, zero failures/errors/skips.
Five lifecycle tests exercise running-read timeout, queued cancellation,
shutdown before/during work, scheduler rejection and read failure/retry.
Existing snapshot tests additionally assert immutable root/child maps.
Native H2 Duels winners -> actual MaceGuard HMAC -> website SQLite ingest ->
public Warzone feed passes, including replay denial, with synthetic records.

This checkout has no project EARS validator or SPEAR state helper; this document
records the scaled requirements and evidence. No runtime delivery, production
activation or actual player match is claimed.

Hosted Codacy at 360c73f confirms the original nine annotations are gone; three
new method-level synchronization style findings were then repaired with explicit
monitor blocks preserving the same lifecycle semantics. Re-run the lifecycle
and full verification checks and inspect the exact updated hosted head.
