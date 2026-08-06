# MaceGuard 6.1.2 player feedback release notes

Version 6.1.2 keeps configuration schema 7 and targets Java 21 on Paper/Leaf 1.21.11.

## Player-facing behavior

- Every player-triggered MaceGuard cancellation now has clear first-attempt chat feedback.
- Successful Ender Pearl, Wind Charge, Mace, whole-Spear, Spear Damage, and Spear Lunge cooldowns send one action-specific ready-time message only after confirmed success.
- Active cooldown attempts report authoritative rounded-up remaining time without restarting the cooldown.
- Disabled actions explain the current Warzone rule without a fake countdown or visual cooldown.
- Duplicate denial chat is bounded per player and target at a configurable default of one second.
- Elytra starts, actual Elytra boosts, stasis teleports, cobwebs, and block-policy denials retain specialized wording.

## Visual cooldown behavior

- Ender Pearls, Wind Charges, Maces, and concrete material targets receive safe owned Bukkit cooldown overlays.
- A whole-Spear cooldown remains authoritative across the `SPEAR` group, while the overlay follows only the actual Spear material used successfully.
- Spear Damage and Spear Lunge are effect-only targets and never shade the whole Spear.
- Reload, reconnect, scope/carryover changes, removal, and shutdown preserve foreign cooldowns and clear only MaceGuard-owned overlays.

## Configuration

New optional `warzone-messages.yml` keys add item/ability cooldown-start templates and block-policy denial templates. Existing customized files are not overwritten; omitted keys receive safe defaults and unknown keys remain invalid. The `warzone.yml` schema is unchanged at 7.

## Deployment gate

Repository verification does not replace production-equivalent staging. Validate exact Leaf/Paper event order, third-party cancellation, CombatLogX, WorldGuard scope/carryover, Java and Geyser/Bedrock chat/overlays, and foreign cooldown ownership before deployment.
