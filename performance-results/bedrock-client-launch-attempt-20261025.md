# Current-tip Bedrock-client launch attempt — 2026-10-25

## Disposable fixture

- Exact current candidate: Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`; Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- Geyser Standalone 2.12.0 build 1271 was downloaded from the official Geyser build service and verified against its API SHA-256 `DF30AFB9970F3A71291EE6C96B5FB21E59FBF6D48A3C0C3F4877FCB096C4E314`. Official current support documentation lists Java 26.3 and Bedrock 26.30–26.52. Build metadata says channel `default`, `promoted=false`.
- Floodgate Spigot 2.2.5 build 141 / SHA-256 `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E` was on both backend copies. The test-only Geyser key fingerprint matched both backend key fingerprints; key contents were not recorded.
- Both backends had `online-mode=true`, Bungee address forwarding enabled, the five canonical backend plugins, and the passive test-only `ScheduledTickLockProbe`. Geyser Standalone used `auth-type: floodgate` and connected to the local MultiPaper Master proxy. Its Bedrock listener bound only `192.168.0.103:32672`; NetherNet used UDP 32673 and RakNet was enabled on UDP 32672.
- No production or Crafty access occurred. No production credentials or Floodgate key contents were read.

## Runtime result

- Master, both Paper backends, and Geyser started. Both backends reached `Done`; Floodgate loaded and enabled on both. Geyser 2.12.0-b1271 reported its listener and built-in signaling ready. Logs: `E:\multipaper-tests\system-temp\multipaper-native-java-online-login-20261025-2018\bedrock-validation-20261026\logs-20261025-203710`.
- The installed Microsoft Bedrock package was `Microsoft.MinecraftUWP_1.26.5203.0`. The official Minecraft Launcher displayed **Update** instead of **Play** on its Bedrock page. Directly starting the installed executable returned `0x87E50035` and no Bedrock client process remained. No client update was applied.
- Therefore this run proves current Geyser/Floodgate startup and configuration only. It did not exercise a Bedrock handshake, Xbox identity, Floodgate UUID/name, movement, gameplay, disconnect, or reconnect. It is not a pass for real Bedrock support or Floodgate identity.

The disposable backends were flushed and stopped; all test listeners on 32651–32673 were verified closed. The pre-existing launcher was returned to its Java Edition page.

Official sources: [Geyser supported versions](https://geysermc.org/wiki/geyser/), [Geyser standalone build API](https://download.geysermc.org/v2/projects/geyser/versions/2.12.0/builds/1271), and [Floodgate standalone setup](https://geysermc.org/wiki/floodgate/setup/standalone/).
