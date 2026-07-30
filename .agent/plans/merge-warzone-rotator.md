# Plan: Merge WarzoneRotator into MaceGuard

## Goal

Port the useful WarzoneRotator runtime into Java inside MaceGuard so one MaceGuard JAR owns rotation scheduling, restrictions, cooldowns, commands, messages, state, region integration, PlaceholderAPI, and temporary cobweb policy.

## Current Behavior

- MaceGuard 3.1.0 owns mace controls, WorldGuard-scoped reset behavior, explosives, End restrictions, and persistent temporary blocks.
- MaceGuard currently calls WarzoneRotator reflectively through `WarzoneRotatorAdapter` for cobweb policy.
- WarzoneRotator 2.x independently owns rotation configuration/state, commands, messages, item restrictions, region lookup, placeholders, and a second temporary-cobweb store.

## Proposed Design

- Add a lifecycle-owned `com.lincoln.maceguard.warzone` module under `PluginRuntime`.
- Parse strict, versioned `warzone.yml` and `warzone-messages.yml` into immutable configuration records.
- Model restrictions by target and mode, with exact Bukkit materials plus `SPEAR` and `SPEAR_LUNGE`.
- Keep cooldown and Lunge decision logic in pure Java services; Bukkit listeners only adapt events.
- Preserve the existing `warzonerotator-cobwebs` WorldGuard flag while using MaceGuard's `TemporaryBlockService` as the only temporary-block owner.
- Persist rotation state under `plugins/MaceGuard/state/warzone-state.yml` through one serialized writer.
- Migrate legacy WarzoneRotator files once, without modifying the legacy directory.
- Register one reload-safe PlaceholderAPI expansion.

## Files Likely Affected

- `pom.xml`
- `README.md`
- `src/main/resources/plugin.yml`
- `src/main/resources/config.yml`
- new `src/main/resources/warzone.yml`
- new `src/main/resources/warzone-messages.yml`
- `src/main/java/com/lincoln/maceguard/bootstrap/PluginRuntime.java`
- `src/main/java/com/lincoln/maceguard/MaceGuardPlugin.java`
- `src/main/java/com/lincoln/maceguard/temporary/*`
- `src/main/java/com/lincoln/maceguard/worldguard/*`
- new `src/main/java/com/lincoln/maceguard/warzone/**`
- new `src/test/java/com/lincoln/maceguard/warzone/**`
- remove `src/main/java/com/lincoln/maceguard/integration/WarzoneRotatorAdapter.java`

## Risks

- An event-order mistake could consume restricted items or start cooldowns for cancelled actions.
- Lunge detection could suppress unrelated velocity or ordinary spear behavior.
- Reload could duplicate listeners/tasks/placeholders or lose a valid running timer.
- State migration or overdue-deadline restoration could choose the wrong rotation.
- WorldGuard flag renames could orphan production region data.
- Temporary cobweb cleanup could diverge from reset storage.
- Sync YAML writes could affect TPS or race with shutdown.

## Implementation Steps

- [x] Inspect both current heads and runtime behavior.
- [x] Define configuration, restriction, cooldown, rotation, and state models.
- [x] Implement strict loaders and legacy migration.
- [x] Implement region, restriction, cooldown, Lunge, command, message, placeholder, and scheduler adapters.
- [x] Integrate lifecycle and temporary cobweb ownership.
- [x] Update resources, documentation, dependencies, and version.
- [x] Add focused tests.
- [x] Run review checklist, clean tests/package, JAR inspection, and Git safety checks.

## Validation Steps

Commands:

```powershell
mvn clean test
mvn clean package
jar tf target/*.jar
```

Manual tests:

- [ ] Fresh startup with and without legacy files.
- [ ] All `/warzone` subcommands and permission paths.
- [ ] Invalid `/warzone reload` and `/maceguard reload`.
- [ ] Disabled and cooldown item uses across the region boundary.
- [ ] Spear attacks, throws, inventory switching, disabled Lunge, and cooldown Lunge.
- [ ] Rotation warnings/transitions and offline deadline advancement.
- [ ] Cobweb flags, rotation policy, transition cleanup, reset, and shutdown.
- [ ] PlaceholderAPI present, absent, and after reload.

## Rollback / Recovery Notes

The standalone WarzoneRotator directory remains untouched. Reverting the MaceGuard commit restores the old reflective integration. Migrated state is copied into MaceGuard and legacy files remain available for rollback.

## Progress Log

- 2026-07-29: Confirmed both fetched `origin/main` heads match the supplied SHAs and both worktrees are clean.
- 2026-07-29: Completed the Java integration, 72-test clean build, package build, and JAR dependency/resource inspection.
