# MultiPaper 26.3 current-tip RC4 evaluation — 2026-10-25

## Candidate and reproducible build

- Source: `feature/port-26.3` at `2265a5a3bf54d1a482c5ac21bd856a9a2674ee32`.
- Clean worktree: `E:\multipaper-builds\MultiPaper-26.3-current-tip-2265-20261025`; Java Temurin 25.0.3.9, Gradle 9.8. Fresh Paperweight patch application succeeded. Two sequential `clean build :multipaper-server:createPaperclipJar :multipaper-server:createBundlerJar :multipaper-master:build :multipaper-mastermessagingprotocol:test --no-build-cache --no-daemon --max-workers=2` runs succeeded. Each reported 188 suites, 10,745 tests, 0 failures/errors, 88 skipped. The two builds emitted byte-identical artifacts. Logs: `%TEMP%\multipaper-clean-build-2265-current-tip-20261025.log` and `%TEMP%\multipaper-clean-build-2265-current-tip-pass2-20261025.log`.
- Exact staged artifact SHA-256 values are recorded in `release-staging/MultiPaper-26.3-RC4-current-tip-2265-20261025/SHA256SUMS.txt`:
  - Paperclip `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`
  - Master `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`
  - Bundler `B6979A6B4071849E8DB2B8AD8E8BE6FC3F64162B211B31F3D45910AAFB0CBD6F`
  - Server `23BDD4B054395DD0BB36D07D0099D0D948ED20A600ABE960E08F804229924E1D`
  - Master messaging `0C1F9690E33C02865AF0ADB4E396D277CD3A41F0AEC2FD5B2FB7692F1F59B97A`

## Six-component runtime versions

The table below now records exact upstream plugin artifacts confirmed by the 2026-10-26 canonical-source rerun; both backend copies matched. The earlier ViaVersion/ViaBackwards 5.12.1 fixture hashes did not match the official release assets (their only different class constants had truncated build identifiers), so the six-component checks were rerun with the official JARs. Full provenance and results: `performance-results/official-plugin-artifact-validation-20261026.md`. GrimAC had no separate PacketEvents JAR because its Bukkit build shades PacketEvents support.

| Component | Tested build | SHA-256 | Exact current-tip evidence |
|---|---|---|---|
| Geyser Standalone | 2.12.0 build 1270 | `AE06830C47719E29AD34FBFB4692597B60FE22460E4CCB6BA1AB113143102F04` | Concurrent startup and loopback UDP bind; no authenticated Bedrock client. |
| Floodgate Spigot | 2.2.5 build 141 | `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E` | Loaded on both backends with matching disposable key; no real Bedrock identity. |
| ViaVersion | 5.12.1 | `B8033807E7182C35003A45962352DD642C1E91880CA281DA3A43CB6F481A95A2` | Exact official release JAR loaded on both; synthetic 26.3 login, chunks, commands and status passed. |
| ViaBackwards | 5.12.1 | `29D94D3A26C85C8A327574B2322DB4078992AE457F0173325431A12AAC384911` | Exact official release JAR loaded with ViaVersion on both; older-client legitimate gameplay remains unverified. |
| InvSee++ | 0.31.22 | `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9` | Exact official Modrinth JAR; cross-owner inventory edit/reconnect and canonical Ender Chest conservation cycles passed. |
| GrimAC | 2.3.74 | `3AC71C0B6EF8AB586C1DD3AB4C0B3254394EF39D048E4985EFB2898B9FEF628C` | Loaded on both; warns that this ViaBackwards-on-1.21.2+ arrangement is unsupported for older-client vehicles. Legitimate movement remains unverified. |

Stable version provenance was checked in the exact-RC3 report and official project metadata. The current startup logs' Grim update checker notices a `2.3.75-d8b8ae4` development build; the official [GrimAC releases page](https://github.com/GrimAnticheat/Grim/releases) identifies 2.3.74 as the stable release. Do not replace the tested stable build with an unverified development build merely to suppress its warning.

## Exact artifact runtime coverage

