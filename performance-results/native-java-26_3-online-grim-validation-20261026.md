# Native Java 26.3 online-mode validation — 2026-10-26

## Scope and fixture

- One official Minecraft Launcher native 26.3 Java client, authenticated to a Microsoft account already signed into the local launcher. No account token, password, or credential file was read or recorded.
- Current candidate source `2265a5a3bf54d1a482c5ac21bd856a9a2674ee32`; Paperclip SHA-256 `B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098`; Master SHA-256 `E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7`.
- Both disposable backends had `online-mode=true` and Bungee address forwarding enabled. The official five backend plugins were present on both with the hashes in `performance-results/official-plugin-artifact-validation-20261026.md`. The test-only `ScheduledTickLockProbe` was also present on both backends; its only automatic behavior is counting piston-retract events in memory, and none of its diagnostic commands were used during this gameplay run. Geyser Standalone 2.12.0 build 1270 ran concurrently on loopback with Floodgate `auth-type: floodgate` and identical private test keys.
- Fixture: `E:\multipaper-tests\system-temp\multipaper-native-java-online-login-20261025-2018`; second runtime logs: `E:\multipaper-tests\system-temp\multipaper-native-java-online-login-20261025-2018\native-login-runtime-2`.
- This verifies Java gameplay on the current candidate with the Master, two Paper backends, and Geyser Standalone running, alongside the five official backend plugins and the test-only probe described above. It is not a real Bedrock/Floodgate client test, an intended-PC capacity test, or a test of the immutable published RC3 JARs.

## Results

1. **Authenticated Java login:** the vanilla 26.3 client connected through the Master proxy. Backend B resolved the authenticated profile UUID, logged the player into the world, and reported the player joined; Backend A received the corresponding remote-player creation. Both backends remained online-mode. The client was not an offline protocol bot.
2. **Survival movement under GrimAC:** the backend was configured for Survival (Peaceful difficulty). A five-second forward walk moved the authoritative position from x=150.5 to x=172.87. A further forward/jump input reached x=181.06. After a server-side teleport to x=190.5, forward movement continued to x=202.58. Both backends' RCON views tracked the moving player within the expected sampling-time difference. The run crossed several chunk coordinates.
3. **GrimAC:** the official GrimAC 2.3.74 read-only SQLite history showed two authenticated sessions and zero violations in each. No matching movement rejection or disconnect appeared in backend logs during the measured movement. This certifies only basic native 26.3 walking, jump input, and movement after teleport; it does not cover knockback, lag, vehicles, Bedrock, or older ViaBackwards clients.
4. **Reconnect:** the client disconnected normally and rejoined about 18 seconds later. The backend resolved the same profile identity and restored the player at the saved position.
5. **Player commands:** an operator grant was made only inside the disposable fixture. Native in-game `/slist` displayed the registered `ender-b` and `ender-a` backends and online player counts. `/mpdebug` enabled its in-world overlay and showed `ender-b` as the serving backend.
6. **Six-component co-start:** both backends reached readiness with Floodgate, ViaVersion, ViaBackwards, InvSee++, and GrimAC enabled; Geyser Standalone reached readiness concurrently. Startup retained GrimAC's warning that ViaBackwards on modern servers has unsupported older-client vehicle behavior, and Geyser's loopback STUN warnings. These limitations remain in scope.

Screenshots of native `/slist` and `/mpdebug` output are `E:\multipaper-tests\system-temp\minecraft-slist-native.png` and `E:\multipaper-tests\system-temp\minecraft-mpdebug-native.png`. The client and every test-owned server process were closed after save/stop; test listeners were clear.

## Interpretation and remaining gates

The prior protocol-777 harness that could not move server-authoritatively does not reproduce native 26.3 walking on the same current-tip stack. That is strong evidence the old synthetic movement trace was not faithful to vanilla movement, but it does not identify the exact packet mismatch. This native result removes basic legitimate Java 26.3 movement and teleport recovery from the open gates; it does not clear older-client GrimAC/ViaBackwards movement, vehicle behavior, knockback, lag, Bedrock/Floodgate authentication, broad Survival builds/farms, or real capacity. The synthetic discrepancy report remains as historical diagnostic evidence and must not be cited as a real-player failure.
