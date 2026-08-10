# Enthusia Sentinel staging

MaceGuard uses the existing production Enthusia Sentinel service in `wsg138/EnthusiaStaff-Staging`. The repository is authorized as a **manual-only** dependency target under immutable GitHub repository ID `1209823832`.

The canonical Sentinel rules live in the control repository. When this guide and the control-plane documentation disagree, reconcile current `EnthusiaStaff-Staging/main` and follow its current `docs/repository-onboarding.md`, `docs/sentinel-dependencies.md`, and trusted policy/registry state.

## Production R02 trust model

The existing `Enthusia Sentinel` GitHub App may remain installed in the owner-selected **All repositories** mode. App visibility is not execution authorization.

Sentinel separately enforces:

1. **Execution authorization** — MaceGuard is enabled in trusted policy as `wsg138/MaceGuard`, repository ID `1209823832`, with `automatic_transitions = false` and only manual `dependencies` allowed.
2. **Dependency source trust** — dependency JAR provenance is locked in the private control-plane registry. A dependency source is not thereby allowed to be polled or executed.
3. **Operation-scoped credentials** — short-lived GitHub App tokens are requested and server-verified for only the repository IDs needed by the current execution or dependency-source operation.

Do not create another GitHub App, use a PAT fallback, broaden token scope, or treat App installation visibility as Sentinel authorization.

## Why the dependency profile is required

MaceGuard declares WorldGuard as a hard Bukkit dependency. A bare-Paper startup without that dependency is not a meaningful MaceGuard smoke test.

`.enthusia-test.yml` therefore declares:

```yaml
profiles:
  - dependencies
dependencies:
  - id: worldguard
    kind: hard
actions:
  - profile: dependencies
    stage: after-start
    type: console-command
    command: warzone debug
```

MaceGuard declares only its **direct** trusted dependency, `worldguard`. The private Sentinel registry owns the transitive closure. The production lock resolves in dependency-first order:

```text
WorldEdit -> WorldGuard -> MaceGuard
```

Do not add a direct `worldedit` declaration merely because WorldGuard requires it. Do not put dependency versions, URLs, repositories, SHAs, workflow runs, artifact IDs, JAR paths, checksums, plugin identities, or transitive requirements in `.enthusia-test.yml`.

The authoritative WorldEdit/WorldGuard coordinates are always the current committed control-plane files:

```text
config/sentinel-dependencies.toml
ai-agents/reports/sen-r02-dependency-artifacts.json
```

Do not copy dependency coordinates from an old handoff or this repository's documentation.

## Exact target artifact

Sentinel must test the exact current PR head. MaceGuard's build workflow publishes the canonical target artifact:

```text
artifact name: MaceGuard
JAR path: target/MaceGuard.jar
```

The acceptance PR must be:

- open;
- non-draft;
- same-repository;
- unchanged at the exact head being tested;
- backed by a successful exact-SHA build that contains the declared `MaceGuard` artifact and `target/MaceGuard.jar`.

Do not substitute a branch-latest, local, or older PR artifact.

## Production acceptance command

Post this as an exact standalone PR comment:

```text
@enthusia-sentinel test dependencies
```

Do not add prose to the command comment and do not manually enqueue a replacement job.

The integration is accepted only when the terminal result is exactly:

```text
PAPER_DEPENDENCIES_OK
```

A passing production result must retain evidence for:

- exact MaceGuard repository, PR, head SHA, successful target workflow run, artifact identity, JAR path, and checksum;
- exact locked WorldEdit and WorldGuard provenance from the deployed control-plane registry;
- resolved/staged/enabled order `WorldEdit -> WorldGuard -> MaceGuard`;
- successful MaceGuard enablement and `warzone debug` after-start action;
- normal Paper readiness and clean `stop` shutdown;
- complete process-group reap;
- zero sandbox, temporary-download, and artifact-lease residue;
- queue returning clean/idle;
- durable GitHub result reporting.

Queued, rejected, stale-head, resource-gated, cancelled, timed-out, or any other terminal code is not a pass.

## Live gameplay checks remain separate

The `dependencies` profile proves dependency-aware startup, the declared console action, and cleanup. It does not replace production-equivalent gameplay checks for WorldGuard region geometry, Java/Bedrock event ordering, or player mechanics.

For Warzone behavior changes, separately verify:

- Wind Charge cooldown begins only after successful player launch and blocks item use before entity creation while active;
- disabled Wind Charges are denied before entity creation;
- dispenser behavior remains intentional;
- `/warzone menu` and every nested kit/modifier/schedule/detail/back path works;
- GUI names/lore remain readable and correctly styled;
- `/warzone info`, `/warzone items`, `/warzone modifiers`, `/warzone schedule`, `/warzone next`, `/warzone help`, and `/warzone debug` remain accurate;
- persistence failure/recovery retains the newest accepted state;
- WorldGuard-denied cobweb, water, or lava placement is never un-cancelled by MaceGuard.