All tests below used the exact staged Paperclip/Master hashes above, isolated disposable data, and loopback listeners.

- Six-component Master/two-backend/Geyser stack smoke: plugin enable on both backends; synthetic 26.3 client login/chunks and `/list`, `/tps`, `/mspt`, `/slist`, `/mpdebug`, `/version`; protocol status 777. Geyser Standalone started.
- Proxy handshake fragmentation: unfragmented and splits of 1, 2, 4, and 20 bytes passed with Bungee forwarding enabled.
- Online-mode forwarding boundary: backend rejected both unauthenticated and forged forwarding identities (`multiplayer.disconnect.unverified_username`). No valid Microsoft account flow was exercised.
- Mechanics: cross-owner block/entity state, chest/shulker block entity, hopper conservation, piston movement/retraction, observer pulse/reset, sticky piston redstone-block return, and entity routing/teleport/removal passed. The PowerShell wrapper emitted a stale `$LASTEXITCODE` error after its individual assertions passed; treat the assertions/logs as evidence and the wrapper exit as unreliable.
- InvSee++ cross-owner inventory edit/reconnect passed; the exact five-item stack persisted only in the edited slot after ownership changed.
- Nether portal activation/transfer passed through the disposable two-backend setup.
- Farm: one exact-pair run at random tick speed 30 observed natural growth to y=65; the harness supplied a temporary redstone-block pulse, filtered the sugar-cane item, removed power, and confirmed piston retraction and y=64 base cane on both backends. This verifies a harness-triggered harvest once, not a fully automatic farm. A fresh no-assist observer-only rerun on the exact current-tip pair started the full five-plugin stack plus Geyser on loopback, manually placed the upper cane segment, and observed for 10 seconds: no item appeared and the piston remained retracted. The same circuit also failed on stock Paper 26.3 build 223; a direct observer-to-piston stock-Paper control passed. An older apparent observer-control pass is invalid because that harness version injected redstone power. No MultiPaper-specific defect is confirmed; automatic farm behavior remains uncertified. Exact rerun: `performance-results/current-tip-observer-farm-repro-20261025.md` and `E:\multipaper-tests\system-temp\multipaper-rc4-observer-only-check-final-20261025`.

## Follow-up observer control — 2026-10-26

On the exact current-tip pair with the canonical official plugin stack enabled on both backends and Geyser running, a valid direct observer-to-piston control passed three alternating target-block changes. Each update extended/retracted the piston and moved cargo, and the final state replicated to both backend views. This proves the direct observer signal and owner replication; it does not validate the separate sugar-cane farm's redstone-wire geometry, automatic growth/harvest, item collection, or Survival gameplay. Full fixture and trace: `performance-results/current-tip-observer-direct-control-20261026.md`.

## GrimAC suspicious-movement control — 2026-10-26

