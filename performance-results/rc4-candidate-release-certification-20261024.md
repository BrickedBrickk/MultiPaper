# RC4 candidate scope and current artifact

This historical plugin-stack report contains the tested component versions, hashes, dependencies, official download pages, and prior RC3 compatibility evidence. The current exact RC4 artifact hashes and the additional exact-RC4 runtime results are in `RC4-CANDIDATE-REPORT.md`; use that report for current candidate claims. RC4 Paperclip SHA-256: `C124A7C775814FF9D5E537A2277084360F84DC67F700E158799B0D1E431C26D2`. RC4 Master SHA-256: `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`.

The five backend plugins were revalidated on RC4: ViaVersion 5.12.1, ViaBackwards 5.12.1, Floodgate 2.2.5-SNAPSHOT (b141-81b65cc), InvSee++ 0.31.22, and GrimAC 2.3.74. Geyser Standalone 2.12.0-b1270 started in its separate process with Floodgate auth, but no Bedrock client authenticated. The GrimAC/ViaBackwards older-client vehicle warning remains open.

---
# MultiPaper 26.3 RC3 plugin-stack compatibility — 2026-10-24

## Scope and exact artifacts

The companion [six-component Crafty installation guide](INSTALL-PLUGIN-STACK.md) records the exact tested versions, dependency placement, key handling, startup order, firewall boundaries, and unverified forwarding caveats.

This was a local, disposable validation of the RC3 Master and identical Paperclip on two backends. It did not access Crafty or production and made no changes to the RC3 release. The fixture ran Java 25, with Master coordination/player-proxy ports 33721/33722, backend game ports 33723/33724, and loopback RCON ports 33731/33732. Its logs and saved disposable data are retained at `%TEMP%\multipaper-26.3-plugin-compat-20261024`.

- Paperclip SHA-256: `22B1EE9737A98BA85A52AE09B6B9C6EB890331F9B7A9443BBD3CDEA9357C6460`
- Master SHA-256: `2FF5C0BECABAB90BAACA2EEFC0F074044B0C1BECA65C8A456BDFA53D81F69186`

The fixture was deliberately offline-mode for synthetic Java clients. That validates protocol/plugin behavior only; it does not validate authenticated online-mode.

## Plugin matrix

