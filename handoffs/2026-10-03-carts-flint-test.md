# Carts-enabled Warzone flint-and-steel interactions

User clarified that the report concerns the Warzone, not protected Spawn. The repair remains inside the effective Warzone and requires the rotator's CARTS modifier. It does not change live region flags or Spawn exclusions.

Previously the WorldGuard block/item/entity-use and fire-placement delegate grants depended on a LIGHTER query returning false. A permitted lighter query does not establish that independent interaction or placement delegates will permit the action. Grants now depend on the scoped carts rule, not that query. Fire-ignition delegates also require the player's location inside the effective Warzone. The block interaction helper requires right-click, flint and steel, and both the clicked block and adjacent fire position inside the carts-enabled scope. Cancelled original events are not reopened. Ordinary interactive blocks and TNT are excluded so a held lighter does not grant chest/door access or normal TNT priming. Candle/campfire modification delegates receive a narrow material grant. Existing cart ownership checks and fire-spread/burn prevention remain intact.

Artifact: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-6.1.8-water-riptide-test.8.jar`

- Descriptor version: `6.1.8-water-riptide-test.8`
- Bytes: 1,010,538
- SHA-256: `AE552AE7C11D9A35352F0164AA4A17B819211E5FC639BF5266AA18C8808BBDE5`
- Includes prior test.7 water, Riptide, and guild-vault combat fixes.

Verification: Maven verify succeeded, 583 tests passed, zero failures/errors. Eight existing DirectCombatLogXGatewayTest fixture tests remain excluded; stale reports for that excluded class are not results of this run. JDK 22, compiler release 21, Java enforcer skipped. Nine new routing tests include carts off, outside/Spawn boundaries, player outside, cancellation, left-click, LIGHTER already allowed, candle/campfire modification, unrelated block placement and chest/door/TNT protection. Material.isInteractable needs a live Paper registry, so routing tests substitute that one classification seam. Live server/client behavior remains unverified.

Next player acceptance: as a normal non-bypass player inside the Warzone, enable CARTS, light fire on a solid block, ignite a player-placed owned TNT minecart, and test a flame arrow crossing fire. Confirm fire does not spread or burn map blocks. Repeat with CARTS disabled and near the Spawn boundary; protected Spawn must not gain grants. Confirm other plugins retain cancellation authority.

No upload, active plugin replacement, reload, restart, PR, or push performed this turn. Test.7 remains the previously uploaded staging artifact.

## Subsequent staging upload

On October 3, at the user's request, the cumulative test.8 artifact was uploaded to Bloom SMP `41f458f0` at `/plugins/chapter 2 staging/maceguard-6.1.8-water-riptide-test.8.jar`. Local SHA-256 was checked against the value above before upload. The file manager confirmed the filename, displayed 1.01 MB size, and fresh timestamp. Test.7 was retained. No active plugin replacement, configuration edit, reload or restart occurred. Screenshot: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-test8-staged.jpg`. File presence confirms staging, not activation or client acceptance. This cumulative artifact differs from the focused flint-only PR #1 artifact.
