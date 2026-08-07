# MaceGuard 6.1.3

This patch closes the post-merge 6.1.2 review findings.

- Bypassed managed cobwebs are still tracked and expire normally.
- Stasis overflow correlation retains independent tick and enforcement segments.
- Block-policy and temporary-cobweb bypass permissions are separate from Warzone administration.
- Cooldown expiry and visual reconciliation use saturation-safe arithmetic, with a 365-day configuration maximum.
- Full `/maceguard reload` reprojects transferred Bukkit cooldowns against the replacement configuration, including shorter, removed, and disabled Warzone cooldowns.
- Full reload transfers the exact live Warzone rotation/override through an in-memory candidate store and only reconnects persistence after candidate activation.
- Player denial throttles are keyed by semantic action and cause.
- Existing block-policy and cobweb regression tests are updated for the split permissions and bucket feedback channels so the complete Maven suite can compile.

## Permission migration

The two new permissions default to `false` and are not children of `warzonerotator.admin`:

- `maceguard.block-policy.bypass` bypasses MaceGuard player block placement, breaking, and bucket policy checks. It does not bypass WorldGuard.
- `maceguard.temporary-cobweb.bypass` bypasses Warzone cobweb availability and its cooldown decision. WorldGuard, block policies, temporary-block tracking, and expiry cleanup still apply.

`warzonerotator.bypass` continues to bypass Warzone item, ability, and combat restrictions, but it no longer grants either of the independent permissions above. Grant the new nodes explicitly only where those narrower authorities are intended.

## Rollback to 6.1.2

MaceGuard 6.1.2 cannot replay the 6.1.3 `state/temporary-blocks-admission.json` write-ahead journal. Before replacing 6.1.3 with the 6.1.2 JAR, stop cleanly only after that admission journal has reconciled into the primary temporary-block state and is empty. If the journal is non-empty after a crash or timeout, start 6.1.3 so it can recover the admissions into `temporary-blocks.json`. A direct rollback can strand admitted temporary cobwebs until 6.1.3 recovers them.
