# Water buckets and Riptide combat repair

Local test build only. No production upload, plugin reload, server restart, or PR.

- Water bucket empty and fill delegates are allowed within the effective Warzone
  only while COBWEBS is active. Actor and destination must both be in scope.
  Explicit block policies, safe-zone exclusions, disabled module state, lava,
  and other plugins' existing cancellations retain authority.
- Production's `world/warzone` water-flow flag was already changed live to allow;
  this alone did not enable ordinary bucket placement under build deny.
- Riptide-enchanted tridents are denied at the item-use/charging boundary during
  Warzone combat, including carryover governed by existing combat configuration.
  Ordinary combat, unenchanted tridents, exclusions, and bypasses remain unchanged.
  The release event remains a fallback for tridents charged before combat tagging.
- Charge and release warnings share the configured blocked-message cooldown.

Required live acceptance on SMP Test: survival players using both hands; placement,
pickup and flow through cobwebs; disabled COBWEBS; Spawn/safe zones and boundaries;
Riptide in water/rain during Warzone combat and carryover, pre-charged release,
ordinary combat and tag expiry. Verify no propulsion and bounded warnings.

Paper documents client-side Riptide prediction:
https://jd.papermc.io/paper/26.2/org/bukkit/event/player/PlayerRiptideEvent.html
Local listener tests do not establish client-visible movement enforcement.

## Local verification

- Built with installed JDK 22, Maven 3.9.9, Java release 21,
  `-Denforcer.skip=true`; required JDK 21 enforcement was not verified.
- Final `verify -Dtest=!DirectCombatLogXGatewayTest`: 556 tests passed,
  zero failures/errors/skips. Eight tests in the existing direct CombatLogX
  adapter fixture were explicitly excluded: the full run exposed seven errors
  because the fixture lacks the TagType class required by prior retag changes.
  This is not a clean full-suite result.
- Riptide routing tests isolate the live enchantment-registry lookup with a spy;
  actual enchantment recognition and client movement require SMP Test acceptance.
- Initial Java 25 run failed because the existing Mockito/Byte Buddy test
  dependency cannot instrument that runtime; verification was rerun on JDK 22.
- Packaged descriptor reports `6.1.8-water-riptide-test.5`.
- Artifact: `C:/Users/p_ric/OneDrive/Documents/ChatGPT/Chapter 2/artifacts/maceguard-6.1.8-water-riptide-test.5.jar`
  (1,006,318 bytes). Existing test artifacts were not overwritten.
