# MultiPaper 26.3 release checkpoint — 2026-10-24

This file is the concise continuation source of truth. Historical notes in `PROJECT_CHECKPOINT.md` may describe superseded blockers; follow this checkpoint. Do not restart completed investigations from context compaction.

## Current candidate

- Branch `feature/port-26.3`, source `f891db1`, version RC4. Published RC3 tag and assets remain unchanged. No stable release is published.
- Java 25.0.3 / Gradle 9.8 full validation passed twice from clean build outputs with `--no-build-cache` and fixed manifest time: 186 suites, 10,740 tests, 0 failures/errors, 88 skipped. Master, server, bundler, and Paperclip outputs matched across the two passes. Gradle's dependency cache was present; an empty-cache bootstrap is not certified.
- Candidate artifacts: Paperclip `C124A7C775814FF9D5E537A2277084360F84DC67F700E158799B0D1E431C26D2`; Master `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`; bundler `EC7F96795EE2202F7F4D3A65F15F38589919BE64061BD03D1F72DF093B2971D9`; server `054DA16495977C017FBC4F50C754F4385D2F1AC89D93B59AF7FFA6070F69F458`.
- Local eight-file evaluation archive: `release-staging/MultiPaper-26.3-RC4-20261024.zip`, SHA-256 `070529BF09A5587A5D3DCB715C51D81DA1BD90BECE74A67B5851943DF40C3982`. It contains the two install JARs, license, checksums, compatibility report, RC4 candidate report, and Crafty/plugin-stack instructions. It contains no Floodgate private key or RCON secret. The archive is local evaluation material, not a published release.

## Completed verification — do not repeat without a new regression

- Durable scheduled-tick handoff passed five receiver restart cycles, three delayed-ack cycles, sender and receiver crashes, Master restart recovery, and 12 focused tests. Primary regression: `Test-ScheduledTickUnloadReplay.ps1`. Full Java 25 validation is above.
- Entity synchronization/recovery passed ownership transfer and crash-recovery validation plus an eight-client movement soak. Preserve its completed evidence; do not revisit absent a fresh failure.
- Exact-RC4 disposable two-backend tests passed plugin startup, `/mpdebug`, `/slist`, 1.21.8 login/chunk delivery, mechanics and hopper conservation across ownership handoff, sticky-piston redstone-block pullback with either backend as owner, and InvSee++ Ender Chest item-conservation cycles. Offline/forged online-mode probes were rejected. These are local synthetic/disposable tests, not human authentication or gameplay evidence. See `performance-results/rc4-candidate-release-certification-20261024.md`.
- Geyser's local loopback listener returned a valid Bedrock status ping only. No Bedrock login or Floodgate identity test occurred.
- The local protocol capacity attempt was invalid under GrimAC; it establishes no player capacity. See `performance-results/rc4-local-capacity-harness-investigation-20261024.md`.

## Newly documented configuration findings

- `sync-settings.sync-permissions` defaults to `false`. Set it to `true` on every backend if effective permissions should follow player ownership changes; still configure permission plugins/grants consistently. Cross-owner permission transfer has not been tested.
- GrimAC routes violations, sessions, player identities, and settings to SQLite by default. SQLite is local/single-server; shared history requires both backends configured to the same MySQL/PostgreSQL service with its JDBC driver present. This does not affect MultiPaper world/inventory replication. Shared SQL was not tested. Details are in both candidate installation guides and the candidate report.
- GrimAC warns that ViaBackwards on 1.21.2+ has unsupported older-client vehicle behavior. Legitimate-client behavior and the compatibility decision remain open.

## Remaining release blockers and next actions

1. Wait for the user's already-promised real Minecraft online-mode login and human Survival/farm/redstone/portal test. Do not ask again.
2. A real reachable Bedrock login must verify Floodgate identity, reconnect, permissions, and ownership transfer.
3. Verify legitimate-player GrimAC behavior and decide how to handle the older-client vehicle warning.
4. Obtain valid controlled 5/10/20-client capacity measurements, with and without plugins, on intended hardware isolated from production workloads. Do not access Crafty or production; no local synthetic result closes this gate.
5. Empty-dependency-cache build bootstrap remains unverified. The two cache-disabled reproducible passes above are valid; do not rerun them without a reason.
6. Keep RC4 as local evaluation material. Do not publish stable until all critical gates pass. Do not touch production/Crafty or modify immutable RC3 assets.

The last process check found no active Gradle client or disposable MultiPaper runtime. Preserve all unrelated tracked and untracked work in the shared worktree.
