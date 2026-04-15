# MaceGuard Testing Checklist

## Status Summary
- Current focus: release candidate for live testing, not a broad architecture change.
- Core behaviors covered by this pass:
  - protected fallback regions from `zones`
  - highest-priority gameplay zone rules from `gameplay_zones`
  - manual snapshot capture and restore
  - timed `FULL`/`CHANGED` resets
  - TTL clearing for temporary placements/liquids
  - End access gating
  - End main-island crystal/mace/explosive restrictions
  - zone-scoped mace armor durability cap
- Current build status: `mvn -q -DskipTests package` passes.

## Known Acceptable Limitations
- `FULL` snapshot restores are still main-thread operations, just heavily batched. Large arenas can still cause mild visible work over time.
- Restore progress is deterministic and safe to cancel on reload/disable, but it is not resumed after restart. A cancelled restore must be run again.
- This plugin does not own PvP region messaging or safe-zone text anymore.
- This plugin does not integrate directly with WorldGuard. The assumption is that WorldGuard owns PvP flags/messages while MaceGuard owns build/reset/liquid/snapshot behavior.
- No GUI flows exist in the current plugin.

## Recommended Testing / Release Approach
1. Test first on a staging Leaf/Paper-compatible server matching production Java and plugin stack.
2. Use a fresh plugin data folder for first-pass validation so old snapshot/config artifacts do not confuse results.
3. Validate zone rules with at least two players:
   one normal player
   one op/admin with `maceguard.edit`
4. Validate snapshot/reset behavior before any public rollout.
5. Only after staged testing passes, deploy to production during a quiet period and keep `/maceguard reset`, `/maceguard snapshot`, and console logs available for rollback/inspection.

## Manual Testing Checklist
1. Startup and config load
   - Start the server with the plugin installed.
   - Confirm the plugin enables without stack traces.
   - Confirm `config.yml` and snapshot directory are created as expected.
   - Confirm command registration works: `/maceguard here` and `/maceguard endstatus`.

2. Command checks
   - `/maceguard here`
     - Verify it reports protected-region status correctly inside and outside configured zones.
     - Verify overlapping zone output reflects only highest-priority gameplay zones.
   - `/maceguard debug`
     - Toggle on and off.
     - Confirm the plugin survives reload-through-toggle cleanly.
   - `/maceguard reload`
     - Confirm reload succeeds with no errors.
     - Confirm temporary TTL blocks/liquids are cleared on reload.
     - Confirm no duplicate scheduled reset behavior starts afterward.
   - `/maceguard clear <zone>`
     - Place or modify blocks in a reset-capable zone.
     - Confirm tracked changes clear in batches and the server stays responsive.
   - `/maceguard snapshot <zone>`
     - Run against a populated snapshot zone.
     - Confirm snapshot capture starts and finishes cleanly.
   - `/maceguard reset <zone>`
     - Run once and confirm restore works.
     - Run a second time while the first restore is still active and confirm duplicate reset is rejected cleanly.

3. Protected fallback region behavior
   - Stand inside a location covered by `zones` but not by a higher-priority gameplay zone.
   - Verify normal players cannot place or break.
   - Verify a creative player with `maceguard.edit` can bypass.
   - Verify bucket emptying into a protected-only area is blocked.
   - Verify dispenser placement into a protected-only area is blocked.
   - Verify minecart creation in a protected-only area is removed/blocked.

4. Highest-priority gameplay zone behavior
   - Create a test spot where `warzone` and `war-pit` overlap.
   - Confirm only the higher-priority zone rules apply.
   - Verify placing blocks allowed by the higher-priority zone succeeds even if lower-priority outer zone would deny.
   - Verify breaking follows the higher-priority zone only.
   - Verify deny lists still win for special items like `END_CRYSTAL` and `RESPAWN_ANCHOR`.

5. Piston / bypass checks
   - Try pushing blocks into a protected fallback region from outside.
   - Try pulling blocks out of a protected fallback region.
   - Try pushing blocks into a gameplay zone that should deny placement.
   - Try pulling blocks out of a gameplay zone that should deny break.
   - Confirm the piston is cancelled in all blocked cases.

