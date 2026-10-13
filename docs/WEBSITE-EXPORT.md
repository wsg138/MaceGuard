# Local automatic Warzone export

Authoritative base: main 38e4255cf1940c2397da5a4e2cecc6c56498c8b4 (6.1.8).
Local-only work: no PR, push, server mutation or activation.

Requirements: capture effective MaceGuard runtime rules and manual overrides on
the server thread, then send bounded public JSON off-thread to the website's
signed community ingest. Respect paused schedules and indefinite overrides.
Never publish world/region IDs, coordinates, player opponents, wagers or secrets.
UTC Monday weekly match wins use only an optional public WarzoneDuels export
method; absent/invalid exports remain unavailable, never zero or invented kills.
Signing uses a secret supplied through an environment variable, not Git or chat.
HTTPS only, no credentials/query/fragment/redirects, timeout, bounded leaders,
one pending delivery and cancellation on shutdown. Default is disabled.

Production remains on WarzoneDuels 1.0.4 while standalone canonical main/pin
describe 1.0.1. This export must not authorize downgrading that plugin. Reconcile
the exact runtime lineage and counted-team-win semantics before enabling its
optional weekly-wins adapter. The existing captured preview is not automatic.

SPEAR requirements/proof/implementation/verification are manual because no
project-local EARS/state helpers were found. Test artifacts remain local and
unmerged; canonical source/pins and live acceptance are separate requirements.

## Live continuation, 13 October

The publisher now prefers the bounded websiteWeeklyResults public API, retains
legacy websiteWeeklyWins compatibility, and carries validated UTC week,
results freshness and completeness metadata. Bad/previous-week exports remain
unavailable. Optional Duels load ordering is explicit. A bounded, Paper-scheduled
asynchronous reader and JDBC query timeout prevent timed-out queries from accumulating
unbounded background work. Rotation capture remains on the game thread.

Local clean Java 21 Maven verify passes: 544 tests, no failures/skips. An actual
native H2 team-results DTO -> MaceGuard signer -> website signed ingest -> SQLite
-> public feed check passes with a synthetic fixture, including replay rejection.
No real match or HTTP production delivery is performed by that check.

Activation needs merged exporter code and reconciled installed runtime lineage:
server inspection shows 6.1.8-water-riptide-test.8, not the canonical version string.
Preserve its water/riptide combat fixes in the merged runtime. Network pin main
559bfabc currently uses MaceGuard e18093c and Duels 475d245; repin only to verified
merged commits and build through the network path after review.

Operator configuration after those gates:

```yaml
# plugins/MaceGuard/website-export.yml
enabled: true
endpoint: "https://enthusia.info/api/community/ingest"
secret-environment-variable: "COMMUNITY_INGEST_SECRET"
interval-seconds: 300
```

Supply the matching website ingest secret through the server environment; never
commit it, put it in this YAML or send it in chat. The existing encrypted Cloudflare
secret cannot be read back. An operator must supply the existing value or explicitly
coordinate a secure rotation on both sides. Then restart the verified runtime and
check two consecutive signed deliveries plus a real counted duel result. Rollback:
disable this exporter and restart; do not change rotation/gameplay settings.

Review follow-up: replace private unmanaged worker threads with Paper-owned
asynchronous tasks and a single-read lifecycle gate. A timeout/cancellation cannot
release a still-running query or deliver its late result. Close cancels queued
results and rejects new work. All snapshot maps are constructed immutably, and
the repeated ConfigLoaderTest line-ending literal has a named constant. No
analyzer rules are suppressed. See WEBSITE-REVIEW-53-48-24.md for verification;
hosted plugin test execution still requires maintainer workflow approval.
