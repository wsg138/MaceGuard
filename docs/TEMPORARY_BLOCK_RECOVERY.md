# Temporary block persistence-failure recovery

MaceGuard accepts a temporary cobweb placement only while normal persistence is healthy and no emergency recovery is pending. Every accepted placement must remain recoverable until the original block is restored.

## Files

- `state/temporary-blocks.json` is the existing primary state file and retains its current format.
- `state/temporary-blocks-emergency.json` is a separate atomic emergency rollback journal.

The emergency journal records placements that were accepted after the last successful primary snapshot when a later asynchronous write fails. It is loaded on startup and survives plugin reload or full server restart. An empty journal is committed only after every emergency entry has reached a terminal physical outcome.

## Failure behavior

When the primary writer becomes unhealthy, MaceGuard immediately stops accepting new temporary blocks. It snapshots every undurable entry into the emergency journal and schedules rollback on the Bukkit server thread.

Emergency rollback is intentionally different from healthy TTL cleanup:

- Healthy expired entries in unloaded chunks continue waiting for natural chunk loading.
- Emergency entries may temporarily acquire MaceGuard-owned plugin chunk tickets.
- Recovery processes at most two chunks and 64 entries per pass.
- Already-loaded chunks are not ticketed by MaceGuard.
- Only tickets successfully acquired by MaceGuard are released by MaceGuard.
- No chunk is marked permanently force-loaded.
- A missing world or unavailable chunk remains journaled for a later pass or restart.
- A failed restoration remains tracked and is retried.
- A changed physical block is terminal and is never overwritten.

Recovery and journal snapshots use ordered `LinkedHashMap` copies confined to the writer or Bukkit thread that creates them. They are not shared mutable maps; the published recovery state is immutable.

If the emergency journal itself cannot be written, MaceGuard logs the failure and continues bounded physical rollback for as long as the runtime remains available. It never treats the failed journal write as permission to discard in-memory recovery state.

## Reload and shutdown

Reload and shutdown stop new placement acceptance before the ordered writer terminates. After the writer finishes, MaceGuard performs another bounded rollback pass so a failure discovered during shutdown receives physical recovery before runtime memory is discarded. Any entry that cannot be restored remains in the emergency journal for startup recovery.

## Required staging checks

On Paper or Leaf, verify all of the following before merging the release:

1. Accept a cobweb, force an asynchronous primary-state write failure, unload its chunk, shut down, restart, and confirm the original block is restored with both primary and emergency tracking at zero.
2. Repeat the sequence with plugin reload instead of full shutdown.
3. Keep the world temporarily unavailable, then load it and confirm recovery resumes.
4. Test multiple entries in one chunk, entries across several chunks, and entries on opposite sides of a chunk boundary.
5. Confirm recovery releases only MaceGuard-owned tickets and leaves no permanent force-loaded chunks.
6. Confirm healthy expired entries still wait for natural chunk loading.
7. Force an emergency-journal write failure and confirm physical rollback continues while the runtime is available.
8. Repeat the existing 100-cobweb expiration regression and require both the tracked count and physical managed-cobweb count to reach zero.

Use `/maceguard temporary` and `/maceguard temporary debug` during staging. Emergency rollback logs when it starts, remains pending, completes, or cannot acquire the required world or chunk.
