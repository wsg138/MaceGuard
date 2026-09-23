# MaceGuard testing guide

This document explains the repository-local automated test suite, the owner-directed feature-coverage hardening, how to run and review tests, and what remains a live Paper/Sentinel/staging concern.

## Test ownership

MaceGuard keeps its handwritten unit, regression, configuration, policy, persistence, reset, WorldGuard, Warzone, combat, GUI, message, and integration tests in this repository under `src/test/java`.

Sentinel Sim may consume the built MaceGuard artifact for runtime/compatibility evidence, but Sentinel is not the home for MaceGuard's normal behavioral tests.

## Coverage hardening added by the test campaign

### `PluginSurfaceContractTest`

This contract freezes security- and compatibility-sensitive plugin metadata:

- the four reviewed commands: `maceguard`, `maceguardpearltrace`, `warzone`, and `stasis`;
- the `warzone` aliases;
- the reviewed outer command permission boundary;
- hard dependency on WorldGuard;
- soft dependencies on PlaceholderAPI and CombatLogX;
- the complete reviewed permission set;
- which permissions are public (`default: true`), operator-only (`default: op`), or fail-closed (`default: false`);
- every permission child references an existing permission and explicitly grants it.

A failure here should be treated as an intentional-surface review requirement. Do not simply update the expected values to make CI green. First verify that the command, permission, dependency, or authority change is intended and has appropriate behavioral tests.

### `FullFeatureCoverageContractTest`

This is an inventory/maintenance guard over the existing behavioral suite. It requires concrete regression-test sources for these feature families:

1. configuration and bundled defaults;
2. block and bypass policy;
3. snapshot/reset planning and validation;
4. restart-safe persistence;
5. temporary blocks and cleanup;
6. WorldGuard flag and priority integration;
7. Warzone combat and stasis;
8. Warzone rotation, schedules, kits, and modifiers;
9. Warzone runtime, reload, migration, and defaults;
10. Warzone GUI behavior;
11. Warzone messages, denial feedback, and cooldown presentation;
12. Warzone external integrations and placeholders.

The contract does **not** replace those behavioral tests. It exists so a future refactor cannot silently remove an entire test family without CI noticing.

Do not satisfy the guard with empty tests or by renaming an unrelated file to match a marker.

## Existing behavioral evidence

The repository already contains substantial suites such as:

- `config/BlockPolicyTest` and `config/ConfigLoaderTest`;
- `policy/BlockPolicyDecisionTest`, `BlockPolicyResolverTest`, and `PermissionIsolationTest`;
- reset/snapshot planner and validator suites;
- `storage/PersistenceSafetyTest` for interrupted/restarted state;
- temporary-block cleanup/failure tests;
- WorldGuard priority/flag behavior;
- Warzone combat/stasis behavior;
- weekly/repeating schedule and modifier-selection tests;
- Warzone runtime/reload/migration/default tests;
- GUI navigation tests;
- message/denial/cooldown tests;
- external integration/placeholder tests.

When product behavior changes, extend the real owning behavioral test. The two coverage contracts are not a substitute for feature-level assertions.

## Running tests

Use Java 21 and the checked-in Maven wrapper.

Run all tests:

```bash
./mvnw -B test
```

Run the two coverage contracts only:

```bash
./mvnw -B -Dtest=PluginSurfaceContractTest,FullFeatureCoverageContractTest test
```

Run a focused class, for example:

```bash
./mvnw -B -Dtest=ResetPlannerTest test
```

Run the canonical repository verification before treating a head as merge-ready:

```bash
./mvnw -B clean verify
```

The canonical GitHub Actions `Build` workflow performs `clean verify`, resolves the dependency tree, inspects the packaged JAR, verifies the exact source SHA and release metadata, checks provided dependencies are not shaded, and uploads Surefire/build evidence.

## Where results are written

Maven/Surefire writes local results under:

- `target/surefire-reports/*.txt`;
- `target/surefire-reports/*.xml`.

The `Build` workflow also records:

- `clean-verify.log`;
- `jar-inspection.log`;
- `target/verification.txt`;
- `target/dependency-tree.txt`;
- the exact built `target/MaceGuard.jar`;
- Sentinel fixtures used by packaging checks.

Always bind final evidence to the exact PR-head SHA. An older green run becomes stale after any content change.

## How to interpret failures

### Plugin surface mismatch

A command, alias, dependency, permission, permission default, or child relationship changed.

Review it as a public/security surface change. Verify authority, backwards compatibility, command behavior, and documentation before changing the contract.

### Feature-family coverage failure

One of the major behavioral test areas disappeared or moved so the maintenance guard can no longer find it.

Usually the right response is to restore/add meaningful behavioral coverage. If the repository was deliberately reorganized, update the marker only after verifying the replacement tests cover the same behavior.

### Behavioral test failure

Treat the owning test as authoritative evidence until shown otherwise. Determine whether the failure is:

- a real product regression;
- an intentionally changed behavior needing a reviewed test update;
- a test-fixture/API mismatch;
- an environment/dependency failure.

Do not weaken assertions solely to obtain a pass.

### Packaging/JAR inspection failure

This is separate from unit-test success. Inspect release metadata, dependency hashes, shaded/provided packages, source/test leakage, schema fixture checks, and exact-SHA provenance.

## Adding tests for new features

When MaceGuard gains or changes a feature:

1. identify the owning feature family;
2. add focused positive and negative behavioral tests close to the production code;
3. cover bypass/permission decisions when relevant;
4. cover reload/restart/recovery/partial-failure behavior for persistent or scheduled state;
5. cover wrong-world/region/stale-state boundaries for spatial behavior;
6. cover provider-present/provider-missing behavior for integrations;
7. update `PluginSurfaceContractTest` only if the public command/dependency/permission surface intentionally changed;
8. add a new family to `FullFeatureCoverageContractTest` if the feature does not fit an existing family;
9. run focused tests, then `./mvnw -B clean verify`;
10. inspect exact-head GitHub Actions evidence before merge.

## Sentinel and live Paper boundaries

Repository-local tests are best for deterministic policy, parsing, persistence, scheduling, state machines, and regression behavior.

Use Sentinel or a real Paper/staging environment when the question requires:

- loading the packaged JAR with real dependency combinations;
- real WorldGuard/Paper lifecycle behavior;
- CombatLogX or PlaceholderAPI runtime interaction;
- server restart/update behavior;
- long action sequences or cross-plugin compatibility;
- production-stack compatibility evidence.

A repository test pass, Sentinel pass, and live staging pass are separate evidence. Do not report one as another.

## Parallel-worker rule

The test-hardening branch is intentionally test/documentation-only. Existing MaceGuard PRs may own build/artifact-retention or Sentinel-producer workflow paths. Reconcile all open PR changed paths before editing, and keep product/runtime fixes in the owning product branch unless the owner explicitly expands scope.
