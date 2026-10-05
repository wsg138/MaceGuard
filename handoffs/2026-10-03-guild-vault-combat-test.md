# Guild vault combat guard test build

## Scope

Block `/g vault` and `/guild vault` during any active CombatLogX tag, including ordinary combat, Warzone combat, and tags carried outside the Warzone. Known LumaGuilds namespaced labels and registered aliases of its canonical guild command are covered. Other guild subcommands and untagged vault access are unchanged.

The guard uses the raw CombatLogX combat state rather than location or Warzone policy latches. It is registered even when Warzone gameplay is disabled, and unregistered during runtime shutdown. A matched command is denied if an available combat integration throws while checking its state. An absent optional integration does not block commands. Denial messages use the existing per-player throttle. This is a command guard, not a restriction on already-open inventory screens or unrelated custom command expansion routes.

## Artifact

- File: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-6.1.8-water-riptide-test.7.jar`
- Descriptor version: `6.1.8-water-riptide-test.7`
- Size: 1,010,026 bytes
- SHA-256: `F8F497D78392A0798AFC67687CC6CD3117F446EC187056F0C6FD749E036184AF`
- Includes the preceding water placement, water map protection, and Riptide test fixes.

## Verification

Maven verify passed with 574 tests, no failures or errors, under local JDK 22 with Java release 21. The eight existing DirectCombatLogXGatewayTest fixture tests were excluded because their fake adapter lacks TagType. The Java-version enforcer was explicitly skipped because JDK 21 was unavailable locally. This is not a full-suite or JDK 21 runtime verification.

Coverage includes known command labels, casing and whitespace, registered aliases, unrelated commands, untagged access, already-cancelled events, missing integrations, combat-query failure, ordinary combat without Warzone location state, and denial-message throttling.

Live Paper/CombatLogX/LumaGuilds player testing remains required: attempt guild vault access while tagged inside and outside the Warzone, verify denial and no vault opening, then verify access returns after the tag clears. Confirm other guild commands remain unaffected.

Production was not uploaded, reloaded, or restarted for this change. No PR or push was created.

## Subsequent staging upload

On the user's request, uploaded the versioned test.7 artifact to Bloom SMP `41f458f0` at `/plugins/chapter 2 staging/maceguard-6.1.8-water-riptide-test.7.jar`. The file manager confirmed the filename, 1.01 MB displayed size, and a fresh upload timestamp. No active plugin was replaced, and no reload or restart was issued. Upload confirmation screenshot: `C:\Users\p_ric\OneDrive\Documents\ChatGPT\Chapter 2\artifacts\maceguard-test7-staged.jpg`. This confirms staging file presence, not activation or live gameplay acceptance.
