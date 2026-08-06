# MaceGuard 6.1.2 player feedback handoff

## Repository state

- Repository: `wsg138/MaceGuard`
- Starting `main`: `70b447c0cb6fe2d4a0d6f9634b6d910c86d6af64`
- Branch: `agent/player-cooldown-feedback-6.1.2`
- Feature commit: `SELF`
- Pull request: one draft pull request for this branch; resolve its number from live GitHub.
- Version: `6.1.2`
- Configuration schema: `7`
- Java: `21`
- Target server: Paper/Leaf `1.21.11`
- Merge status: **DO NOT MERGE — independent review and live staging required**.

## Implemented feedback contract

MaceGuard now explains every player-triggered action it cancels while leaving unrestricted and bypassed actions silent. `WarzoneMessageService` is the central formatter and dispatcher for disabled restrictions, active cooldowns, successful cooldown starts, Elytra starts, actual Elytra boosts, stasis cancellations, temporary cobweb restrictions, and block-policy denials.

- Disabled actions identify the item or ability and current Warzone meta without inventing a countdown or item overlay.
- Active cooldown attempts report authoritative rounded-up remaining time without restarting or lengthening the cooldown.
- Successful actions send one cooldown-start message only after a finalized projectile launch, positive accepted damage, or accepted Lunge velocity.
- Rapid duplicate denial messages are throttled per player and restriction target for approximately one second. The first denial is always delivered and unrelated targets remain independent.
- `warzonerotator.bypass` remains unrestricted, unshaded, and silent.
- Cancellations by another plugin do not start MaceGuard cooldowns or send start messages.
- Dispenser-originated projectiles do not produce player chat.

## Target matrix

| Target | Start trigger | Start/active feedback | Vanilla overlay |
|---|---|---|---|
| Ender Pearl | finalized successful player launch | throw-another wording | concrete `ENDER_PEARL` |
| Wind Charge | finalized successful player launch | use-another wording | concrete `WIND_CHARGE` |
| Mace | confirmed positive direct Mace damage | use-your-Mace wording | concrete `MACE` |
| whole Spear | finalized launch or confirmed positive direct damage | whole-Spear wording | only the actual concrete Spear material used |
| Spear Damage | confirmed positive direct or correlated projectile damage | deal-Spear-damage wording | none |
| Spear Lunge | accepted Lunge velocity | Lunge wording | none |
| Elytra / rocket policy | denied glide start or actual boost | direct policy explanation | none |
| blocks, buckets, cobwebs, stasis | canceled player action | direct policy explanation | none |

The authoritative whole-Spear cooldown remains shared by the `SPEAR` group target. Cooldown state records the concrete successful material solely for safe visual reconciliation; unrelated Spear materials are not shaded. `SPEAR_DAMAGE` and `SPEAR_LUNGE` remain independent effect-only restrictions so ordinary Spear use is not visually or mechanically blocked.

## Overlay ownership

`CooldownService` remains authoritative. Bukkit item cooldowns are a presentation layer only. Concrete material state now survives snapshot/restore and reconciles on reconnect, eligible-scope entry, combat carryover, and successful reload. Removed limits clear only MaceGuard-owned overlays. Failed reload preserves old state. Longer foreign cooldowns are never shortened or cleared, and shutdown cleanup remains ownership-scoped.

## Configuration

`warzone-messages.yml` adds backward-compatible optional defaults for:

- `item-cooldown-started`
- `ability-cooldown-started`
- `block-place-denied`
- `block-break-denied`
- `bucket-use-denied`

Existing templates were clarified for disabled, cooldown-active, cobweb, Elytra, firework, and stasis feedback. Supported placeholders are documented in `docs/WARZONE.md`, including `<item>`, `<ability>`, `<action>`, `<ready_action>`, `<cooldown>`, `<cooldown_remaining>`, `<meta>`, `<meta_id>`, `<modifiers>`, `<time_left>`, `<changes_at>`, `<next_meta>`, `<next_meta_id>`, and `<cobweb_clear_time>`. Existing customized files are not overwritten. Unknown top-level keys remain invalid.

`messages.blocked-message-cooldown` remains schema-7 configuration and defaults to `1s`; no `warzone.yml` format change required a schema bump.

## Verification evidence

Pre-publication exact-tree verification reconstructed this feature from the stated starting commit and ran on Temurin Java `21.0.11` with Maven `3.9.11`:

- `./mvnw -B clean verify`: **BUILD SUCCESS**
- Tests: `404` run; `0` failures; `0` errors; `0` skipped
- Maven Checkstyle: passed
- Maven PMD: passed
- Maven SpotBugs: passed
- `./mvnw -B dependency:tree`: **BUILD SUCCESS**
- Candidate: `MaceGuard.jar`
- Candidate size: `888,663` bytes
- Candidate SHA-256: `f8340c19390f2d0c6fd9d347ce99badd9481d6d655ca93ea641493963fd45722`
- Candidate `plugin.yml` version: `6.1.2`
- Candidate `warzone.yml` schema: `7`
- Exactly one `plugin.yml`; no provided API packages, tests, Java sources, or handoff files were packaged.

Exact final pull-request-head Build, Codacy, and artifact provenance must supersede the pre-publication candidate values before review completion.

## Tests added or expanded

Coverage includes central message rendering, readable duration rounding, first-message and per-target throttling, bypass silence, disabled versus cooldown wording, successful-start timing, canceled-event silence, projectile path deduplication, Pearl and Wind Charge item preservation, Mace positive-damage requirements, whole-Spear concrete-material ownership, effect-only Spear restrictions, Elytra and firework feedback, strict message loading/defaults, reload/snapshot ownership, and placeholder documentation.

## Required live staging

Still required on production-equivalent Paper/Leaf `1.21.11`:

- Java and Bedrock/Geyser confirmation of chat wording and visual shaded cooldowns.
- Pearl, Wind Charge, Mace, every supported Spear material, Spear Damage, and Lunge timing.
- Another-plugin cancellation ordering and item preservation.
- CombatLogX Elytra start/rocket behavior and carryover outside the region.
- Reload success/failure, reconnect, region entry/exit, changed/removed limits, longer foreign cooldown ownership, and plugin-disable cleanup.
- High-rate duplicate-click behavior at normal latency.
- WorldGuard overlap, priority, inheritance, border, and query-failure behavior.

Repository verification is not production deployment approval.
