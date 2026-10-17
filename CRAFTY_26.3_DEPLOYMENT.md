# MultiPaper 26.3 on two Windows PCs with Crafty

This guide describes deployment for the current fork. Two isolated MultiPaper 26.3 backends have been started together on one Windows host against a real Master. Checks include two simultaneous clients, mirrored player inventory, bidirectional block changes, entity spawn subscription and position updates, redstone, and player-driven cross-backend chest interaction and item transfer. These checks do **not** verify two physical PCs, sustained survival gameplay, or crash recovery during active edits; keep the system closed to players until the remaining checks at the end of this guide pass.

## Build and test status

On 2026-10-16, `:multipaper-server:build` and `:multipaper-server:createPaperclipJar` completed successfully after the external-player client-loaded and hidden-entity synchronization changes. The server suite passed 10,103 tests with 0 failures, 0 errors, and 86 skipped; `scanJarForBadCalls` passed. Test output included the existing asynchronous inventory warning from `PlayerInventoryTest.testCanHold`. The focused handshake-forwarding suite passed 4/4 tests. On 2026-10-17, the Master suite passed 19 tests: 16 protocol/storage integration tests, one region-cache close/reopen test, one separate-process graceful-restart test, and one separate-process abrupt-termination recovery test after the Master acknowledged completed writes. Both process tests start the built Master JAR in isolated temporary directories and read back identical chunk, level, and player data after starting a second process. Abrupt termination while a write is still in flight remains untested. The paperclip artifact built from source commit `23425b8` is 66,657,344 bytes with SHA-256 `BEB1DC64831CBE1E33F39209E172A816F4BC9AEF57954B9A1CDB4D4030FD72A4`. The full server suite ran after the entity changes; the post-commit build reused those successful test results and regenerated the paperclip. The Master now closes its cached region files after async work and networking have drained during graceful shutdown.

The live smoke root was `C:\Users\elibo\AppData\Local\Temp\MultiPaper-26.3-smoke-0c73a38-20261016-063616`. Master ran on `35353` with proxy `25565`; `smoke-server-1` and `smoke-server-2` ran on `25566` and `25567`. Both reached `Done` and connected to the Master. Status requests to all three ports returned MultiPaper 26.3, protocol 777; the Master logged proxy connections routed to both backends. After a graceful stop and restart, both backends loaded `world_gen_settings.dat` from the Master. Master and backend copies were each 242 bytes with matching SHA-256. The final `8ba9af3` JAR was used for the last two-backend startup and proxy status check.

That initial smoke run had no Minecraft client. The user explicitly accepted the current official Minecraft EULA on 2026-10-16; disposable test server directories used `eula=true`.

### Later gameplay probe — cross-backend checks still required

The initial forced-unload restriction still allowed Moonrise holders with active ticket levels to be removed. A fresh ordered startup then crashed with `Missing chunkholder when required` during player chunk loading. The guard now requires Moonrise's `isSafeToUnload()` checks to pass, in addition to stable inaccessible full status and no pending unload state. A fresh two-backend run on 2026-10-16 started Server 1 before Server 2, then logged raw Minecraft 26.3 clients into Server 2 and Server 1 in that order. Both reached player placement and remained connected for 60 seconds; neither backend reported the missing-holder exception. This verifies the specific startup/login crash fix on one PC. The initial cross-backend block check was inconclusive; the later bidirectional block checks below passed. Proxy client login/authentication, two physical PCs, and sustained gameplay have not been verified. Keep both servers closed to players until those checks pass.

### Latest replication probe — 2026-10-16

The isolated servers in `C:\Users\elibo\AppData\Local\Temp\MultiPaper-26.3-blocksync-trace2-20261016` were restarted with the latest Paperclip build and both connected to the Master on `35713`. Server 1 and Server 2 listened on `26006` and `26007`, with RCON on `26010` and `26011`. Two offline-mode test clients, `ProbeAlpha` on Server 1 and `ProbeBeta` on Server 2, were online simultaneously. Both backends listed both players. A `minecraft:gold_ingot` stack of 7 given to `ProbeAlpha` on Server 1 appeared in the same inventory slot when read from Server 2.

