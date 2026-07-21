# MaceGuard

[![Build](https://github.com/wsg138/MaceGuard/actions/workflows/build.yml/badge.svg)](https://github.com/wsg138/MaceGuard/actions/workflows/build.yml)
[![Codacy Analysis](https://github.com/wsg138/MaceGuard/actions/workflows/codacy.yml/badge.svg)](https://github.com/wsg138/MaceGuard/actions/workflows/codacy.yml)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/068edee475ee4c18a8f60717b80c8e88)](https://app.codacy.com/gh/wsg138/MaceGuard/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Zone protection, snapshot reset, End access, and End island combat safety plugin.

| Download | Version | Runtime |
| --- | --- | --- |
| [MaceGuard.jar](https://github.com/wsg138/MaceGuard/releases/download/v2.4.0-beta.1/MaceGuard.jar) | `2.4.0-beta.1` prerelease | Java 21, Paper/Leaf 1.21.11 |

## WarzoneRotator integration

Set `cobweb_policy: WARZONE_ROTATOR` and `reset_mode: SPARSE_SNAPSHOT` only on the broad Warzone gameplay zone. MaceGuard remains the build authority and asks WarzoneRotator whether the active meta permits a cobweb; an unavailable or incompatible bridge denies it.

The sparse baseline journals only the first original block state at a changed coordinate, then restores those entries each Sunday at 4:00 AM `America/Indiana/Indianapolis`. `maceguard.permanent-edit` preserves staff edits by removing their prior baseline entry.

Keep WorldGuard `build`, `block-place`, and `block-break` available for the broad Warzone so MaceGuard can decide each action. KOTH regions can keep those deny flags and should set `warzonerotator-cobwebs deny`; they retain item-limit enforcement but cannot place cobwebs or mutate blocks.

## Build

```powershell
./mvnw -B clean verify
```

## Mace armor durability verification

MaceGuard identifies smash attacks from Paper's `DamageSource` (`DamageType.MACE_SMASH`), rather than from the attacker's later hand state. Normal mace attacks use a Paper pre-attack item snapshot, with the current held mace only as a final fallback. Health damage, item swaps, attack cooldown, enchantments, and mace smash behavior are untouched.

To reproduce the former rapid-swap issue, equip a victim with low-durability armor, stand in the plain `warzone` outside higher-priority child zones, and check `/maceguard here`. Start an attack with a sword, rapidly switch to a mace, then perform a high-altitude smash; repeat while immediately switching back to the sword. In both runs, health damage remains vanilla and each equipped armor piece loses no more than `damage_per_armor_piece`. Repeat outside `warzone` to confirm vanilla armor durability. Leaf/Paper attribute-swap configuration is server-specific and is intentionally not changed by MaceGuard.
