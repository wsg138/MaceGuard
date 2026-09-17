# Wind Burst block-explosion regression fix

## Problem

Wind Burst mace hits on Paper/Leaf 1.21.11 can surface their non-destructive explosion lifecycle as a `BlockExplodeEvent` with `ExplosionResult.TRIGGER_BLOCK`. MaceGuard's earlier Wind Burst exception covered player/entity explosion paths but the generic block-explosion handler still cancelled the entire event when `maceguard-explosives` was denied. Cancelling the full event suppresses the Wind Burst launch effect.

## Fix

- Detect only `ExplosionResult.TRIGGER_BLOCK` in the block-explosion path.
- At `LOWEST`, remove MaceGuard-denied block interactions before WorldGuard abstracts the event, preserving the explosion lifecycle while keeping protected blocks/interactions out of the affected list.
- At `HIGHEST`, never cancel a surviving `TRIGGER_BLOCK` event; re-filter its target list as a defensive boundary check.
- Leave `DESTROY`, `DESTROY_WITH_DECAY`, and other block-explosion results on the existing cancellation/filtering path.

## Regression coverage

`WindBurstBlockExplosionTest` verifies:

1. a denied-origin `TRIGGER_BLOCK` explosion keeps its lifecycle alive and clears affected blocks;
2. an ordinary destructive block explosion is still cancelled at a denied origin;
3. a permitted-origin `TRIGGER_BLOCK` explosion removes only targets denied by MaceGuard.

No configuration, schema, rotation, cart, reset, or other explosive behavior is intentionally changed.