The exact current-tip pair, all five official backend plugins, and Geyser running together detected a deliberately impossible protocol-777 movement stream. The bot sent coherent `player_input`/position/`tick_end` cadence while claiming normal forward input and moving 1 block each tick. GrimAC stored 251 Simulation, 252 GroundSpoof, and 3 Timer violations in the backend owning its actual spawn chunk; backend logs also showed vanilla movement corrections. This verifies detection of the deliberate exploit-shaped trace only. It does not certify legitimate Java/Bedrock behavior, false-positive rates, or player capacity. Full details and trace: `current-tip-grim-suspicious-movement-20261026.md`.
- InvSee++ Ender Chest: the exact current-tip Master/two-backend pair passed `scripts/Test-CurrentTipInvSeeEnderChest.ps1` with all five plugins on both backends and Geyser running concurrently. A synthetic ViaBackwards 26.1 client deposited six emeralds into the target's Ender Chest; an admin on the other backend withdrew and redeposited them, then withdrew again. Owner chest, remote chest mirror, target inventory, and admin inventory totaled exactly six emeralds at each asserted transition. This does not test a legitimate 26.3 account or crash timing. Logs and result: `%TEMP%\multipaper-current-tip-enderchest-fixed-2265-20261025`.
- Exact published RC3 baseline comparison: immutable Paperclip `22B1EE9737A98BA85A52AE09B6B9C6EB890331F9B7A9443BBD3CDEA9357C6460` and Master `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`, with the same plugin JARs, test runner, disposable topology, and Geyser concurrently running, failed the first remote-mirror assertion after the target deposited six emeralds: owner Ender Chest `6`, remote backend mirror `0`. This test did not proceed to remote withdrawal, so it proves stale mirrored state, not an observed item loss or duplication. The current-tip pair passed the same test and subsequent cycles. The persistent fix is in `e41e53c` plus follow-up `0e15bd8`: the Paperweight source patch adds the Ender Chest `setChanged` hook and the handler broadcasts remote-player container changes while suppressing echo during inbound updates. See `patches/server/0025-Sync-enderchests.patch` and `multipaper-server/minecraft-patches/sources/0076-MultiPaper-sync-enderchest-container-changes.patch`.
- Harness iteration note: the first draft of the new PowerShell wrapper wrote the Java proxy port with the wrong YAML indentation in the disposable Geyser config, so Geyser failed configuration parsing before any test client was started. The wrapper now edits indentation-specific keys and fails fast on startup errors; both the corrected current-tip run and the exact-RC3 comparison completed with the results above. The failed fixture is retained at `%TEMP%\multipaper-current-tip-enderchest-2265-20261025` for audit; it is not a product failure.

Detailed scripts and logs: `scripts/Test-DisposableSixComponentStack.ps1`, `scripts/Test-ProxyHandshakeFragmentation.ps1`, `scripts/Test-OnlineModeProxyBoundary.ps1`, `scripts/Verify-TwoBackendReplication.ps1`, `scripts/Probe-InvSeeCrossBackend.js`, `scripts/Verify-InvSeeEnderChestCrossBackend.js`, `scripts/Test-CurrentTipInvSeeEnderChest.ps1`, `scripts/Verify-DisposablePortalActivation.ps1`, and `%TEMP%\multipaper-current-tip-*` fixtures/logs. The exact-RC3 baseline logs are under `%TEMP%\multipaper-rc3-enderchest-test-20261025`.

## Startup warning inventory

Both current-tip backends reached readiness and `/plugins` showed all five required plugins enabled. No plugin dependency failure or startup exception prevented the six-component smoke or Ender Chest flow. The fixture is not warning-free: GrimAC logged the unsupported ViaBackwards older-client vehicle warning; backend `online-mode=false` emitted the expected insecure-mode warning for the synthetic offline fixture; Windows OSHI/Perflib emitted an ERROR and process-stat NPE warnings; the synthetic smoke bot generated repeated “moved too quickly” warnings; and Geyser loopback mode emitted a warning that real clients cannot connect to a loopback-only Bedrock address, plus STUN/NetherNet send failures. Java 25 also emitted Floodgate/Geyser Unsafe/native-access deprecation warnings. These environment/test-mode warnings were not suppressed and are not represented as production-safe behavior. Exact logs: `E:\multipaper-tests\multipaper-current-tip-2265-six-component-20261025\six-component-smoke-20261025-180930` and `%TEMP%\multipaper-current-tip-enderchest-fixed-2265-20261025`.

## Release decision and remaining gates

This is a local unpublished evaluation candidate, not a stable or public beta release. Completed entity synchronization, durable scheduled-tick handoff/recovery, and prior capacity evidence are preserved; do not repeat without a fresh regression or a justified current-tip gap.

Critical/open gates: real online-mode Java login and human Survival play (the user said they will test later; do not ask again), authenticated Bedrock/Floodgate identity and reconnect, legitimate-client GrimAC/ViaBackwards compatibility, fully automatic/repeatable farm growth and harvest on current exact artifacts, and isolated intended-hardware capacity measurements. Target machines had existing Java workloads during the last availability check, so no competing benchmark was run. No Crafty or production access; no release publication.
