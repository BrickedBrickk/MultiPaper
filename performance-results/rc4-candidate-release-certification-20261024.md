# MultiPaper 26.3 RC4 candidate validation (2026-10-24)

Source commit: `f891db1` on `feature/port-26.3`. This is a local evaluation package only; it is not a stable release and must not replace the immutable published RC3.

## Build evidence

Temurin Java 25.0.3, Gradle 9.8, offline dependencies, `--no-build-cache`, fixed manifest time `2026-10-24T20:01:55.854680300Z`, `BUILD_NUMBER=4`. Two clean-output build/test/package passes completed successfully from exact source `f891db1`; each reported 186 suites, 10,740 tests, 0 failures, 0 errors, 88 skipped. Paperclip, bundler, server, and Master JAR hashes were identical across both passes. The regular Gradle dependency cache was available; empty-cache bootstrap is not certified. Detailed procedure/logs are in the repository file `performance-results/current-candidate-clean-build-certification-20261024.txt` and `%TEMP%\multipaper-rc4-release-build-f891db1.log` plus its repeat log.

## Exact candidate JARs

- Paperclip: `multipaper-paperclip-26.3-RC4.jar`, SHA-256 `C124A7C775814FF9D5E537A2277084360F84DC67F700E158799B0D1E431C26D2`.
- Master: `multipaper-master-3.0.0.jar`, SHA-256 `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`.
- Bundler/server outputs were built and hashed for reproducibility but are not included; the Crafty deployment guide uses the Paperclip and Master JARs.

## Exact-RC4 disposable runtime check

A local Java 25 fixture ran Master plus two identical RC4 backends, all five backend plugins, Geyser Standalone/Floodgate configuration, and two ViaProxy routes. Both backends reached `Done` and registered with Master. Both loaded ViaVersion 5.12.1, Floodgate 2.2.5-SNAPSHOT (b141-81b65cc), ViaBackwards 5.12.1, InvSee++ 0.31.22, and GrimAC 2.3.74. The checked-in Mineflayer runner connected one synthetic client to each distinct backend route and passed two remote `/endersee` withdrawal/redeposit cycles; all six emeralds remained conserved in owner/mirror/inventory assertions. Both backends were save-flushed and stopped; fixture processes and ports were closed afterward.

This fixture used offline mode, loopback-only RCON and Geyser listeners, and synthetic Java clients. It does **not** certify authenticated online-mode login, human Survival/farm/redstone/portal gameplay, Bedrock/Floodgate identity/reconnect, legitimate GrimAC behavior, older-client vehicle behavior, intended-hardware capacity, or public beta readiness. GrimAC emitted its known warning that ViaBackwards with GrimAC on a 1.21.2+ server has unsupported older-client vehicle behavior. Treat those gates as open.

Logs and disposable world data are retained under `%TEMP%\multipaper-26.3-rc4-plugin-runtime-f891db1`. The RCON secret is not part of this package.

## Release disposition

Keep this package local for controlled evaluation. Do not publish stable while the open player/authentication, Bedrock, GrimAC compatibility, intended-hardware capacity, and later human gameplay gates remain unresolved. Do not access Crafty or production for this candidate.

## RC4 Master command and InvSee++ permission smoke — 2026-10-24

A second run used the exact RC4 Paperclip on both disposable backends and the exact Master JAR. Two synthetic clients connected through the Master proxy with the full five-plugin stack enabled.

- `/mpdebug` returned `MultiPaper debug enabled` to the operator client.
- `/slist` returned `There are 1 out of 20 players online`, followed by entries for both `backend-a` and `backend-b`.
- The client completed login and received 117 map chunks.
- `scripts/Verify-InvSeePermission.js` passed through the Master proxy: a non-operator issuing `/invsee` received no inventory GUI.
- Both backends were save-flushed and stopped; Master and ViaProxy fixture processes were stopped; ports 31821–31834 are closed.

This confirms these command routes and the checked permission boundary on the RC4 local candidate. It remains synthetic offline-mode evidence, not authenticated human-gameplay or anti-cheat validation. Logs: `%TEMP%\multipaper-26.3-rc4-plugin-runtime-f891db1\command-*.stdout.log`.