Block changes were verified in both directions. Server 1 set an emerald block and Server 2 read it; Server 2 set a diamond block and Server 1 read it. After `save-all flush`, Server 2 was stopped cleanly and restarted while Server 1 remained online; both saved marker blocks were still readable from both servers after the restart. The reusable disposable-world check `scripts/Verify-TwoBackendReplication.ps1` repeats the block check. With `-PlayerName ProbeAlpha`, it also verifies a player inventory update on both backends. The script summons a tagged no-gravity pig on Server 1, confirms the same UUID on Server 2, teleports it, checks for an identical position, kills it, and verifies it is removed from Server 2. It passed with position `[-192.5d, 102.0d, 72.5d]`. Neither backend logged the previous `Unhandled packet ClientboundEntityPositionSyncPacket` warning during the probe.

The corresponding fixes add server-side entity subscriptions for newly loaded entity chunks and apply 26.3 position-sync packets to the mirrored entity. A second clean-world pair ran under `C:\Users\elibo\AppData\Local\Temp\MultiPaper-26.3-clean-entity-20261016` with its own Master on `35714`, backends on `26016` and `26017`, and RCON on `26020` and `26021`. Two offline clients, `CleanAlpha` and `CleanBeta`, were online simultaneously. Running `scripts/Verify-TwoBackendReplication.ps1` with `-TestX 168 -TestY 80 -TestZ 206 -PlayerName CleanAlpha` passed bidirectional blocks, player inventory, overworld entity spawn/movement/removal, and Nether entity spawn/removal. The Nether removal check needed a 2 second wait for the external message to arrive; the earlier 500 ms assertion was too short. These checks used separate server processes on one Windows host. They do not verify independent physical PCs, world-generation ownership under load, redstone, pistons, containers, player transfers, crash recovery during active edits, or performance under load.

An initial container interaction probe against that pair did not pass. The test client was revised to delay its `player_loaded` packet after a teleport confirmation, which avoided the earlier invalid movement kick, but the chest still did not open and no `open_screen` packet was observed. A later RCON check found that Server 2 saw a chest block created on Server 1 but had an empty item list after five diamonds were inserted. Inspection traced the missing data to the sync hook serializing `BlockEntity.getUpdateTag()`, whose default implementation is empty. The hook now sends `BlockEntity.saveWithFullMetadata`.

After rebuilding, both backend jars were updated and restarted against the same isolated Master and disposable shared test world. `scripts/Verify-TwoBackendReplication.ps1 -Server1RconPort 26020 -Server2RconPort 26021 -TestX 168 -TestY 80 -TestZ 206 -TimeoutSeconds 20` passed bidirectional block changes and confirmed that Server 2 read the five diamonds inserted into Server 1's chest. This verifies command-driven chest block entity contents. Player-driven chest opening and inventory changes remain unverified. The Server 2 log also recorded duplicate entity UUID and unloaded-chunk entity warnings during the repeatedly reused test world; their cause remains unresolved.

The next clean test pair used Master port `35715` and backend/RCON ports `26022`/`26024` and `26023`/`26025`. The rebuilt artifact fixed a separate redstone regression: the 26.3 port override had called `super.updateNeighborsAt`, but `Level.updateNeighborsAt` is empty. The override now calls `neighborUpdater.updateNeighborsAtExceptFromFacing` directly and retains Paper's block-state capture guard. With online test clients present in the test chunk, a redstone block powered a piston on Server 1; Server 2 observed the extended piston and pushed stone. Removing power retracted the piston and Server 2 observed the retracted state and stone at its destination. The automated script was corrected to assert this vanilla piston behavior and handle false `execute if` results returned as empty RCON strings. The command registration patch also restores `/mpdebug`, `/mpmap`, and `/servers` to the Craft command map; `/mpdebug` showed the assigned backend to connected players.

