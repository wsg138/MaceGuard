# MaceGuard 6.1 deployment and staging

MaceGuard 6.1 targets Java 21 and Paper/Leaf 1.21.11. WorldGuard remains required; PlaceholderAPI and CombatLogX are optional integrations. Keep a new schema-7 build in staging until the checks below are complete.

## Safe Warzone activation

Fresh installations ship with `warzone.yml` disabled. Complete this sequence before enabling gameplay:

1. Back up `plugins/MaceGuard`, including `state/` and reset snapshots.
2. Create and review the configured outer `warzone` WorldGuard region.
3. Create and review every configured exclusion, including `spawn` and `market` by default.
4. Review `rotation.schedule`, every cycle entry, kit, modifier, conflict group, and restriction target.
5. Run `/warzone validate` and require a successful result.
6. Change top-level `enabled` to `true`.
7. Reload or restart.
8. Run `/warzone debug`, `/warzone info`, `/warzone schedule preview`, and `/warzone items`.
9. Confirm `Gameplay scope active: true` and `Whole-world fallback: false`.

Missing world or region geometry makes Warzone gameplay inactive. It does not expand restrictions to the configured world.


## Required CombatLogX production configuration

MaceGuard does not edit another plugin's files. Before production, update the installed CombatLogX 11.6 Cheat Prevention expansion to the following behavior.

`items.yml`:

```yaml
prevent-elytra: false
force-prevent-elytra: false
elytra-retag: true
prevent-riptide: true
riptide-retag: false
```

If that expansion exposes `prevent-fireworks`, keep it `false`. MaceGuard cancels only `PlayerElytraBoostEvent`; ordinary firework launching must remain available.

`teleportation.yml`:

```yaml
prevent-portals: true
prevent-teleportation: true

allowed-teleport-cause-list:
  - ENDER_PEARL

ender-pearl-retag: true
untag: false
```

Remove `PLUGIN` and `UNKNOWN` from the allow list. This leaves successful Ender Pearl teleports available for CombatLogX retagging and MaceGuard's aged-pearl decision while CombatLogX blocks `/tpa`, `/home`, `/spawn`, other plugin teleports, and portals during combat. Validate server-specific plugins in staging rather than weakening the allow list preemptively.

Assign the custom flags only to the intended WorldGuard regions:

```text
/rg flag <region> warzonerotator-combat-zone allow
/rg flag <region> warzonerotator-stasis deny
```

An unset combat-zone flag never grants a latch. An unset or allowed stasis flag never blocks a stasis teleport. There is no world-wide fallback.

## Combat integration staging matrix

Stage all of the following on the production-equivalent Leaf build:

- start combat inside versus outside the flagged region;
- cross the border while already tagged, including a normal Ender Pearl teleport into the region;
- rotate the live meta in both directions during combat;
- leave the Warzone while Mace, Spear, Spear damage, Spear Lunge, Ender Pearl, and Wind Charge carryover variants are active;
- verify two variants of the same cooldown target can use different carryover settings without stale enforcement;
- hold a real bubble-column stasis chamber beyond 60 seconds, then compare a normal pearl below the threshold;
- suspend several pearls for one player and land normal/aged pearls close together;
- test simultaneous pearls from multiple players and confirm no cross-player correlation;
- begin combat while already gliding, then land and attempt to restart;
- attempt a new Elytra start in ordinary combat and under the Elytra-allowing Warzone meta;
- attempt actual Elytra boosts and ordinary firework launching separately;
- verify Riptide is canceled by CombatLogX;
- verify `/tpa`, `/home`, `/spawn`, plugin teleports, and portals after the configuration change;
- test death, combat logout, reload, and full restart;
- test Java and Bedrock/Geyser players;
- observe event and tracking behavior under realistic load and validate the 100-player assumptions.

The Ender Pearl impact-to-teleport ordering and Elytra behavior must be confirmed on the exact deployed Paper/Leaf build. Automated tests validate the policy and tracker, but they do not replace this staging step.

## Schedule review

Confirm the schedule uses the intended IANA timezone, anchor date, local time, cadence, and cycle:

```yaml
rotation:
  schedule:
    enabled: true
    timezone: America/Indiana/Indianapolis
    anchor-date: "2026-08-09"
    time: "04:00"
    cadence:
      every: 1
      unit: WEEKS
    cycle:
      - type: KIT
        kit: smp
      - type: RANDOM
      - type: KIT
        kit: mace
      - type: MODIFIERS
        modifiers: [cobwebs, no-lunge]
```

Staging must verify:

- `DAYS`, `WEEKS`, and `MONTHS` preserve the configured local transition time;
- spring and fall DST boundaries behave as documented;
- monthly anchors on the 29th, 30th, or 31st clamp only for shorter months and return to the anchor day later;
- several missed boundaries advance directly to the currently due slot;
- a `RANDOM` slot keeps the same persisted result after restart;
- `KIT`, exact `MODIFIERS`, and `NONE` activate exactly;
- `/warzone schedule advance` persists its phase and does not repeat the advanced entry at the next boundary;
- schedule enable/disable survives restart and enabling catches up to the current due slot.

## Kit and custom-combination review

For every enabled kit, confirm:

- the icon is a real Bukkit material for 1.21.11;
- every modifier exists and is enabled;
- modifier order matches the intended display order;
- no duplicate, conflict-group, target, or conditional contradiction exists;
- the kit contains the complete intended selection because kit activation replaces the active set.

