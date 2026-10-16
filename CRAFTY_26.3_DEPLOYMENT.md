# MultiPaper 26.3 on two Windows PCs with Crafty

This guide is a deployment plan for the current fork. The two-server shared-world setup has **not** yet been verified end to end; follow the staged startup and recovery checks below before exposing it to players.

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

Use the same Paperclip build on both server PCs. The preserved build artifacts currently on disk have these SHA-256 hashes (the hashes identify the preserved artifacts; regenerate the Paperclip JAR from the now-repaired tracked patch before release):

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `multipaper-paperclip-26.3-R0.1-SNAPSHOT.jar` | 66,656,002 bytes | `D4209836902E507A6DC73C6687FA1E543D56D914A29AC9ABB96BF15845CBE668` |
| `multipaper-master-3.0.0.jar` | 19,151,848 bytes | `39B2098A13D000C3623D7C78A70CF4C02EBAEFDA77CFAD479DA861FED78F3A0C` |

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

For the built-in proxy, set `settings.bungeecord: true` in each server's `spigot.yml`. The Master sends an address-only client-IP forwarding form. The required backend handshake change is not currently integrated into the tracked source patch, so do not enable the built-in proxy for player traffic until that fix is restored, rebuilt, and exercised with a real client login. Keep `online-mode=true` in each `server.properties` so each backend authenticates clients.

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

Crafty asks for EULA confirmation when a Minecraft server first starts. Review the [official Minecraft EULA](https://www.minecraft.net/en-us/eula) and accept it yourself in Crafty or by setting `eula=true` only after you have decided to accept. Do not treat the Master process as a Minecraft server or create an EULA file for it.

## Shutdown, backup, and recovery

For a planned stop, stop Server 1 and Server 2 with their `stop` command and wait for their final save/stop messages. Then stop the Master with `exit` (or `shutdown` if you deliberately want it to notify remaining server connections). Back up the Master directory and both Crafty server directories together while all three processes are stopped. Crafty's per-server backups do not include the separate Master data directory, so back that directory up independently.

For recovery after a backend crash, check both server logs and the Master's connection list/log first. Restart the crashed backend with the same `my-name` and Master address; do not restore an old local world over the Master's shared data. If Master data is damaged, stop both backends and restore the complete offline Master backup before starting either backend. Never start one backend against a partially restored Master directory.

## Verification still required

The current automated tests cover protocol handshakes and Master-level ownership/storage/recovery logic, and the Master has been smoke-started locally. They do not establish that two real 26.3 servers can safely share a world. Before deployment, test real logins through `25565`, one player on each backend, chunk handoff/ticking, block/entity/inventory/container/redstone updates, dimensions, saves, clean stops, a backend crash/reconnect, and full restart from backup. Record TPS/MSPT, CPU, RAM, and network use before comparing against a standard Paper server.
