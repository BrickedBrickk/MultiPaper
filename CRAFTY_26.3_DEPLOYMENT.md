# MultiPaper 26.3 on two Windows PCs with Crafty

This guide describes deployment for the current fork. Two isolated MultiPaper 26.3 backends have been started together on one Windows host against a real Master. The checks now include two simultaneous clients, mirrored player inventory, bidirectional block changes, entity spawn subscription, and entity position updates. These checks do **not** verify two physical PCs, sustained survival gameplay, or crash recovery during active edits; keep the system closed to players until the remaining checks at the end of this guide pass.

## Build and test status

On 2026-10-16, `:multipaper-server:build` and `:multipaper-server:createPaperclipJar` completed successfully after the neighbor-update fix. The server suite passed 10,103 tests with 0 failures, 0 errors, and 86 skipped; `scanJarForBadCalls` passed. The test output included the existing asynchronous inventory warning from `PlayerInventoryTest.testCanHold`. The focused handshake-forwarding suite passed 4/4 tests, and the Master integration suite passed 16/16 tests. The artifact's embedded build version remains `26.3-DEV-feature/port-26.3@cf7b094`.

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

The next clean test pair used Master port `35715` and backend/RCON ports `26022`/`26024` and `26023`/`26025`. The rebuilt artifact fixed a separate redstone regression: the 26.3 port override had called `super.updateNeighborsAt`, but `Level.updateNeighborsAt` is empty. The override now calls `neighborUpdater.updateNeighborsAtExceptFromFacing` directly and retains Paper's block-state capture guard. With online test clients present in the test chunk, a redstone block powered a piston on Server 1; Server 2 observed the extended piston and pushed stone. Removing power retracted the piston and Server 2 observed the returned stone. A repeat of the automated script without active players in the test chunk was flaky, so this records the direct live RCON probe only. The command registration patch also restores `/mpdebug`, `/mpmap`, and `/servers` to the Craft command map; `/mpdebug` showed the assigned backend to connected players.

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

Use the same Paperclip build on both server PCs. The server artifacts below were freshly built from the current worktree based on `cf7b094`, including the block entity payload, command registration, and neighbor-update fixes. The current Paperclip build is `26.3-DEV-feature/port-26.3@cf7b094`.

| Artifact path | Use | Size | SHA-256 |
| --- | --- | ---: | --- |
| `multipaper-server/build/libs/multipaper-paperclip-26.3-R0.1-SNAPSHOT.jar` | PC1 Server 1 and PC2 Server 2 | 66,657,994 bytes | `23C18BF41AF897013EF41983A6948F6B748E2179466FFDD8265487AC07408F6F` |
| `multipaper-server/build/libs/multipaper-bundler-26.3-R0.1-SNAPSHOT.jar` | Bundled server distribution | 110,086,278 bytes | `3A9414A5329FAC7902F5C384866D8AAF5E6FF88FCD932ABA441BBD995F8ECED2` |
| `multipaper-server/build/libs/multipaper-server-26.3-R0.1-SNAPSHOT.jar` | Extracted server runtime | 31,687,030 bytes | `7E5FFA713975ACCC022B7C28A368BFC2982D403375D363CDA8E2305E122C27A7` |
| `MultiPaper-Master/build/libs/multipaper-master-3.0.0.jar` | PC1 Master | 19,152,253 bytes | `252A5E986803C1244370B6038D71826EC1CDC0F9608CF5E4A71190DF499C874C` |

Keep a separate backup copy of the original world before migrating it.

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

For the built-in proxy, set `settings.bungeecord: true` in each server's `spigot.yml`. The tracked 26.3 handshake patch accepts the Master proxy's address-only forwarding form while leaving authentication to the backend. Backend startup now sends its listening port to the Master; the Master uses it to select proxy destinations. A live status probe through `25565` successfully reached both backends. Real client login and authentication through the proxy remain unverified. Keep `online-mode=true` in each `server.properties` so each backend authenticates clients.

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

The local tests proved startup, distinct Master connections, proxy status routing, world-generation settings retrieval from Master storage, clean backend shutdown, and concurrent basic direct login to both backends without the earlier chunk cast crashes. Command-driven block and container replication, entity synchronization, and a redstone-powered piston were also probed on one Windows host. They did not establish that two physical PCs can safely operate one world. Before deployment, run Server 1 on PC1 and Server 2 on PC2, then test real logins through `25565` with two clients. Verify players on separate backends, chunk ownership and ticking, world generation, block and entity synchronization, inventories, player-driven containers, mobs, redstone, pistons, dimensions, transfers, saves, and persistence. Exercise planned shutdowns, Master restart, backend disconnect, crash during active edits, reconnect, and recovery. Compare against standard Paper under repeatable loads, recording TPS/MSPT, CPU, RAM, and network traffic with players together and spread apart. Do not claim readiness until those checks pass.
