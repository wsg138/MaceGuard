# MaceGuard — Enthusia SMP Player Guide

This file documents the **current player-facing MaceGuard behavior on Enthusia SMP**. `README.md` and `docs/WARZONE.md` remain the deeper implementation/configuration references; this file is intended to be the source for player documentation and future wiki updates.

The values below were checked against the live Enthusia server configuration on August 22, 2026. Dynamic selections can change according to the Warzone schedule, so use `/warzone` in game for the currently active rules.

## Warzone scope

MaceGuard's rotating Warzone rules apply to the WorldGuard region named `warzone`, with **spawn** and **market** excluded from the effective scope.

That means entering spawn or market does not make the surrounding Warzone modifier set apply there merely because those areas overlap the larger Warzone region.

Useful player commands:

```text
/warzone
/warzone info
/warzone modifiers
/warzone modifier list
/warzone kit
/warzone kits
/warzone items
/warzone next
/warzone schedule
/warzone menu
/warzone help
```

These commands let players inspect the current rules, active kit/modifiers, item restrictions, and upcoming scheduled change.

## Weekly rotation

The automatic schedule changes **once per week at 12:00 PM in the `America/Indiana/Indianapolis` timezone** and repeats this four-week cycle:

1. **SMP kit**
2. **Random modifiers**
3. **Vanilla kit**
4. **Random modifiers**

Then the cycle repeats.

Global warnings are configured for:

- 1 hour before the change
- 30 minutes
- 10 minutes
- 5 minutes
- 1 minute

Random weeks choose **1–3 mutually compatible modifiers**. The configured modifier-count chances are:

| Number of modifiers | Weight/chance share |
| --- | ---: |
| 1 | 35% |
| 2 | 45% |
| 3 | 20% |

The selector prevents an identical random set from immediately repeating and rejects combinations whose restrictions conflict.

## Fixed kits

### SMP

The **SMP** kit is the standard mixed-combat/utility ruleset:

- temporary cobwebs enabled;
- Wind Charges: **5-second cooldown**;
- Ender Pearls: **5-second cooldown**;
- Spear Lunge: **5-second cooldown**;
- Maces: **disabled**.

### Mace Nerf

The **Mace Nerf** kit keeps mace combat available but limits it:

- successful Mace attacks: **10-second cooldown**;
- Spear Lunge: **5-second cooldown**.

### Spear

The **Spear** kit focuses on spear combat:

- successful Spear damage: **10-second cooldown**;
- Spear Lunge: **10-second cooldown**.

The damage cooldown starts from successful spear damage, not merely from throwing a spear.

### Vanilla

The **Vanilla** kit has no MaceGuard Warzone modifiers. Normal vanilla/server rules apply.

## Possible random modifiers

Random weeks draw from the enabled modifiers below while respecting conflict rules.

| Modifier | Player effect | Random weight |
| --- | --- | ---: |
| Cobwebs | Temporary cobweb placement enabled | 10 |
| No Lunge | Spear Lunge disabled; ordinary spear use remains possible | 8 |
| 5s Lunge Cooldown | Successful Lunge receives 5s cooldown | 7 |
| 10s Lunge Cooldown | Successful Lunge receives 10s cooldown | 5 |
| No Spears | Spear launches and spear damage disabled | 4 |
| 5s Spear Damage Cooldown | Successful spear damage receives 5s cooldown | 6 |
| 10s Spear Damage Cooldown | Successful spear damage receives 10s cooldown | 4 |
| No Maces | Mace use disabled | 4 |
| Mace Cooldown | Successful Mace attack receives 10s cooldown | 8 |
| No Ender Pearls | Ender Pearls disabled | 3 |
| 5s Pearl Cooldown | Successful Ender Pearl receives 5s cooldown | 9 |
| 10s Pearl Cooldown | Successful Ender Pearl receives 10s cooldown | 6 |
| No Wind Charges | Wind Charges disabled | 3 |
| 5s Wind Charge Cooldown | Successful Wind Charge receives 5s cooldown | 9 |
| 10s Wind Charge Cooldown | Successful Wind Charge receives 10s cooldown | 6 |
| Elytra, No Rockets | Elytra gliding allowed but actual firework boosting blocked | 1 |

Only compatible modifiers can exist together. For example, the random selector will not simultaneously choose two different Ender Pearl modes or both a disabled Mace and a Mace cooldown.

