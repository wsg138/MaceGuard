# MaceGuard 6.1.2 player feedback release notes

Version 6.1.2 keeps configuration schema 7 and targets Java 21 on Paper/Leaf 1.21.11.

## Player-facing behavior

- Every player-triggered MaceGuard cancellation has clear first-attempt chat feedback.
- Successful Ender Pearl, Wind Charge, Mace, whole-Spear, Spear Damage, and Spear Lunge cooldowns send one action-specific ready-time message only after confirmed success.
- Active cooldown attempts report authoritative rounded-up remaining time without restarting the cooldown.
- Disabled actions explain the current Warzone rule without a fake countdown or visual cooldown.
- Duplicate denial chat is bounded per player and semantic target at a configurable default of one second.
- Ordinary Ender Pearl cooldown and stasis-pearl denials use distinct throttle channels, so one cause cannot hide the other's first explanation.
- Elytra starts, actual Elytra boosts, stasis teleports, cobwebs, and block-policy denials retain specialized wording.
- `warzonerotator.bypass` remains uncanceled and silent across item, block, bucket, and policy-cobweb paths.

## Visual cooldown behavior

- Ender Pearls, Wind Charges, Maces, and concrete material targets receive safe owned Bukkit cooldown overlays.
- A whole-Spear cooldown remains authoritative across the `SPEAR` group, while the overlay follows only the actual Spear material used successfully.
- Every concrete Spear material exposed by the target Paper API is covered by shared-authority and concrete-overlay tests.
- Spear Damage and Spear Lunge are effect-only targets and never shade the whole Spear.
- Reload, reconnect, scope/carryover changes, removal, and shutdown preserve foreign cooldowns and clear only MaceGuard-owned overlays.

## Reliability hardening from independent review

Worker #2 performed two complete source-review passes and repaired:

- feedback suppression after a backward wall-clock change;
- missing bypass handling in player block, bucket, and policy-cobweb paths;
- unsafe null policy-material feedback context;
- overflow in extreme positive duration formatting;
- incomplete concrete-Spear API coverage;
- stasis/ordinary Pearl throttle-key collision.

The original eleven Codacy findings and both narrow PMD suppressions were independently rechecked. No broad suppression, hidden asynchronous access to cooldown authority, or weakened assertion was accepted.

## Configuration

New optional `warzone-messages.yml` keys add item/ability cooldown-start templates and block-policy denial templates. Existing customized files are not overwritten; omitted keys receive safe defaults and unknown keys remain invalid. The `warzone.yml` schema is unchanged at 7.

## Deployment gate

Repository verification does not replace production-equivalent staging. Validate exact Leaf/Paper event order, third-party cancellation, CombatLogX/BlueSlimeCore, WorldGuard scope/carryover, Java and Geyser/Bedrock chat/overlays, every concrete Spear material, foreign cooldown ownership, reload rollback, reconnect, shutdown cleanup, and repeated-input behavior before deployment.
