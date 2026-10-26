# Official plugin artifact and exact-stack validation — 2026-10-26

## Candidate and official artifacts

Both runtime tests used the same exact current-tip server pair: Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`, Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.

| Component | Exact tested build | SHA-256 | Official artifact source and verification |
|---|---|---|---|
| Geyser Standalone | 2.12.0 build 1270 | `AE06830C47719E29AD34FBFB4692597B60FE22460E4CCB6BA1AB113143102F04` | [Geyser official build API](https://download.geysermc.org/v2/projects/geyser/versions/2.12.0/builds/1270/downloads/standalone); official API hash matches. |
| Floodgate Spigot | 2.2.5 build 141 | `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E` | [Floodgate official build API](https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/spigot); official API hash matches. |
| ViaVersion | 5.12.1 | `B8033807E7182C35003A45962352DD642C1E91880CA281DA3A43CB6F481A95A2` | [Official ViaVersion 5.12.1 release](https://github.com/ViaVersion/ViaVersion/releases/tag/5.12.1); GitHub asset digest matches. |
| ViaBackwards | 5.12.1 | `29D94D3A26C85C8A327574B2322DB4078992AE457F0173325431A12AAC384911` | [Official ViaBackwards 5.12.1 release](https://github.com/ViaVersion/ViaBackwards/releases/tag/5.12.1); GitHub asset digest matches. |
| InvSee++ | 0.31.22 | `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9` | [Official Modrinth 0.31.22 version](https://modrinth.com/plugin/invsee-plus-plus/version/LmdQx1rG); Modrinth SHA-512 matches the tested JAR. |
| GrimAC | 2.3.74 | `3AC71C0B6EF8AB586C1DD3AB4C0B3254394EF39D048E4985EFB2898B9FEF628C` | [Official GrimAC 2.3.74 version](https://modrinth.com/plugin/grimac/version/baDrVvfo); Modrinth SHA-512 matches the tested JAR. GrimAC shades PacketEvents; no separate PacketEvents JAR was installed. |

The JARs above were present with identical hashes on both backends. Geyser Standalone ran separately and its official artifact hash was also verified. The exact six-component test scripts now reject unexpected plugin hashes before starting.

The first candidate fixtures had ViaVersion/ViaBackwards 5.12.1 JARs whose only differing ZIP entries were `VersionInfo.class` constants with shortened commit IDs; those JAR hashes did not match the official GitHub/Modrinth release assets. InvSee++ matched the official Modrinth artifact exactly; its Hangar distribution carries a different `download-source.txt` marker. The validation below was repeated with the exact official Via release JARs and the exact official Modrinth InvSee++ JAR. This replaces the earlier ambiguous provenance for the six-component startup, command, mechanics, and Ender Chest results.

## Exact official-stack runtime results

Two disposable tests used Paperclip/Master above and the exact plugin hashes in the table:

1. Six-component Master/two-backend/Geyser smoke: all five plugins loaded on both backends; Geyser Standalone reached ready and bound loopback UDP; a synthetic Minecraft 26.3 protocol-777 client logged in, received chunks, and received `/list`, `/tps`, `/mspt`, `/slist` (both `ender-a` and `ender-b`), `/mpdebug`, and `/version`. The Master proxy status query returned protocol 777 and MultiPaper 26.3. The full two-backend mechanics suite passed cross-owner marker blocks, chest/shulker data, exact hopper item conservation, piston extension/retraction and movement, observer pulse/reset, sticky-piston redstone-block return under both backend owners, and entity spawn/teleport/removal/dimension transfer. No duplicate UUID or missing-entity request warnings increased. Logs: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-full-sixstack-20261026\six-component-smoke-20261025-192554`.
2. InvSee++ Ender Chest: target deposited six emeralds, remote admin withdrew them, redeposited them, and withdrew them again. At each transition the owner chest, remote mirror, and both player inventories totaled exactly six. All five plugins were enabled on both backends while Geyser Standalone was running. Logs: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-stack-enderchest-20261026\logs-20261025-191946`.
3. Portal activation: the canonical five-plugin backend stack and exact current-tip Master/Paperclip pair activated a vanilla Nether portal; the synthetic 26.3 client transferred to `minecraft:the_nether` and received the respawn packet. Geyser was not running in this focused portal test; it was concurrently running in tests 1 and 2. Logs: `E:\multipaper-tests\system-temp\multipaper-current-tip-official-portal-20261026\portal-activation-20261025-192811`.

An earlier canonical-smoke attempt reported missing `/slist` because its fixture names were `ender-a`/`ender-b` while the probe expected `smoke-a`/`smoke-b`. Server logs confirmed `/slist` was executed. The harness now accepts expected backend names; rerunning with the actual names passed. This was a test assertion mismatch, not a server-command failure.

## Limits and warnings

- This proves the exact official plugin binaries start and pass the scripted checks above. It is not a real authenticated Microsoft Java login, a real Bedrock/Floodgate identity, a GUI session, or broad ViaBackwards gameplay coverage.
- GrimAC 2.3.74 still warns that the ViaBackwards-on-1.21.2+ arrangement is unsupported for older-client vehicle behavior. Legitimate Java/Bedrock movement, vehicles, lag, teleportation, knockback, and false-positive checks remain open.
- Geyser was bound to loopback; no remote Bedrock join or Floodgate identity was exercised. Java test backends were in offline mode for synthetic clients; this does not certify authentication or public forwarding.
- The automated farm test remains uncertified. Intended i5-7600/i5-6600 capacity remains unmeasured in an isolated run. No stable/public beta approval follows from this validation.
- Java 25/Geyser/Floodgate native-access and Unsafe deprecation notices, the expected offline-fixture warning, Windows OSHI/Perflib errors, and loopback Geyser signaling warnings were retained. No plugin dependency failure prevented readiness.

No Crafty or production services/worlds were accessed. All test-owned processes and listeners were stopped.
