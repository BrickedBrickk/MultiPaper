# MultiPaper 26.3 RC4 evaluation handoff

Updated: 2026-10-26 (America/New_York)

## Candidate and publication

- Source candidate: `feature/port-26.3` at `2265a5a3bf54d1a482c5ac21bd856a9a2674ee32` (`Share world UUIDs across MultiPaper backends`). Previous candidate fixes retained: `d96c50a8f` fragmented proxy handshakes and `28a0a572b` Paperweight dependencies/handshake fields.
- The authenticated GitHub account is `BrickedBrickk`; `BrickedBrickk/MultiPaper` is its public fork of `MultiPaper/MultiPaper`, with admin/push permission. Its `feature/port-26.3` branch already equals the candidate commit. `origin` still points to `PurpurMC/Purpur`; `.git/config` shows no repository metadata explaining that URL. Git history and the fork API confirm the candidate derives from MultiPaper upstream, not Purpur. Do not push through `origin`; a separate fork remote is the verified route.
- Exact evaluation package: `release-staging/MultiPaper-26.3-RC4-evaluation-2265a5a-20261026-final.zip`, 83,576,869 bytes, SHA-256 `2B140EDEEEC982AE1EF4085B02B9C49C1401F1C8E65EEC9C8FDE06AEA8E83E2C`. Sidecar, all 30 payload hashes, the two JAR hashes, and the package exclusion audit passed. Contents: 32 ZIP entries including manifests; exactly the Master and Paperclip JARs, docs, monitoring, scripts and selected evidence. No keys, credentials, worlds, raw logs or caches were found.
- Paperclip SHA-256: `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`.
- Master SHA-256: `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- This is an evaluation prerelease candidate, never stable. Do not change or replace RC3.

## Build and verification

- Existing exact-commit clean builds used Temurin Java 25.0.3.9 and Gradle 9.8 in an isolated worktree. Two sequential no-cache builds passed; each reported 188 suites, 10,745 tests, zero failures/errors and 88 skipped. Product artifacts were byte-identical. Logs are `%TEMP%\multipaper-clean-build-2265-current-tip-20261025.log` and `...pass2-20261025.log`. No product source/build input changed after these builds; the current worktree's later changes are support scripts, monitoring and documentation.
- Re-ran monitor unit tests: 6/6 passed. Version command returned `1.0.0`; `git diff --check` passed.
- Synthetic monitor cost was 15.781 ms CPU per 10-second poll (about 0.158% of one core), peak RSS 24,002,560 bytes. This is a synthetic Windows estimate, not target-PC or live-server overhead.

## Verified candidate behavior

- Exact current-tip pair: six-component startup and command smoke; shared world UUIDs; cross-backend block/entity/hopper/piston/observer replication; InvSee++ cross-owner edits, reconnect and Ender Chest conservation; permissions; Nether and End travel; fragmented proxy handshake handling; online-mode proxy identity boundary; scheduled tick and entity handoff evidence; pearl restart/stasis evidence; native authenticated Java 26.3 login, basic movement, reconnect, `/slist` and `/mpdebug`.
- The exact RC3/current-tip A/B reproduced stale shared-world UUID routing and Ender Chest mirror problems on RC3 and passed on RC4. RC3 remains unchanged.
- Official plugin artifact hashes were revalidated for Geyser Standalone, Floodgate, ViaVersion, ViaBackwards, InvSee++ and GrimAC. All five backend plugins loaded on both backends with loopback Geyser ready.
- Evidence sources include `performance-results/current-tip-rc4-validation-20261025.md`, `performance-results/official-plugin-artifact-validation-20261026.md`, `performance-results/native-java-26_3-online-grim-validation-20261026.md`, `performance-results/current-tip-end-portal-transfer-20261025.md`, `performance-results/rc3-current-tip-live-block-entity-regression-20261026.md`, `performance-results/rc3-vs-current-tip-enderchest-20261025.md`, `performance-results/player-owned-pearl-peer-restart-direct-owner-20261025.md`, and the durable scheduled-tick reports cited by `RELEASE_CHECKPOINT.md`.

## Open gates and limitations

- No authenticated Bedrock/Floodgate client session; the local Bedrock launcher required an update and its launch failed. Geyser/Floodgate startup was verified only.
- No legitimate older ViaBackwards client, vehicle, knockback or lag test. GrimAC warns about unsupported older-client vehicle behavior with ViaBackwards on modern servers.
- No broad human Survival or repeatable automatic farm/collection certification. Direct observer-to-piston replication passed; the farm circuit did not produce a dependable harvest in the exact-pair control. Do not claim farm support from the harness-triggered control.
- No isolated 5/10/20-player capacity certification on the two intended PCs. Existing Java workloads were observed and left untouched. The 5–20-player monitored beta is an evaluation target, not demonstrated capacity.
- Monitor can report Paper TPS/MSPT, CPU/RAM/disk/network, player count, and recognized backend/plugin/tick/inventory/portal/GrimAC events only when configured sources emit them. Master ownership, transfers, chunk replication and tick internals may not be exposed by current logs; absent metrics remain absent. RCON is optional and restricted to loopback `tps`, `mspt`, and `list`.

## Monitoring and topology

- Package setup and safe collection: `monitoring/README.md`; manual Crafty topology: `monitoring/MANUAL-CRAFTY-TOPOLOGY.md`; routing: `monitoring/NETWORK-ROUTES.md`; restore: `monitoring/BACKUP-RESTORE.md`; two-week plan: `monitoring/BETA-TEST-PLAN.md`.
- PC1: Master/proxy, Backend 1 and Geyser Standalone. PC2: Backend 2. Keep backend listeners private and use LAN synchronization; publish only the Java TCP and supported Bedrock tunnel via Playit. Use Tailscale for administration. Follow the manual Crafty instructions after downloading; no Crafty host was accessed for deployment or changed.
- Logs are bounded/rotated with retention controls; exports include allowlisted sanitized telemetry only. The collector does not store chat, passwords, IPs or authentication keys. Start with an idle/normal-load overhead comparison on the actual hosts before relying on the monitor.

## Cleanup audit

- Read-only inventory and exact proposed deletion manifest: `performance-results/multipaper-inventory-and-proposed-cleanup-20261026.md`.
- Nothing was deleted and Crafty hosts were not modified. Three superseded ZIPs (total 254,163,044 bytes) and one 148-byte scratch placeholder have verified off-volume copies at `E:\multipaper-tests\cleanup-backups\preapproval-20261026\`. They remain pending explicit user review. Preserve all worlds, backups, active workloads, services and unrelated files. No repeated Temp scan was run.

## Manual installation and rollback

1. Download the evaluation ZIP and verify its SHA-256 against the adjacent `.sha256` file before extracting it.
2. Read `README.txt`, `INSTALL-CRAFTY.txt`, `INSTALL-PLUGIN-STACK.md`, `OFFICIAL-PLUGIN-ARTIFACTS.md`, and `monitoring/README.md` in the archive. Install only into a new disposable Crafty server setup, keeping both existing production backends and their worlds untouched.
3. On PC1 create the Master/proxy, Backend 1 and Geyser Standalone servers. On PC2 create Backend 2. Use the included example configurations and keep backend ports private; expose only the two Playit tunnels. Do not copy test keys or test fixture data.
4. Before any trial, stop the beta cleanly, take and verify a complete offline backup of each backend/world plus Master state, and record the baseline. The two-week checklist calls for staged 5/10/20-player observation only when capacity and safety gates permit.
5. To roll back, stop all beta components cleanly, preserve diagnostics, restore the matching full pre-beta backups to the same server identities, reinstall the prior approved JAR/plugin set, and verify world/player data before reopening access. Do not restore beta data over production.

## Cleanup and process state

- Latest recorded check found no Gradle/Minecraft Java process or disposable test listener active. Test-owned services were stopped; no ports were left open. No production process was stopped or modified.
- The worktree contains many prior unrelated/untracked test outputs, plugin downloads, logs and release archives. Preserve them; commit only reviewed source/docs artifacts. Do not add raw logs, worlds, credentials or the large ZIP to the source commit.