The latest active regression pair used the existing Master on `35715`, backends on `26032` and `26036`, and RCON ports `26033` and `26037`, with a distinct disposable world. The final Paperclip build embeds `55c83f9` and both backends were restarted from it. Chunk `60,60` was owned by Server 1 and external on Server 2. An offline protocol client connected to Server 2, opened a chest at `970,100,970` owned by Server 1, and the `CrossInteractProbe` plugin recorded the right-click on Server 1. Mirrored `ExternalPlayer` connections began with a 60 tick client-load timeout, while `ExternalPlayer.tick()` skips normal ticking so the timeout never expired. `ExternalPlayer` now marks its connection client-loaded immediately on creation. Starting from an empty player inventory and five diamonds in the chest, the client moved all five from chest slot 0 to inventory slot 9; RCON showed an empty chest on Server 1 and exactly five diamonds in the player's inventory on both backends. After `save-all flush` and a graceful Server 2 restart, the player rejoined with all five diamonds in slot 9. The reusable script was run at `-TestX 1400 -TestY 200 -TestZ 1400 -PlayerName ActivePlayer1`, with clients on both backends; all block, chest, piston extension/retraction, player inventory, overworld entity, and Nether entity checks passed. The script now uses `TestY` for its block checks and clears the piston line before the test, avoiding terrain collisions seen at y=101 in the reused world. These checks validate player-driven container opening, item transfer, cross-backend inventory state, and backend restart persistence on one host; they do not validate physical PC separation or crash recovery during an active transfer. The reused world continued to produce duplicate entity UUID and stale entity-request warnings, so their cause remains open.

A separate isolated recovery pair used a disposable Master on `35716`, with backends and RCON on `26042`/`26043` and `26046`/`26047`. After both distinct backend names connected, an emerald set on Server 1 replicated to Server 2. The Master process was terminated unexpectedly and restarted from the same disposable directory; both backends reconnected automatically. The pre-disconnect emerald remained readable, a diamond set on Server 2 then replicated to Server 1, and after `save-all flush` plus graceful restarts of both backends, both blocks remained visible on both servers. This verifies Master disconnect/reconnect and saved block recovery on one host. It does not cover a Master crash during an in-flight write or recovery from damaged Master storage.

## Layout and network

Reserve a fixed LAN address for PC1 (shown below as `192.168.1.10`) and record PC2's address (shown as `192.168.1.11`). Replace both examples with the addresses on your LAN. Use the same PC1 address in both servers' `multipaper.yml` files.

| PC | Process | Bind/port | Access |
| --- | --- | --- | --- |
| PC1 | MultiPaper Master | TCP `35353` | Server 1 and PC2 only |
| PC1 | Master's built-in player proxy | TCP `25565` | Players on the LAN |
| PC1 | MultiPaper Server 1 | TCP `25566` | Keep private; proxy is local |
| PC2 | MultiPaper Server 2 | TCP `25567` | PC1 only |
| PC1, PC2 | Crafty panel | TCP `8443` | Administrator devices only |

Do not forward the backend ports to the public internet. Allow TCP `35353` inbound on PC1 from PC1 and PC2. Allow `25567` inbound on PC2 from PC1. Allow `25565` inbound on PC1 from the players' LAN. Keep `25566` restricted to PC1. Outbound TCP from both PCs to PC1 port `35353` must be allowed. Use Windows Defender Firewall rules scoped to these LAN addresses; no router port forwarding is needed for LAN-only play.

## Install Crafty and Java

