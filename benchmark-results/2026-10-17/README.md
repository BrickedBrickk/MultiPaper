# MultiPaper 26.3 local benchmark — 2026-10-17

## Environment and method

- Host: AMD Ryzen 9 7900X (12 cores / 24 logical threads), 32 GiB RAM; Java 25.0.3.
- Standard Paper: 26.3 build 200. MultiPaper: fork Paperclip built from commit `23425b8`; Master and both backends ran on the same Windows host.
- Each capture lasted about 90 seconds with nine samples spaced about 10 seconds apart. Both named offline protocol-777 test clients had to be present in every row. TPS and MSPT came from each server's RCON commands. CPU is process CPU-time delta expressed as a percentage of one logical core. RAM is process working-set size; MultiPaper sums Master and both backend processes.
- Workload: 40 persistent, invulnerable, AI-enabled zombies with natural mob spawning disabled. `together` keeps both clients at the same area. Paper `spread` places one client and 20 zombies at each of two areas about 250 blocks apart. MultiPaper `spread` places one client and 20 tagged zombies on each backend, at areas more than 1,000 blocks apart. The disposable benchmark world uses prepared, force-loaded chunks and stone pads to keep the mob sets loaded.
- These are light, short diagnostics. The clients are protocol test bots; they do not move or play survival. All sampled TPS stayed at 20, far from saturation. The measurements are specific to this 7900X host and do not predict performance on the target i5-6700 PCs.

## Results

Values are means across the nine samples. MSPT columns show the average of the servers' 10-second rolling-window averages; CPU is mean percent of one logical core.

| System / layout | TPS | MSPT, Server 1 | MSPT, Server 2 | Combined CPU | Combined RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Paper, together | 20 | 0.69 ms | — | 12.38% | 2.23 GiB |
| Paper, spread | 20 | 1.00 ms | — | 13.77% | 2.41 GiB |
| MultiPaper, together | 20 / 20 | 3.03 ms | 0.71 ms | 40.78% | 4.60 GiB |
| MultiPaper, spread | 20 / 20 | 1.21 ms | 1.13 ms | 37.19% | 4.73 GiB |

RCON also reports min/max tick times. Individual samples, including TPS/MSPT ranges, CPU, working set, player lists, and mob counts, are in the four CSVs in this directory.

A separate 90-second JFR capture correlated MultiPaper's internal backend-to-backend and backend-to-Master loopback sockets to about 405,295 application-payload bytes total (about 4.5 KiB/s). It excludes TCP/IP headers and physical LAN traffic and included a client reconnect during capture, so it is not a clean two-PC network benchmark.