| Component | Exact tested version / SHA-256 | Result |
|---|---|---|
| Geyser Standalone | `2.12.0-b1270`; `AE06830C47719E29AD34FBFB4692597B60FE22460E4CCB6BA1AB113143102F04` | Runs separately from Paper as required. Started with Floodgate auth pointed at the Master player proxy and opened its loopback Bedrock listener plus NetherNet listener. No Bedrock client joined. Because the test bind address was loopback, Geyser correctly warned that NetherNet clients could not connect; this run does not certify Bedrock login. |
| Floodgate Spigot | `2.2.5-SNAPSHOT (b141-81b65cc)`; `21570AFF9CE17D6983928E8552777760E1EDE5050026B04C686B0AE112E6FD7E` | Loaded and enabled on both backends. The generated keys differed initially; the disposable setup synchronized the same key to both backend configs and Geyser. This matches the [official Floodgate standalone and backend setup](https://geysermc.org/wiki/floodgate/setup/). On Java 25, Floodgate's shaded Guava emitted a terminally deprecated `sun.misc.Unsafe.objectFieldOffset` warning, but Floodgate loaded without a dependency failure or startup exception. Actual Floodgate authentication/identity forwarding remains unverified without a Bedrock client. |
| ViaVersion | `5.12.1`; `BAB94B846E1D2BF516C070562D0271B32382AFD41870BF0D3A7993B73A36F791` | Loaded and enabled on both backends; detected native server version 26.3 (protocol 777). |
| ViaBackwards | `5.12.1`; `4B9DD5CB7662F532B44627F8491255BF96F586CBD00280D1480442590AD4450D` | Loaded and enabled on both backends. A synthetic 1.21.8 Java client completed login through the Master proxy. This was a login smoke only, not legacy gameplay coverage. |
| InvSee++ | `0.31.22`; `01F3E053A814F76D46513EBF65A38747DF11DF81C577D12BED108B353D7775F9` | Loaded on both backends and detected Paper 26.3. Main inventory cross-backend view/edit and reconnect persistence passed. A later local candidate fix passed two cross-backend Ender Chest withdraw/redeposit cycles with exact RCON item conservation; this does not establish that the published RC3 artifact contains the fix. |
| GrimAC | `2.3.74`; `3AC71C0B6EF8AB586C1DD3AB4C0B3254394EF39D048E4985EFB2898B9FEF628C` | Loaded on both backends with its PacketEvents support (2.14.1). Startup warns that GrimAC plus ViaBackwards on a 1.21.2+ server is unsupported and older-client vehicle behavior will have issues. Do not advertise this combination as fully supported for older clients. |

Geyser is a standalone Java program rather than a Paper plugin. Its official [setup guide](https://geysermc.org/wiki/geyser/setup/self/standalone/) supports Floodgate auth and says the standalone setup requires copying Floodgate's key. Geyser's [supported-versions page](https://geysermc.org/wiki/geyser/supported-versions/) currently lists Bedrock 26.30–26.52 and Java 26.3. These are vendor statements, not a substitute for a client join test.

## Runtime checks

- Both backends started with all five Bukkit plugins enabled. `/plugins` listed Floodgate, GrimAC, InvSee++, ViaBackwards, and ViaVersion on both.
- The existing proxy command probe passed login/chunk delivery and `/list`, `/tps`, `/mspt`, `/slist` (both backend names), `/mpdebug`, and `/version` with the combined plugin stack active.
- A 1.21.8 synthetic Java client logged in through the Master proxy with ViaVersion/ViaBackwards enabled.
- The initial same-backend InvSee++ view succeeded. A later, stronger cross-backend validation is recorded below.
- Eight 26.3 synthetic clients all completed login and stayed connected for the measured 31-second moving run; 4,540 synthetic movement packets were sent and there were no disconnects or protocol errors. This is a local stress smoke, not a capacity result.
- The movement bots asserted `onGround=true` while generating movement without normal game-client physics. GrimAC logged GroundSpoof alerts against them (208 alert lines across both backend logs). Those alerts are expected for the malformed bot movement and cannot be used to infer legitimate-player false positives or successful anticheat behavior.
- Geyser Standalone 2.12.0-b1270 started with `auth-type: floodgate`, the shared disposable key, and Master proxy port 33722. It opened the configured listeners, but the loopback-only bind prevented a valid Bedrock/NetherNet client test. The fixture was stopped afterward; its logs remain available.

## Online-mode authentication boundary probe — 2026-10-24

A second isolated fixture used the exact RC3 Paperclip and Master hashes above, with `online-mode=true` and `spigot.yml` `settings.bungeecord=true` on both backends. It used separate disposable ports 33821/33822 for Master, 33823/33824 for backends, and 33831/33832 for loopback RCON. All fixture processes were stopped after the probes; the disposable files remain under `%TEMP%\\multipaper-26.3-online-auth-secure-20261024`.

- `scripts/Verify-OnlineModeProxyAuth.js` passed for a plain unauthenticated protocol client and, after retry, a client supplying forged Bungee-style address/UUID fields. Both received the exact `multiplayer.disconnect.unverified_username` session-verification rejection and neither logged in. One intermediate forwarded probe received `multiplayer.disconnect.authservers_down`; the test correctly failed that attempt rather than treating an unrelated disconnect as proof, and a retry reached the expected rejection. This supports that these offline synthetic clients could not bypass Java online-mode through this configuration, but does not prove successful Microsoft account login or establish availability of Mojang session services.
- Fixed `scripts/Minecraft26_3ProtocolSupport.js` to patch the `minecraft-data` instance resolved by the selected `minecraft-protocol` package, rather than a different top-level copy. The actual auth probe now runs under the existing disposable dependency setup. Its success check requires the explicit session-verification translation key; unrelated disconnects fail.
- Backend Floodgate keys/config were synchronized in this disposable fixture and Geyser Standalone started with `auth-type: floodgate`. No Bedrock client joined, so this is startup/configuration evidence only, not Floodgate identity/authentication or forwarding proof.
- The Master proxy source rewrites an ordinary short handshake by appending the connecting socket address to the hostname; it leaves a hostname already containing NUL-delimited forwarding data unchanged. Floodgate's actual encrypted identity token path was not exercised end-to-end here.

## Cross-backend InvSee++ inventory and permission check — 2026-10-24

Reused the same exact-RC3 disposable plugin fixture and five-plugin backend stack. `scripts/Probe-InvSeeCrossBackend.js` used `/mpdebug`'s local/remote color and backend label to pair a command user on `peerfix-b` with a target on `peerfix-a`. InvSee++ opened the target's inventory. The client moved five diamonds from the target's slot 0 to slot 1 through the real container click packets. Loopback RCON then showed slot 0 empty and exactly five diamonds in slot 1 on both backends. After disconnecting and reconnecting `InvSeeT2` through the Master proxy, both backends still showed the same slot-1 stack. This verifies the cross-backend main-inventory view/edit and the tested reconnect persistence case; it does not cover every ownership-transfer/crash timing.

`scripts/Verify-InvSeePermission.js` connected a non-operator and a separate target, issued `/invsee`, and observed no inventory window for the non-operator during the 9-second observation. The command was issued in the backend log. No explicit permission-denial chat packet was captured, so this proves the unauthorized GUI did not open in that run, not the wording of the denial.

An Ender Chest attempt is **inconclusive**. It injected an emerald into the target after the remote player mirror already existed, without first opening the target's own Ender Chest menu; the owner saw the emerald and the remote `/endersee` view was empty. The persistent `MultiPaperEnderChestHandler` broadcasts through the menu listener, so this setup may have bypassed the normal update path. The client-side Ender Chest opening attempt did not produce an open-window packet. Do not treat this as a confirmed product defect or as a passing Ender Chest test. Follow up by opening the target's own Ender Chest with a tick-complete client interaction, then edit and inspect through `/endersee` across backends.

## Exact-RC3 automated mechanics recheck — 2026-10-24

The exact published RC3 pair and the six-component backend plugin stack were tested in a disposable two-backend world. The corrected [`rc3-mechanics-validation.txt`](rc3-mechanics-validation.txt) records artifact hashes, process ports, test command, and cleanup. The verifier now checks ownership through `ticklockprobe state` on both backends; `execute if loaded` alone had been selecting subscribers in earlier attempts.

- Cross-backend block marker, chest, and shulker reads passed.
- Two hoppers conserved exactly 8 diamonds and 8 iron ingots across a chunk boundary and after ownership handoff.
- Normal piston extension/retraction, observer pulse/reset, and sticky-piston redstone-block pullback passed. Sticky-piston pullback passed with each backend independently confirmed as the authoritative chunk owner.
- No new duplicate UUID or missing-entity request warnings appeared.

This validates automated redstone/hopper mechanics and replication, not player-placed mechanics, farms, portals, pearl stasis, GUI interactions, or human Survival play. Those remain outside this RCON test.

## Release status and next work

This is partial compatibility evidence for the existing RC3 pre-release; it does not satisfy the release gates for stable SMP. Keep the existing tag and assets unchanged. Remaining certification is:

1. Real authenticated Java online-mode login and human Survival, farm, redstone, and portal play on the exact candidate. Automated hopper/piston/observer mechanics passed as recorded above, but player-driven play remains unverified. The user said they will test later; do not ask again.
2. Bedrock join with Geyser Standalone and Floodgate using a real Bedrock client, correct reachable Bedrock/NetherNet bind and ports, shared Floodgate key, and the Master proxy. Confirm Floodgate identity and reconnect behavior.
3. InvSee++ Ender Chest viewing/editing and item conservation passed on the local changed candidate in a scripted synthetic Java test. The test moved six emeralds through two withdraw/redeposit cycles with matching owner/mirror counts and no loss/duplication. The published RC3 asset predates the local fix; RC3 itself has not passed this gate. Main inventory cross-backend editing, reconnect persistence, and absence of an unauthorized non-operator GUI also passed their disposable checks.
4. GrimAC false-positive behavior with legitimate client movement. Resolve or explicitly accept the GrimAC/ViaBackwards older-client vehicle warning before claiming the full six-component stack is compatible.
5. Intended-hardware capacity on the two target PCs using isolated disposable workloads. The localhost synthetic soak is not a supported player-count claim; do not access production or Crafty.
6. Exact-tag cache-disabled reproducibility is not proven by this plugin test. The RC3 build/test and published artifact checksums remain as recorded in the checkpoint; do not start an overlapping build.

Do not publish a stable release until all critical gates pass. No source fix or rebuild was justified by this plugin smoke, and the RC3 pre-release was left unchanged.

### Fresh clean-world follow-up

On the retained exact-RC3 two-backend fixture `%TEMP%\multipaper-26.3-rc3-plugin-entity-isolation-20261024`, all five Bukkit plugins started on both backends. A fresh world emitted one transient `Entity uuid already exists` warning for a bat in neighboring chunks during an eight-client login probe. After `save-all flush`, orderly backend shutdown, and process exit, the entity MCA scan was 55 records / 55 unique UUIDs / 0 persisted duplicates; the warned UUID was absent from the saved entity records. This is not a reproduction of the earlier persisted duplicate-entity defect; no source change was made. The attempted capacity probe is invalid: all eight stationary synthetic clients logged in and had RCON-validated positions, then GrimAC timed them out. No real capacity claim follows from it.

### InvSee++ Ender Chest synthetic-client follow-up

The disposable probe was updated to send player-input/tick-end heartbeats without synthetic position or on-ground claims. In the exact-RC3 plugin fixture with GrimAC enabled, an operator on `peerfix-b` and target on `peerfix-a` formed a confirmed cross-backend pair. RCON seeded one emerald and placed an Ender Chest in the target's reach; the block-use attempt still did not yield an open-window packet. GrimAC disconnected the stationary synthetic target for `disconnect.timeout` about 60 seconds after login, before the chest could be edited. A final follow-up created a stone support platform beneath the target, placed the chest beside it, and teleported the target onto the platform before block-use. There was still no open-window packet, and the server logged repeated `moved too quickly` corrections during client startup. This protocol bot is not a reliable vanilla movement/interaction client. Both backends were save-flushed and stopped; Master was stopped after they exited, Geyser remained stopped, and ports 33721–33732 are closed. Detailed log: `performance-results/invsee-cross-backend-validation-20261024.txt`.

This remains inconclusive, not evidence of an InvSee++ or MultiPaper defect and not a passing test. Do not exempt bots from GrimAC. A valid completion needs a real client or a physically coherent client that remains connected under the unchanged anti-cheat, then menu-listener-backed item transfer and equal-count checks across both backends and reconnect.

### Later local-candidate Ender Chest fix — 2026-10-24

The in-place mirror-edit defect was reproduced on a changed local candidate and fixed in `MultiPaperEnderChestHandler`; details and exact candidate artifact hashes are in `../invsee-enderchest-cross-backend-regression-20261024.txt`. The checked-in `scripts/Verify-InvSeeEnderChestCrossBackend.js` runner passed with the same six-plugin pair, no teleporting, and GrimAC unchanged. Two withdrawals left both target Ender Chests empty and the operator with exactly six emeralds; the intervening redeposit was present on both backends. This is candidate evidence, not evidence that the immutable published RC3 Paperclip includes the fix.


## Exact-RC4 ViaBackwards login smoke — 2026-10-24

With the exact RC4 Master and two backend Paperclip JARs, the full five-plugin stack enabled on both backends. A synthetic offline-mode Java 1.21.8 client connected through the Master proxy, completed login, and received 117 map chunks. This demonstrates a basic ViaBackwards login/chunk path on RC4 only; it does not validate older-client vehicle behavior or legitimate-client GrimAC compatibility. The known GrimAC warning remains open.
