# MaceGuard 6.1.3

This patch closes the post-merge 6.1.2 review findings.

- Bypassed managed cobwebs are still tracked and expire normally.
- Stasis overflow correlation retains independent tick and enforcement segments.
- Block-policy and temporary-cobweb bypass permissions are separate from Warzone administration.
- Cooldown expiry and visual reconciliation use saturation-safe arithmetic, with a 365-day configuration maximum.
- Player denial throttles are keyed by semantic action and cause.
- Existing block-policy and cobweb regression tests are updated for the split permissions and bucket feedback channels so the complete Maven suite can compile.

## Permission migration

The two new permissions default to `false` and are not children of `warzonerotator.admin`:

- `maceguard.block-policy.bypass` bypasses MaceGuard player block placement, breaking, and bucket policy checks. It does not bypass WorldGuard.
- `maceguard.temporary-cobweb.bypass` bypasses Warzone cobweb availability and its cooldown decision. WorldGuard, block policies, temporary-block tracking, and expiry cleanup still apply.

`warzonerotator.bypass` continues to bypass Warzone item, ability, and combat restrictions, but it no longer grants either of the independent permissions above. Grant the new nodes explicitly only where those narrower authorities are intended.
