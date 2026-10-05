# Water map protection follow-up

Supersedes water-riptide-test.5 for testing. Production is unchanged; no reload,
restart, upload, or PR was performed for this code update.

- Buckets and water flow may replace only air variants, existing water, or COBWEB.
- Other map blocks are protected regardless of the COBWEBS modifier state,
  explicit fluid-policy grants, or the block-policy bypass permission.
- Water-contact lava solidification and concrete-powder hardening are cancelled
  inside the effective Warzone. Other formations and outside scope are unchanged.
- Bucket placement/pickup and the Riptide charge gate from test.5 remain included.
- New protection tests failed against test.5 before implementation: four failures
  in 26 focused tests, no test errors. No historical red/green claim for prior work.

Paper's BlockFormEvent documentation covers contact-driven fluid formations:
https://jd.papermc.io/paper/26.2/org/bukkit/event/block/BlockFormEvent.html

Live acceptance remains required: non-op survival player, both hands, direct water
bucket placement and flow against grass, flowers, torches, redstone, rails, crops,
snow and vines; water near lava/concrete powder; permitted cobweb removal and
air/water flow; nested safe zones and region boundaries. No live gameplay claim.

## Final local evidence

- Maven verify with installed JDK 22, Java release 21, enforcer explicitly skipped:
  565 tests passed, zero failures/errors/skips. As in test.5, eight existing
  DirectCombatLogXGatewayTest cases were excluded due to the incomplete adapter
  fixture. This is not a clean full-suite claim or JDK 21 runtime verification.
- Material allowlist regression checks every Material enum value, with targeted
  bucket/flow tests for decorations and explicit-policy/bypass paths.
- Bucket grants use Bukkit's affected block rather than inferring a destination
  from the clicked face, so replacement checks and boundary checks agree.
- Descriptor version: `6.1.8-water-riptide-test.6`.
- Artifact: `C:/Users/p_ric/OneDrive/Documents/ChatGPT/Chapter 2/artifacts/maceguard-6.1.8-water-riptide-test.6.jar`.
