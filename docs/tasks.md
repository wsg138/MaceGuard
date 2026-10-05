# SPEAR tasks: Warzone combat and batch modifiers

| ID | Stage | Work | State |
|---|---|---|---|
| MG-WZ-01/02 | spec | Record batch GUI behavior, permissions, and one-apply boundary | Done |
| MG-WZ-03/06 | spec | Record Warzone latch, CombatLogX ownership, and config compatibility | Done |
| MG-WZ-PROVE | prove | Add focused regression evidence for scope, bar, config, and GUI security | Done locally; batch draft needs live UI acceptance |
| MG-WZ-ENGINE | engine | Implement batch GUI, scoped combat policy, and bar | Done locally |
| MG-WZ-ARCH | arch | Review lifecycle, reload, external plugin configuration, and GUI session ownership | Done locally; server integration remains unverified |
| MG-WZ-REFINE | refine | Run focused checks and full clean build; package a versioned test JAR with checksum | Done locally; staging acceptance pending |
| MG-WZ-04A | spec | Specify mutually exclusive CombatLogX and Warzone combat bar ownership | Done |
| MG-WZ-04A-PROVE | prove | Cover suppression before show, failed suppression, and restoration after hide | Done locally |
| MG-WZ-04A-ENGINE | engine | Add optional CombatLogX Boss Bar handoff through the direct gateway | Done locally |
| MG-WZ-04A-ARCH | arch | Review dependency reload, player preference, and bar cleanup lifecycle | Done locally; deployed expansion check pending |
| MG-WZ-04A-REFINE | refine | Run focused and full build, then package an updated test JAR | Done locally; staging acceptance pending |
| MG-WZ-BOUNTY-VERIFY | prove | Verify a valid NotBounties player kill pays its bounty while Warzone combat-tagged, both inside the Warzone and during restriction carryover outside it; confirm WorldGuard claim flag and anti-abuse conditions | SMP Test Warzone `claim-bounties: allow` saved; updated MaceGuard JAR and in-game kill/reward acceptance pending |
| MG-WZ-07 | engine | Block entry into configured Spawn and Market WorldGuard regions for Warzone combat-tagged players, including carryover and Ender Pearls | Implemented locally; live acceptance pending |
| MG-WZ-09 | engine | Refresh existing Warzone combat on successful PvP hits and Ender Pearl launches through CombatLogX's timer API | Implemented locally; live acceptance pending |

The implementation predates this SPEAR record. Existing tests are not claimed as historical red/green evidence. On 2026-10-04 the user authorized a PR and read-only production inspection. Deployment and activation are separate actions.

| ID | Stage | Work | State |
|---|---|---|---|
| MG-PR-01 | prove/refine | Repair the stale CombatLogX test fixture and run the entire canonical Java 21 suite without exclusions | Done locally: 602 tests passed; CI pending |
| MG-PR-02 | prove | Add retag and safe-region entry regression coverage | Done locally; live acceptance pending |
| MG-PR-03 | arch | Inspect production runtime and cumulative PR overlap with #44 | Production test.8 enabled; #44 remains open |

Local EARS/state helpers are absent. Requirements and these task/evidence records provide manual traceability.

| MG-WZ-10/11 | spec/prove/engine/arch/refine | Fix initial WorldGuard water right-click routing and separate flint item-use result | Two regressions reproduced; focused 36 and full 604 tests pass; PR CI and live acceptance pending |