Verify that changing a kit-derived selection requires `warzonerotator.manage.custom-combinations`, displays the detach warning, creates a `CUSTOM_OVERRIDE`, and does not alter the kit definition.

## Manual override review

Test all manual entry points from both a player and console:

```text
/warzone kit set <kit> <1h|next|manual>
/warzone modifier set <modifier> <1h|next|manual>
/warzone modifier remove <modifier> <1h|next|manual>
/warzone random <1h|next|manual>
```

Verify:

- players receive selection, confirmation, and duration screens;
- console rejects missing IDs or duration values;
- one-hour overrides expire exactly one hour after confirmation;
- next-boundary overrides retain their originally confirmed expiration after config reload;
- indefinite overrides survive reload and restart;
- automatic boundaries under an override update state silently without changing gameplay;
- clearing or expiring an override applies the automatic slot currently due;
- ending an override does not reroll a persisted random automatic slot.

## GUI safety

Test the menus with Java and Bedrock clients. Confirm managed inventories reject:

- normal movement and taking menu items;
- shift-click insertion;
- number-key swaps;
- drag operations;
- double-click collection;
- offhand swaps;
- clicks from stale or expired sessions.

Navigate through multiple pages and confirm closing the replaced page does not invalidate the newly opened page. Close every screen before final confirmation and confirm no change is applied. Leave a confirmation open while another administrator changes the active set and confirm the stale operation is rejected.

## Spear staging

The default schema includes separate whole-spear, damage, and Lunge controls:

- `spear-disabled` blocks spear launch and both melee and correlated thrown-spear damage in the effective scope;
- `spear-damage-cooldown-10` starts only after accepted positive spear damage, including a correlated thrown-spear hit;
- `no-lunge` blocks only the Lunge velocity;
- `lunge-cooldown-10` starts only after an accepted correlated Lunge velocity.

Stage each spear material and enchantment level. Verify cross-boundary actor/target cases, ordinary attacks, throws, hits, misses, item swaps, unrelated velocity, latency, Geyser/Bedrock behavior, and cooldown expiration. Paper/Leaf 1.21.11 has no supported `EntityLungeEvent`; the implementation intentionally uses the narrow pre-attack/velocity correlation without NMS.

## Other restriction staging

Verify the existing Mace, Ender Pearl, Wind Charge, Elytra, and firework behavior:

- disabled actions cancel without consumption or duplicated denial messages;
- cooldowns start only from accepted success events;
- automated Wind Charges remain allowed in cooldown mode and blocked in disabled mode;
- visual cooldown overlays do not shorten stronger vanilla or plugin cooldowns;
- a modifier transition clears cooldowns only for restriction targets whose policy changed;
- region exit/re-entry, reconnect, reload, and lag reconcile owned overlays correctly;
- Elytra gliding and firework boosting follow the active final set.

## Transition and persistence staging

Exercise natural schedule changes, every override operation, config reload, plugin reload, and full restart. Confirm:

- each effective change emits each removed end message and added start message once;
- background automatic changes under an override produce no normal public transition;
- transient Lunge/projectile correlation state is safely reset at effective transitions;
- unchanged authoritative cooldown targets remain active while changed targets are cleared;
- cobweb clear-on-meta-change is invoked exactly when a cobweb-enabled final set ends;
- `state/warzone-state.yml` is atomic, ordered, and contains the expected automatic and override fields;
- malformed state is backed up and rejected with a clear log reason;
- startup publishes one final active set and does not replay historical announcements.

## Placeholder and command staging

Confirm existing placeholders still work and the schema-7 values return stable plain text:

```text
%warzone_source_type%
%warzone_active_kit%
%warzone_override_active%
%warzone_override_mode%
%warzone_override_ends_at%
%warzone_override_time_left%
%warzone_schedule_slot%
%warzone_schedule_cycle_position%
%warzone_next_source_type%
%warzone_next_name%
%warzone_next_changes_at%
```

Also verify indexed modifier placeholders preserve final active order and spear placeholders report whole-item, damage, and Lunge states correctly.

Tab completion must hide management operations without their focused permission and show live enabled modifier IDs, kit IDs, duration aliases, and schedule operations when authorized.

## Existing block, cobweb, and reset safety

MaceGuard 6 retains the existing WorldGuard block-policy, temporary-block recovery, explosion, snapshot, and reset safety. Stage at least:

- normal and emergency temporary-cobweb persistence, bounded recovery, chunk tickets, restart, and changed-block protection;
- a real burst of at least 100 managed cobwebs through TTL and full cleanup;
- source and flowing-water restoration under Paper block-data serialization;
- direct, inherited, and `__global__` block-policy diagnostics;
- full and filtered snapshot validation, exclusions, checksums, arming, one-use plan tokens, journals, and interrupted-operation recovery;
- plugin reload and shutdown while normal TTL tracking or emergency recovery is active.

MaceGuard never creates WorldGuard regions, captures snapshots, arms reset profiles, or enables reset schedules automatically.

## Emergency operator mitigation

For unexpected restrictions, do not grant a broad bypass as the first response. Inspect:

```text
/version MaceGuard
/rg info warzone
/rg flags warzone
/rg flags __global__
/warzone validate
/warzone debug
/warzone info
/warzone items
/maceguard here
```

Disable the top-level Warzone module or restore missing required geometry before returning gameplay to service. Review `state/warzone-state.yml` and migration backups before deleting any state.
