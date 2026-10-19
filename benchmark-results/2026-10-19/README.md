# Target-PC MultiPaper synthetic load — 2026-10-19

## Setup

- PC1: Intel i5-7600, 16 GiB RAM, rotational HDD; disposable Backend 1 plus the disposable Master.
- PC2: Intel i5-6600, 16 GiB RAM, rotational HDD; disposable Backend 2.
- Artifact on both disposable backends: final production Paperclip SHA-256 `948927FABA4F8AE62602465BDE4A15D1E15C1E3E16789940BE2C53C41C6DB5F5`.
- Workload: 16 persistent, AI-enabled zombies in each of two separated glass enclosures in the disposable `currentbuildworld`; natural mob spawning disabled. No players were connected.
- Nine samples covered 113 seconds. Every sample verified zero players and 16 zombies for each tag on both backends. TPS/MSPT came from RCON; process CPU time and RSS came from each PC's `/proc` over strict-host-key SSH. `vmstat 1 105` ran on both PCs across the same period.

## Results

| Process | TPS | 1-minute MSPT average | Highest sampled 1-minute max | CPU, one logical core | RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Backend 1, PC1 | 20.0 | 2.03 ms | 25.8 ms | 12.11% | 1.43 GiB |
| Backend 2, PC2 | 20.0 | 1.73 ms | 22.5 ms | 11.42% | 1.51 GiB |
| Master, PC1 | — | — | — | 0.78% | 0.13 GiB |

Host average idle was 93.71% on PC1 and 94.45% on PC2. Average I/O wait was 0.34% (maximum 5%) and 0.21% (maximum 3%), respectively. `vmstat` read/write columns averaged 2.05/74.49 blocks/s on PC1 and 2.63/36.37 blocks/s on PC2. The disks report `ROTA=1`; `iostat` is not installed, so device latency was not measured.

The raw nine-sample capture is [`target-pc-16-ai-zombies-reconnectfix.csv`](target-pc-16-ai-zombies-reconnectfix.csv). The separate eight-ping PC1-to-PC2 sample in the deployment log averaged 1.334 ms with no loss.

## Re-run

Open strict-checking SSH tunnels to the disposable RCON ports, then place each disposable RCON password in the current PowerShell process environment without writing it to a file:

```powershell
$env:MULTIPAPER_RCON_PASSWORD1 = '<Backend 1 disposable RCON password>'
$env:MULTIPAPER_RCON_PASSWORD2 = '<Backend 2 disposable RCON password>'
```

Run [`scripts/benchmark/Collect-RemoteMinecraftBenchmark.ps1`](../../scripts/benchmark/Collect-RemoteMinecraftBenchmark.ps1) with the active disposable process IDs, RCON ports, zombie tags, sample count, and output path. The script uses `StrictHostKeyChecking=yes`, asserts the expected player and mob counts for each sample, and records per-process CPU/RSS plus tick metrics. It refuses to collect if players are online by default.

This workload is a small CPU diagnostic. It has no player movement, redstone clock, chunk generation, or sustained disk writes, and is not a survival gameplay performance claim.

## Fresh 8×8 chunk generation and cross-backend load — 2026-10-19

The exact deployed backend JAR was run on both physical PCs in the disposable `currentbuild-9375421` world. Each backend was assigned a previously absent Overworld region with 64 chunks through `/forceload add`; after owner generation, the peer was told to load the same area. PC1's area starts at block `(50000,-50000)` and PC2's at `(-50000,50000)`. Each 8×8 chunk center was confirmed loaded on both backends. Owner-side 4×4 anchor grids were ready after 12.0 seconds on PC1 and 13.5 seconds on PC2; peer anchor grids were ready after 16.4 and 18.1 seconds. The full 64-chunk checks on both views completed by 41.5 seconds, including RCON query time.

At the post-generation sample, both reported 20 TPS over 5 seconds and 1 minute. One-minute MSPT averages were 2.1 ms on PC1 and 2.2 ms on PC2, with 451.2 and 458.4 ms sampled maxima during this world-generation/unload window. Host `vmstat` sampled 150 seconds beginning just before the generation commands: average idle was 87.95% / 87.07%, average I/O wait 0.33% / 0.28%, and maximum I/O wait 7% / 4% for PC1 / PC2. Diskstats deltas over the benchmark and cleanup window were about 25.8 MiB written on PC1 and 10.8 MiB on PC2. Fresh region files were persisted at 4 MiB and 3.8 MiB. See [`target-pc-fresh-chunk-generation.csv`](target-pc-fresh-chunk-generation.csv) and the [raw PC1](target-pc-chunkgen-pc1-vmstat.txt) / [raw PC2](target-pc-chunkgen-pc2-vmstat.txt) `vmstat` captures.

This is a 128-chunk, no-player synthetic generation and replication measurement, not normal exploration or a survival benchmark. During simultaneous force-load removal, logs reported late chunk/entity subscriber notifications for already-unloaded holders, plus a Master read timeout for an entity chunk that Backend 2 retried at 20-second intervals. Both servers saved their block/entity chunks and completed RegionFile I/O, but I did not verify that the timed-out read returned successfully before stopping Backend 2. These warnings remain a lifecycle issue to investigate; this was not a clean warning-free run. The benchmark removed its own 64-chunk tickets on both backends and confirmed that only nine pre-existing disposable-world tickets remained.