## How cooldowns work

MaceGuard cooldowns begin from the relevant **successful action**, not simply from an attempted click.

Examples:

- an Ender Pearl cooldown starts after a successful throw;
- a Mace cooldown starts after a successful Mace attack;
- a Spear damage cooldown starts after accepted positive melee or correlated thrown-spear damage;
- a Lunge cooldown starts after the Lunge movement is actually accepted.

Canceled actions, misses, and irrelevant attempts do not start a fake cooldown.

When an action is blocked, MaceGuard tells the player why and reports the remaining cooldown. For item-based cooldowns it can also use Minecraft's shaded item cooldown as a visual indicator, but the server-side MaceGuard timer is authoritative.

## Temporary Warzone cobwebs

When the **Cobwebs** modifier is active, temporary cobweb placement is available in the effective Warzone scope.

On Enthusia:

- Warzone cobwebs last **60 seconds**;
- tracked temporary cobwebs are cleared when their lifetime expires;
- they are also configured to clear when the active Warzone meta changes;
- they clear if the feature/plugin is disabled rather than being left behind permanently.

This is separate from ordinary permanent cobwebs outside the managed Warzone behavior.

## Combat carryover after leaving the Warzone

Some combat restrictions can follow a combat-tagged player briefly after they leave the Warzone. This prevents stepping across the boundary from instantly escaping certain combat rules.

The current configuration enables combat carryover for:

- 5s and 10s Spear Lunge cooldowns;
- No Spears;
- 5s and 10s Spear damage cooldowns.

The current configuration does **not** carry the Cobweb, Mace, Ender Pearl, Wind Charge, or Elytra modifiers outside the Warzone.

Carryover only applies through MaceGuard's CombatLogX/WorldGuard combat-scope system while the relevant combat tag/latch remains active; it is not a permanent restriction after leaving the region.

## Elytra behavior during combat

MaceGuard has combat-aware Elytra rules in addition to the rotating modifier system.

A combat-tagged player cannot normally begin gliding under the Warzone combat policy. A player who was already gliding when tagged is not forcibly knocked out of the air.

When **Elytra, No Rockets** is the active applicable effect, gliding may be allowed but actual firework boosting is blocked. Ordinary firework use is not treated as a fake item cooldown; the plugin blocks the boost itself and explains the denial.

## End Island weapon restriction

This rule is **not part of the weekly Warzone rotation**.

Within a **1,024-block radius of the center of the main End island**, Enthusia currently blocks:

- **Maces**
- **Spears**

For Spears, the restriction covers ordinary spear interaction/use, launches, spear damage, and Spear Lunge movement. The restriction applies only in the End environment and only inside the configured radius.

## Mace armor-durability protection

MaceGuard also contains a WorldGuard-scoped armor durability rule for mace hits. Where that WorldGuard rule is enabled, Mace-related armor durability damage is capped at **2 durability per equipped armor piece per qualifying Mace hit**.

This changes armor item durability only; it does not directly rewrite player health damage, knockback, or vanilla Mace combat mechanics.

Because this feature is WorldGuard-scoped, the exact places where the durability rule is active should be sourced from the server's current WorldGuard region configuration rather than assumed globally.

## Special build/reset areas

MaceGuard also backs several region-specific environmental policies used by the SMP, including the War Pit, Cobweb Box, and Warzone environment reset system.

The configured **Cobweb Box** policy permits only:

- placing/breaking Cobwebs and Ice;
- filling/emptying Water buckets;

and confines liquid behavior to the region while blocking infinite-water-source creation through that policy. Automated/non-player block sources are not allowed by the policy.

The War Pit and configured arena/environment regions can be restored from server snapshots on schedules. These reset systems exist to return managed PvP areas to their intended state; they are not player rollback commands.

## Staff overrides

Staff can temporarily override the automatic Warzone selection for one hour, until the next scheduled change, or until manually cleared. An override changes the active gameplay rules but **does not pause or shift the underlying weekly schedule**.

For players, `/warzone info`, `/warzone next`, and the Warzone GUI expose the relevant current/next state so an override does not need to be guessed.

## Related technical documentation

For implementation details, validation rules, placeholders, stasis-pearl handling, WorldGuard flags, persistence, and administrative commands, see:

- [`README.md`](README.md)
- [`docs/WARZONE.md`](docs/WARZONE.md)
- [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md)
