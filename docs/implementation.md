# Warzone combat and batch modifier implementation

- `WarzoneGuiManager` owns the per-player draft. `RotationManager.previewCustom` validates the composed set; `applyPrepared` changes the live set only after preview and duration confirmation.
- `CombatScopeService` owns a transient latch derived from CombatLogX and WorldGuard. `WarzoneCombatBar` reads the gateway's remaining and maximum seconds; the runtime reconciles and clears its owned bar.
- `DirectCombatLogXGateway` checks CombatLogX's enabled Boss Bar updater, temporarily suppresses its cached per-player display setting, and calls the updater's public `remove(Player)` method before the MaceGuard bar appears. It restores and saves the original setting only after MaceGuard hides its bar. An adapter failure keeps MaceGuard's bar hidden.
- `CombatElytraPolicy` and `ItemRestrictionListener` apply movement and teleport rules only when the Warzone latch and configured location/carryover scope allow them. Ender Pearls continue through the existing stasis path.
- `WarzoneConfigLoader` supplies defaults for old operator files and validates new boss-bar color settings. The bundled `warzone.yml` documents these settings; `docs/DEPLOYMENT.md` records the separate CombatLogX Cheat Prevention changes needed for ordinary combat outside Warzone.

- `WarzoneRetagListener` refreshes existing latches after successful PvP hits and pearl launches through the runtime-validated CombatLogX `tag` API, skipping duplicate timer refreshes and disconnected/expired players.
- `CombatPositionListener` cancels entry into configured safe regions; `WorldGuardQueryService` owns platform geometry queries. Movement within and out of a region remains permitted.
- `WarzoneWaterProtection` shares the bucket/flow destination allowlist. `CombatVaultCommandListener` denies guild vault commands for all active CombatLogX combat tags, including when Warzone gameplay is disabled.