6. TTL / temporary block behavior
   - In `cobweb-box`, place allowed temporary materials such as `COBWEB`, `WATER`, and `ICE`.
   - Confirm they clear after the configured TTL.
   - Place multiple water sources/blobs at once and confirm all of them eventually drain.
   - Reload during active TTL countdowns and confirm temporary blocks/liquids are removed immediately.

7. Snapshot behavior
   - In `war-pit`, capture a snapshot when the arena contains real blocks.
   - Modify the arena substantially.
   - Run reset and confirm the cuboid restores to the captured state.
   - Delete or empty the snapshot file and confirm reset is skipped rather than pasting all air.
   - Confirm players inside the reset zone are moved to a safe snapshot-based height before restore work runs.

8. Timed reset behavior
   - Temporarily lower `full_reset_minutes` in a test zone.
   - Confirm warning messages fire only once per configured warning second.
   - Confirm the automatic reset starts once per cycle.
   - Confirm a second timed reset does not start on top of an active restore.

9. Reload / restart / shutdown checks
   - Trigger a `FULL` restore, then run `/maceguard reload` mid-restore.
   - Confirm the restore stops cleanly and no orphaned follow-up tasks continue afterward.
   - Trigger a `FULL` restore, then stop the server mid-restore.
   - Restart the server and confirm startup is clean.
   - Confirm cancelled restore progress does not silently continue after restart.
   - Re-run the reset manually and confirm it works normally after restart.

10. End access checks
   - With eyes disabled, try throwing an Ender Eye.
   - Confirm it is blocked.
   - Confirm inserting eyes into portal frames still works.
   - With portals disabled, try entering an End portal.
   - Confirm it is blocked.
   - Try ender-pearl portal bypass near an End portal and confirm it is blocked.
   - Test `/maceguard endeyes on|off|at ...`, `/maceguard endportal on|off|at ...`, and `/maceguard endstatus`.

11. End main-island checks
   - On the End main island, confirm mace hits are blocked if `block_maces: true`.
   - Confirm End crystals cannot be placed except on dragon respawn bedrock spots.
   - Confirm dragon respawn crystal spots enforce cooldown.
   - Confirm TNT and TNT minecart behavior follows config percentages.
   - Confirm beds and respawn anchors follow configured behavior, including fun bed sleep when enabled.
   - Confirm crystal explosions/damage remain blocked on the main island.

12. Mace durability zone checks
   - In `warzone`, attack a player with a mace.
   - Confirm player health damage stays vanilla.
   - Confirm armor durability loss is capped to `mace_armor_durability.damage_per_armor_piece`.
   - Repeat the same test outside `warzone` and confirm vanilla armor durability applies.
   - Repeat inside a higher-priority overlapping zone with durability cap disabled and confirm the cap does not apply there.

13. Performance / hot-path checks
   - Use a large snapshot arena and trigger a `FULL` restore while multiple players are online.
   - Watch TPS/MSPT and visible hitching during restore.
   - Confirm the server remains playable while restore batches are running.
   - Repeat with active TTL liquid cleanup and confirm there is no runaway lag.
   - Watch for excessive console spam during normal operation.

## Edge Cases To Verify
- Snapshot file exists but contains no usable blocks.
- Reset command issued while restore already running.
- Automatic reset interval fires while manual restore is already running.
- Reload during active TTL clear.
- Reload during active `FULL` restore.
- Piston movement crossing protected-zone boundaries.
- Overlapping zones where outer zone enables mace durability cap but inner higher-priority zone disables it.
- World missing or unloaded for configured snapshot zone.

## Integration Checks
- WorldGuard ownership expectations
  - Confirm WorldGuard, not MaceGuard, is handling PvP flags in spawn/safe zones.
  - Confirm WorldGuard, not MaceGuard, is handling player-facing region messages.
  - Confirm there is no confusing duplicate behavior between WorldGuard and MaceGuard.

## Final Release Decision
- Ready for testing if:
  - startup/reload/shutdown are clean
  - snapshot reset refuses empty/missing snapshot safely
  - duplicate restore prevention works
  - piston and dispenser bypass tests pass
  - zone-priority behavior matches expectations
  - mace durability cap only applies where configured
- Hold release if:
  - restores overlap
  - reload leaves orphaned restore/drain work
  - protected/reset zone bypasses still succeed
  - `FULL` restore causes unacceptable visible lag on your real arena sizes