Install Crafty's native Windows portable package on both PCs, or use its Windows Service package if you need Crafty to start before a user logs in. Crafty documents both Windows options. Do not run Crafty in Docker Desktop/WSL for this world: Crafty's documentation warns that Windows Docker volume handling can corrupt Minecraft chunks. See [Crafty's Windows installation guide](https://docs.craftycontrol.com/pages/getting-started/installation/windows/) and [Docker warning](https://docs.craftycontrol.com/pages/getting-started/installation/docker/).

Install a 64-bit Java 25 runtime on both PCs and verify the `java.exe` path Crafty will use. This fork compiles with Java 25. In the Crafty server configuration, set **Server Executable** to that `java.exe` and **Server Execution Command** to the arguments below. Crafty documents these fields and its server import workflow in [server configuration](https://docs.craftycontrol.com/pages/user-guide/server-config/minecraft/) and [server creation/import](https://docs.craftycontrol.com/pages/user-guide/server-creation/minecraft/).

Keep each Crafty server's working directory separate. On PC1, create entries for Server 1 and, if your Crafty build can manage a non-Minecraft Java process, the Master. On PC2, create Server 2. Import a ZIP containing the appropriate JAR into each Crafty entry, or create the entry first and copy the JAR into its working directory. Do not put the Master JAR or its files in either Minecraft server directory.

## Copy the JARs

Copy these built artifacts from the fork to the matching Crafty working directories:

- PC1 Master: `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar`
- PC1 Server 1 and PC2 Server 2: `multipaper-server/build/libs/multipaper-paperclip-26.3-R0.1-SNAPSHOT.jar`

Use the same Paperclip build on both server PCs. The current Paperclip artifact was built from commit `23425b8` and includes the hidden external entity synchronization fix. The build version is `26.3-DEV-feature/port-26.3@23425b8`.

| Artifact path | Use | Size | SHA-256 |
| --- | --- | ---: | --- |
| `multipaper-server/build/libs/multipaper-paperclip-26.3-R0.1-SNAPSHOT.jar` | PC1 Server 1 and PC2 Server 2 | 66,657,662 bytes | `263674BA91ABAE959DF21EEC78D3B7889D23E667533F15E8D2E38ADC61985F13` |
| `multipaper-server/build/libs/multipaper-bundler-26.3-R0.1-SNAPSHOT.jar` | Bundled server distribution | 110,086,478 bytes | `1A4C82FFD2D3FFA2A87292C78E9490FBA232D7D508CE35B90E45664ED7E6533C` |
| `multipaper-server/build/libs/multipaper-server-26.3-R0.1-SNAPSHOT.jar` | Extracted server runtime | 31,687,179 bytes | `088AC8778ED37669C7139AB07C0EC758A7ADD86764C46199F913A20D10AA0B20` |
| `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar` | PC1 Master | 19,152,695 bytes | `A245AB56764EC5791FBFF07AFF362719F5E39F45C7A1A7994C1EA05B9AD6DDCA` |

Keep a separate backup copy of the original world before migrating it.

### Local performance diagnostic — 2026-10-17

Paper 26.3 build 200 and the MultiPaper `23425b8` pair were sampled on a Ryzen 9 7900X host (12 cores/24 threads, 32 GiB RAM, Java 25.0.3). Each run lasted about 90 seconds with nine 10-second samples, two offline protocol-777 test clients, and 40 persistent, invulnerable, AI-enabled zombies. The raw samples and method are recorded in [`benchmark-results/2026-10-17`](benchmark-results/2026-10-17/README.md).

| System / layout | TPS | MSPT Server 1 | MSPT Server 2 | Combined CPU | Combined RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Paper, players and mobs together | 20 | 0.69 ms | — | 12.38% | 2.23 GiB |
| Paper, players and mobs spread | 20 | 1.00 ms | — | 13.77% | 2.41 GiB |
| MultiPaper, players and mobs together on Server 1 | 20 / 20 | 3.03 ms | 0.71 ms | 40.78% | 4.60 GiB |
| MultiPaper, 20 mobs and one player per backend | 20 / 20 | 1.21 ms | 1.13 ms | 37.19% | 4.73 GiB |

These are light local diagnostics with static test bots; all runs stayed at 20 TPS, far from saturation. They do not predict performance on the target i5-6700 PCs. A separate JFR socket capture measured about 405,295 bytes of MultiPaper internal loopback application payload over 90 seconds (roughly 4.5 KiB/s), excluding TCP/IP headers and physical LAN traffic. Repeat on the target hardware and network with representative gameplay before drawing performance conclusions.

## Configure the Master

Run the Master from its own working directory on PC1. For example, in PowerShell:

```powershell
$JavaExe = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
Set-Location 'C:\Crafty\servers\MultiPaper-Master'
& $JavaExe -Xms512M -Xmx1G -jar .\multipaper-master-3.0.0.jar 35353 25565
```

The second port argument enables the built-in proxy. If Crafty manages this process, set its stop command to `exit` and its working directory to the Master directory. Otherwise keep the PowerShell window open; type `exit` there to stop only the Master after both backends have stopped. Do not use a forced process kill for normal shutdown.

## Configure both backend servers

On each backend, start the Paperclip JAR once only after the EULA requirement has been handled. Stop it and edit the generated `multipaper.yml` and `spigot.yml` before the shared-world run.

Set `multipaper.yml` on PC1:

```yaml
master-connection:
  my-name: pc1-survival
  master-address: 192.168.1.10:35353
  advertise-to-built-in-proxy: true
```

On PC2 use the same `master-address` and `advertise-to-built-in-proxy`, with a unique name:

```yaml
master-connection:
  my-name: pc2-survival
  master-address: 192.168.1.10:35353
  advertise-to-built-in-proxy: true
```

Use a stable `my-name`; the default is randomized on first initialization. MultiPaper requires every backend to use the same literal Master address, not different aliases for the same PC. The configuration keys and defaults are documented in [`MULTIPAPER_YAML.md`](MULTIPAPER_YAML.md).

For the built-in proxy, set `settings.bungeecord: true` in each server's `spigot.yml`. The tracked 26.3 handshake patch accepts the Master proxy's address-only forwarding form while leaving authentication to the backend. Backend startup now sends its listening port to the Master; the Master uses it to select proxy destinations. A live status probe through `25565` reached both backends. In a disposable two-backend run on 2026-10-17, two simultaneous offline protocol-777 test clients logged in through the Master proxy; Master and backend logs show one routed to each backend. This verifies proxy login and routing with `online-mode=false`. Production online-mode authentication and physical LAN proxy traffic remain unverified. Keep `online-mode=true` in each production `server.properties` so each backend authenticates clients.

Use the same `level-name=world` and matching world-generation settings on both servers. Set `server-port=25566` on PC1 and `server-port=25567` on PC2. Leave `server-ip` blank unless you have a specific bind-address requirement; the Master learns the backend's reachable address from its Master connection. Do not run two unrelated first-start world generations and then point both at the Master.

In Crafty, set the Paperclip server execution command to:

```text
-Xms2G -Xmx8G -jar multipaper-paperclip-26.3-R0.1-SNAPSHOT.jar --nogui
```

Set the Crafty stop command to `stop`, the per-server Crafty port to its backend port, and the shutdown timeout to at least 120 seconds. The suggested 8 GB maximum leaves room for Windows and Crafty on a 16 GB PC; monitor memory pressure and lower it if the OS pages or Java is killed. Do not set `Xms` equal to the 8 GB maximum on these machines.

## World migration and first startup

The Master working directory is the shared world-data store. Stop the source server cleanly, make a full offline backup, then copy the world folders (for example `world`, `world_nether`, and `world_the_end`) into the Master working directory. Preserve the server's `level-name` and dimension folder names. Do not copy a live region directory while Minecraft is writing it.

Start in this order:

1. Start the Master on PC1 and confirm it listens on `35353`; start its proxy on `25565`.
2. Start Server 1 and wait for its log to report a successful Master connection and completed world load.
3. Start Server 2 and verify it connects under its distinct name and loads the same world from the Master.
4. Check that both servers advertise to the built-in proxy, then connect a client to `192.168.1.10:25565`.
5. Keep a second client available for simultaneous-player and cross-server checks. Do not open the setup to regular players until chunk ownership, interactions, saving, and restart recovery have passed.

The user explicitly accepted the current [official Minecraft EULA](https://www.minecraft.net/en-us/eula) on 2026-10-16. Set `eula=true` in each production backend's `eula.txt` to record that acceptance. Do not treat the Master process as a Minecraft server or create an EULA file for it.

## Shutdown, backup, and recovery

For a planned stop, stop Server 1 and Server 2 with their `stop` command and wait for their final save/stop messages. Then stop the Master with `exit` (or `shutdown` if you deliberately want it to notify remaining server connections). Back up the Master directory and both Crafty server directories together while all three processes are stopped. Crafty's per-server backups do not include the separate Master data directory, so back that directory up independently.

For recovery after a backend crash, check both server logs and the Master's connection list/log first. Restart the crashed backend with the same `my-name` and Master address; do not restore an old local world over the Master's shared data. If Master data is damaged, stop both backends and restore the complete offline Master backup before starting either backend. Never start one backend against a partially restored Master directory.

## Verification still required

The local tests proved startup, distinct Master connections, proxy status routing, world-generation settings retrieval from Master storage, clean backend shutdown, concurrent basic direct login to both backends, command-driven block and container replication, entity synchronization, redstone and piston updates, player-driven cross-backend chest opening and item transfer, player inventory persistence through a backend restart, and disposable Master crash/reconnect with saved block recovery. They did not establish that two physical PCs can safely operate one world. Before deployment, run Server 1 on PC1 and Server 2 on PC2, then test real logins through `25565` with two clients. Verify players on separate backends, chunk ownership and ticking, world generation, block and entity synchronization, inventories, mobs, dimensions, transfers, saves, and persistence. Exercise planned shutdowns, backend disconnect, crash during active edits, and recovery from damaged Master storage. Compare against standard Paper under repeatable loads, recording TPS/MSPT, CPU, RAM, and network traffic with players together and spread apart. Do not claim readiness until those checks pass.

The duplicate-UUID investigation found that Moonrise's normal `EntityLookup.get(UUID)` hides entities below `FULL` chunk visibility. External synchronization handlers treated those hidden mirrored entities as absent and could attempt to insert a second entity with the same UUID. Commit `23425b8` adds a raw UUID lookup for external synchronization and updates the inbound entity update, removal, effect, persistent-data, raid, and request handlers. The helper and the earlier external-player loaded-state method are tracked as Minecraft source patches, so clean source regeneration retains both changes. The full build passed with these changes.

The regression pair ran the `23425b8` Paperclip on a disposable world with Master `35715`, Server 1 `26032`, and Server 2 `26036`. Two offline protocol-777 clients stayed connected on separate backends. `scripts/Verify-TwoBackendReplication.ps1` passed block changes in both directions, chest contents, piston extension and retraction, player inventory replication, overworld entity spawn/movement/removal, and Nether entity spawn/removal. An optional `-Server1LogPath`/`-Server2LogPath` assertion was added to the script; it passed and found no new `Entity uuid already exists` warnings on either backend. The earlier pre-fix Server 2 log contains 50,438 matching warning lines. The servers then flushed saves; Server 2 was gracefully restarted and reconnected. After explicitly forceloading chunk `87,87` on both backends, both read the saved emerald and diamond markers, and a fresh block update from Server 1 replicated to Server 2. This is one-host integration evidence, not proof under long-running survival load. The same test logs recorded 17 warnings when remote entity NBT arrived before its target chunk was loaded on Server 1, plus 12 missing-entity requests on Server 1 and 2 on Server 2 during client startup/movement; investigate these if they persist in ordinary gameplay.

### Cross-chunk verifier follow-up — 2026-10-17

Commit `ff78e01` updates `scripts/Verify-TwoBackendReplication.ps1` so omitting `-PlayerName` skips only inventory checks; entity and dimension synchronization still run. Entity teleport now crosses chunk boundaries and waits for the synchronized position. Entity removal polls for up to `TimeoutSeconds` rather than assuming replication must finish within 500 ms. Optional `-SkipContainerCheck` and `-SkipRedstoneAndPistonCheck` switches support isolated probes where those checks require player-loaded chunks; both checks remain enabled by default.

On the disposable pair under `C:\Users\elibo\AppData\Local\Temp\MultiPaper-26.3-fresh-crosschunk-b8a2e7687ce847dca9f85b37a1d9856c`, Master ran on `35827`, backends on `26100`/`26102`, and RCON on `26101`/`26103`. The run at `TestX=1900`, `TestY=100`, `TestZ=2000` passed bidirectional block synchronization, entity spawn with matching UUID, cross-chunk teleport with identical positions, entity removal, Nether entity creation/removal, and the no-new-duplicate-UUID-warning assertion. It explicitly skipped containers and redstone/pistons and had no online players, so those behaviors are covered only by the earlier player-connected probes documented above. One initial run exposed the verifier's overly short fixed removal delay; a follow-up run passed after the wait was changed to poll up to the configured timeout. Both backends were stopped after the probe; this remains one-host disposable evidence and does not verify the two physical PCs.

### Master persistence rerun — 2026-10-17

Ran `.\gradlew.bat :multipaper-master:test --rerun-tasks --no-daemon` with Microsoft Java 25.0.3. All 19 tests executed successfully: 16 Master protocol/storage integration tests, 2 separate-process restart/recovery tests (graceful restart and unexpected termination after acknowledged writes), and 1 region file cache close/reopen test; 0 failures, 0 errors, 0 skipped. The Master JAR SHA-256 remained `A245AB56764EC5791FBFF07AFF362719F5E39F45C7A1A7994C1EA05B9AD6DDCA`. This does not cover process termination while a write is still in flight or corruption of the Master data directory.

### Server test rerun — 2026-10-17

Ran `.\gradlew.bat :multipaper-server:test --rerun --no-daemon --console=plain` with Microsoft Java 25.0.3. The first invocation was stopped while Gradle was rebuilding `processResources`. Isolating that task showed it copying and hashing 9,932 resource files; it completed successfully in 9m23s. With that output snapshot warm, the server test task completed successfully in 2m33s. Current test XML reports 10,103 tests, 0 failures, 0 errors, and 86 skipped. The pre-existing asynchronous inventory warning from `PlayerInventoryTest.testCanHold` appeared again. The Paperclip SHA-256 remains `BEB1DC64831CBE1E33F39209E172A816F4BC9AEF57954B9A1CDB4D4030FD72A4`. This is the same suite scope as the previous full pass; it does not replace the need for two-physical-PC verification.

### Inventory dirty-tracking regression — 2026-10-17

Commit `ac2ab2a` changes `MultiPaperInventoryHandler.markDirty` to ignore inventory lists not owned by a `ServerPlayer`. The warning in `PlayerInventoryTest.testCanHold` came from constructing a standalone `Inventory` with a null player; those lists cannot be broadcast by MultiPaper and were being added to the synchronization queue anyway. Added `MultiPaperInventoryHandlerTest` to assert these standalone list changes remain unmarked. The focused test passed, then the full server suite passed with 10,104 tests, 0 failures, 0 errors, and 86 skipped. The suite output and XML no longer contain the asynchronous inventory modification warning. `:multipaper-server:createPaperclipJar` and `:multipaper-server:scanJarForBadCalls` both passed.

The rebuilt Paperclip was started with a disposable Master and two independent backends. The updated artifact passed bidirectional block sync, cross-chunk entity spawn/teleport/removal, Nether entity creation/removal, and no-new-duplicate-UUID-warning checks. The player-free verifier run skipped inventory, container, and redstone/piston checks; earlier online-player probes remain the evidence for those gameplay features. Both backends were stopped through RCON after the probe.

### Player transfer verifier — 2026-10-17

Extended the opt-in `-PlayerName` path in `scripts/Verify-TwoBackendReplication.ps1` to teleport a connected player across chunk boundaries, verify matching positions on both backends, transfer the player into the Nether, compare mirrored Nether positions, return the player to the Overworld, and verify both backends agree on the final position. On the fresh disposable pair and rebuilt Paperclip, the run used `TestX=2500`, `TestY=100`, `TestZ=2600`, `TransferProbe`, and `TimeoutSeconds=20`; it passed the player inventory assertion, cross-chunk position replication at `[2532.5d, 100.0d, 2601.5d]`, Nether transfer at `[0.5d, 80.0d, 0.5d]`, return to the Overworld at `[2532.5d, 101.0d, 2601.5d]`, and no-new-duplicate-UUID warnings. Bidirectional blocks and the existing entity spawn/move/removal and Nether entity checks also passed. This isolated run explicitly skipped container and redstone/piston checks; they remain supported by the earlier probes documented above.

The test used one offline 26.3 protocol client connected to Server 1 and RCON-driven teleport commands. Because that disposable client does not simulate ordinary player movement while idle, both test backends used `allow-flight=true`; this is a harness-specific setting and must not be copied into deployment configs. The player transfer evidence is still one-host integration evidence; it does not verify two physical PCs, ordinary player-driven dimension travel, or crash recovery during active gameplay.

A final full verifier run on a fresh coordinate (`TestX=3300`, `TestY=100`, `TestZ=3400`) kept the container and redstone/piston checks enabled and included the connected `TransferProbe`. It passed bidirectional blocks, chest contents, piston extension/retraction and moved-block replication, player inventory, cross-chunk player position, entity spawn/teleport/removal, Nether entity synchronization, player Nether transfer and return, and duplicate-UUID checks. A preceding run on a previously used coordinate failed the chest check because that reused location had no mirrored block entity; rerunning the same chest probe in a fresh forced-loaded chunk and then in a fresh full-verifier coordinate passed. The stale-coordinate failure appears fixture-specific, but the cause is not established.
