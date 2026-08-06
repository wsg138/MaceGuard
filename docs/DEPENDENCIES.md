# Reproducible compile dependencies

MaceGuard 6.1.1 uses immutable compile-time API artifacts. All remain `provided`; none are shaded into the runtime JAR.

| API | Group ID | Artifact ID | Immutable version | SHA-256 | Repository | Intended runtime baseline |
|---|---|---|---|---|---|---|
| Paper | `io.papermc.paper` | `paper-api` | `1.21.11-R0.1-20251209.165129-1` | `7ec623c368f72a6a7326a2d903397a537b0c4cad86d6f63f65dc77606a29809b` | `https://repo.papermc.io/repository/maven-public/` | Paper/Leaf Minecraft 1.21.11 |
| BlueSlimeCore | `com.github.sirblobman.api` | `core` | `2.9-20260720.221205-67` | `c98c8fbeecca618c3021ea7d116ef21f5e17632cf8ea235b0905ac6fafa0c33e` | `https://nexus.sirblobman.xyz/public/` | API surface expected with CombatLogX 11.6.0.0.1286 |
| CombatLogX | `com.github.sirblobman.combatlogx` | `api` | `11.6-20251210.005328-47` | `cf57523967ec8778a2164a3fbd2f468efe6797da78c9c0dc125cf3606f35015c` | `https://nexus.sirblobman.xyz/public/` | CombatLogX 11.6.0.0.1286 |

These coordinates and hashes establish a reproducible compile surface; they are not evidence that the exact Paper/Leaf, CombatLogX, BlueSlimeCore, WorldGuard, PlaceholderAPI, Java-client, or Bedrock/Geyser runtime matrix has been exercised. That compatibility remains subject to `docs/DEPLOYMENT.md`.

WorldGuard `7.0.17` and PlaceholderAPI `2.11.6` are already immutable releases. Maven Enforcer rejects unsupported Java/Maven versions and duplicate dependency declarations. CI retains `./mvnw -B dependency:tree` output and fails unless the resolved critical API JAR hashes exactly match the values above.

The build does not vendor, relocate, or shade Paper, WorldGuard, WorldEdit, CombatLogX, BlueSlimeCore, or PlaceholderAPI classes.
