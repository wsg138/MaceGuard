# Current Handoff

Latest report: `handoffs/player-cooldown-feedback-6.1.2.md`

## Active review status

- Repository: `wsg138/MaceGuard`
- Starting `main`: `70b447c0cb6fe2d4a0d6f9634b6d910c86d6af64`
- Branch: `agent/player-cooldown-feedback-6.1.2`
- Feature commit: `SELF`; resolve the exact branch head from live GitHub.
- Pull request: one draft pull request for the branch; resolve its number and exact-head checks from live GitHub.
- Version: `6.1.2`
- Configuration schema: `7`
- Scope: complete player-facing disabled, active-cooldown, successful-cooldown-start, Elytra/firework, stasis, cobweb, and block-policy feedback with safe visual cooldown ownership.
- Repository verdict: **READY FOR INDEPENDENT REVIEW AFTER EXACT-HEAD CHECKS**.
- Production verdict: **STAGING REQUIRED**.
- Merge status: **DO NOT MERGE**.

## Pre-publication verification

- Java: Temurin `21.0.11`
- Maven: `3.9.11`
- `./mvnw -B clean verify`: success
- Tests: `404` passed; `0` failures; `0` errors; `0` skipped
- Checkstyle, PMD, SpotBugs: passed
- `./mvnw -B dependency:tree`: success
- Candidate JAR: `MaceGuard.jar`, `888,663` bytes
- Candidate SHA-256: `f8340c19390f2d0c6fd9d347ce99badd9481d6d655ca93ea641493963fd45722`

These values describe the reconstructed pre-publication feature tree. Exact final pull-request-head Build, Codacy, and artifact evidence must supersede them.

## Operational gate

Production-equivalent Paper/Leaf `1.21.11`, WorldGuard overlap/priority/inheritance/border/query-failure behavior, CombatLogX Elytra/rocket lifecycle, Java and Bedrock/Geyser feedback, all concrete Spear materials, item preservation under third-party cancellation, visual cooldown ownership/transfer/rollback, reload failure, reconnect, changed/removed limits, foreign cooldowns, and disable cleanup remain unperformed.

Repository readiness does not imply production deployment readiness.

## Prior handoffs

- `handoffs/worker-2-warzone-combat-review-followup.md`
- `handoffs/worker-2-warzone-combat-review-blockers.md`
- `handoffs/worker-1-warzone-combat-review-blockers.md`
- `handoffs/worker-2-warzone-combat-integration.md`
- `handoffs/worker-1-warzone-combat-integration.md`
