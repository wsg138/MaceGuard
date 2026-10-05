# Fire arrow contact repair — SMP Test

Deployed to SMP Test (`5d109214`) on 2026-09-27 at about 23:52 EDT. No production change or PR.

- `ExplosiveControlListener` now ignites arrows that enter fire or soul fire in the effective Warzone while the CARTS modifier is active. This supplies the burning-arrow state required by the existing TNT minecart ignition path.
- Active file: `plugins/MaceGuard.jar`, 985812 bytes, SHA-256 `4DDBE446404BA3C4A26E8B2BF0AC16689EE9A8E3FEC62973F7977A9E92C10853`.
- Plugin version reported after restart: `6.1.8-cart-arrow-test.1`.
- Previous working JAR retained as `plugins/MaceGuard.jar.pre-cart-arrow-test1.disabled`.
- Maven package completed using JDK 22 with `-Denforcer.skip=true -DskipTests`. Tests were not run in this turn.
- Live before/after check: A console-summoned arrow traveled through fire at `(152,309,5)` and hit the opposite barrier wall. Its Fire tag was `0s` before the update and `291s` after it. CARTS and COBWEBS were active during the after check.
- The tagged test arrow was killed, the fire removed, all 58 temporary barrier blocks restored to air, and temporary force loading of chunk `[9,0]` removed.
- Player-fired arrow impact against a player-placed TNT minecart has not yet been observed in this check.
