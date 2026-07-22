# Production migration checklist

Assume every old coordinate, reset mode, timer, sparse baseline, and snapshot is unsafe until independently verified.

## Before maintenance

- Back up every world, the WorldGuard region database, and the complete old MaceGuard directory together.
- Record the running plugin/server/WorldGuard versions.
- Export `/rg info` for the war pit, warzone, market, player plots, duel arenas, KOTH areas, and every intended exclusion.
- Disable old automatic resets before replacing the JAR.

## WorldGuard review

- Verify world identity, exact minimum/maximum points, region type, priority, parents, owners, and members.
- Confirm market/player subregions are buildable through WorldGuard without MaceGuard installed.
- Configure pistons, liquids, explosions, use, containers, and build/break only in WorldGuard.
- Do not recreate broad legacy fallback regions.
- Add only the three documented MaceGuard flags, manually, after review.

## First 3.0.0 start

- Confirm the migration backup/report was created. It is advisory, not proof of migration.
- Install a reviewed version-7 config with no coordinates.
- Leave all reset profile intervals at `0` initially.
- Confirm `/maceguard here` in the market, plot, warzone, war pit, duel arena, and outside all regions.
- Confirm ordinary place/break behavior is identical with MaceGuard enabled and disabled.

## Reset commissioning

- Start with a small disposable WorldGuard cuboid on a staging copy of the production world.
- Capture only with all chunks already loaded.
- Validate, preflight, inspect air-change counts, arm, alter known blocks, preflight again, then use the exact token.
- Verify container inventory restoration and confirm unsupported tile entities refuse capture.
- Restart between capture and arm; confirm it stays disarmed.
- Change geometry/profile/exclusions; confirm it disarms.
- Simulate interruption on staging; verify the journal blocks continuation.
- For sparse profiles, confirm the first player edit is cancelled, the retry succeeds after the durable-write message, and non-player mutation sources are denied by WorldGuard where restoration is required.
- Commission production regions one at a time. Enable intervals only after at least one observed manual cycle.

## Cobweb and combat checks

- Verify WorldGuard denial is never bypassed.
- Verify missing custom flags produce no MaceGuard behavior.
- Verify WarzoneRotator absence disables only TTL tracking, not unrelated building.
- Verify sword hits and item switching never trigger mace armor caps.
- Verify quit, death, reload, and disable clear combat state.

## Rollback readiness

- Retain the synchronized pre-migration world/WorldGuard/plugin backup.
- Never combine an old world with a newer WorldGuard database or reset snapshot.
- If reset results differ from preflight, stop the server, preserve journals, and restore the synchronized backup before investigating.
