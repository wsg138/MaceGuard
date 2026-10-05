# Warzone water flow fix — SMP Test

Deployed to SMP Test (`5d109214`) on 2026-09-27, about 23:31 EDT.

## Cause and repair

- The live WorldGuard `world/warzone` region explicitly denied `water-flow`.
  Changed only this flag with `rg flag -w world warzone water-flow allow`.
  WorldGuard confirmed and saved the change. Spawn's water-flow deny remains.
- WorldGuard also checks build permission for flowing liquid. Previously MaceGuard
  granted flow only where a named block policy existed; most of Warzone has none.
  The patched listener grants water spread inside the effective Warzone while
  COBWEBS is active, preserving invalid-policy denial and other plugin cancellations.
- Outward water flow into the nested safe zone or outside the effective Warzone
  is cancelled by MaceGuard.

## Artifact and validation

- Active file: `plugins/MaceGuard.jar`, 985606 bytes.
- SHA-256: `AE7EE7DFABFA8343F4273EF55F556448CC8469CA04D9A068745A087C4BAEF650`.
- Maven artifact version: `6.1.8-water-test.1`. The existing static plugin.yml
  descriptor still reports `6.1.8`; identify this test artifact by its hash.
- Previous JAR retained as `MaceGuard.jar.pre-water-flow-test1.disabled`.
- 29 focused tests passed: BlockPolicyWorldGuardGrantTest,
  BlockPolicyDecisionTest, CobwebListenerPlacementTest.
- Built with JDK 22, Java release 21, and `-Denforcer.skip=true` because the
  environment lacks the build contract's JDK 21. The full suite was not rerun;
  previous loader failures remain outside this fix.

## Live physics evidence

Runtime override before restart: `[cobwebs, no-lunge, carts]`; scope active.
Test coordinates at x=150..152, y=309, z=5 fall only inside `warzone`.
Temporary enclosed cells were created only after every affected block was
confirmed to be air. Water was placed with vanilla setblock, then natural
server fluid ticks performed the movement and cobweb destruction.

- 23:33:11 EDT: water above a cobweb replaced the cobweb at `(150,309,5)`.
  `execute in minecraft:overworld if block 150 309 5 minecraft:water`
  returned `Test passed`.
- 23:34:04 EDT: water at `(150,309,5)` flowed horizontally through air at
  `(151,309,5)` and replaced a cobweb at `(152,309,5)`.
  Both destination water checks returned `Test passed`.
- 23:34:38 EDT: all 45 blocks in the larger test volume were verified restored
  to air. Test string drops were removed, including the identified vertical-test
  drop whose recorded origin matched the test cell. Temporary force-loading of
  chunk `[9,0]` was removed; it was not force-loaded before testing.

This verifies live fluid movement and cobweb destruction, not a player bucket
interaction or a client-visible escape attempt. No production change or PR.
