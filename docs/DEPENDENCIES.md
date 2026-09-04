# Reproducible compile dependencies

MaceGuard 6.1.6 keeps runtime plugin integrations out of its compile surface whenever their public Maven artifacts are not stable long-term dependencies.

| API | Group ID | Artifact ID | Immutable version | SHA-256 | Repository | Intended runtime baseline |
|---|---|---|---|---|---|---|
| Paper | `io.papermc.paper` | `paper-api` | `1.21.11-R0.1-20251209.165129-1` | `7ec623c368f72a6a7326a2d903397a537b0c4cad86d6f63f65dc77606a29809b` | `https://repo.papermc.io/repository/maven-public/` | Paper/Leaf Minecraft 1.21.11 |

CombatLogX remains an optional runtime integration declared through `softdepend`. MaceGuard no longer compiles against or downloads CombatLogX or BlueSlimeCore artifacts. Instead, the CombatLogX gateway resolves and validates the small public API surface it needs when CombatLogX is actually enabled. If that runtime API is missing or incompatible, only combat-dependent MaceGuard behavior is marked unavailable; unrelated features continue to operate.

This removes the previous failure mode where an upstream Maven repository could purge a timestamped CombatLogX or BlueSlimeCore snapshot and make an otherwise unchanged MaceGuard source commit impossible to build.

WorldGuard `7.0.17` and PlaceholderAPI `2.11.6` remain normal immutable `provided` dependencies. Maven Enforcer rejects unsupported Java/Maven versions and duplicate dependency declarations. CI retains `./mvnw -B dependency:tree` output and verifies the immutable Paper API JAR hash above.

The build does not vendor, relocate, or shade Paper, WorldGuard, WorldEdit, CombatLogX, BlueSlimeCore, or PlaceholderAPI classes.
