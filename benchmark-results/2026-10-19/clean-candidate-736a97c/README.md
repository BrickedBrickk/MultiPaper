# Clean target-PC load run

Artifact: Paperclip `26.3-DEV-73c5983`, SHA-256 `736A97C92CC690DE7F7E067C3D3856BD2CDF4CF1F27A98722FAB297C530F45D0`.

The run used a newly created disposable world on the physical two-PC setup (Master and Backend 1 on PC1; Backend 2 on PC2). The collector recorded 61 samples over 772.2 seconds. Two tagged groups remained visible with identical counts on both backend views for every sample: 14 `bench_a` zombies and 6 `bench_b` zombies. No players were connected, so these measurements do not cover player traffic or gameplay.

| Metric | Backend 1 | Backend 2 |
| --- | ---: | ---: |
| TPS, all samples | 20 | 20 |
| 1m average MSPT, mean | 0.93 ms | 0.80 ms |
| 1m maximum MSPT, highest sampled | 23.8 ms | 21.9 ms |
| Process CPU, mean (one-core percentage) | 7.63% | 7.40% |
| Process RSS, mean / maximum | 1.33 / 1.33 GiB | 1.34 / 1.37 GiB |

Master averaged 0.64% one-core CPU and 0.22 GiB RSS. The 238-sample host telemetry tails showed mean CPU idle of 92.2% on PC1 and 95.5% on PC2, mean I/O wait of 0.20% and 0.26%, and no swap in/out samples. Cross-PC ping during the same tail was 237 replies, 1.32 ms mean and 1.66 ms maximum. These are host and ICMP observations, not a measurement of block replication latency.

The test pair was saved with `save-all flush` and stopped via RCON. Both backend logs report region I/O completion. The disposable test ports were then closed; production listeners remained available. CSV and raw telemetry files are retained alongside this note. Java GC telemetry was attempted but the `jstat` executable was absent from the JRE paths, so no heap/GC conclusions are claimed.

This is a sustained low-load correctness/performance smoke with 20 AI entities, not a capacity benchmark. It does not establish behavior under player load, long-duration production traffic, inventory transfer, or Crafty-managed lifecycle. The Crafty registration/access blocker remains open.

## Additional disposable-world measurements

Using the same build and isolated physical pair, 20 block changes per direction were observed at the other backend. End-to-end RCON visibility (write command plus polling until the peer returned the new block) had median/P95/max of 75.125/96.043/126.050 ms from Backend 1 to Backend 2 and 75.810/164.405/313.678 ms in reverse. Treat these as upper bounds that include RCON setup/round trips and poll granularity, not transport-only latency. Raw samples are in `block-sync-latency.csv`.

A fresh 4x4 area (16 chunks, at X/Z near 10,000) was force-loaded in the disposable world and confirmed loaded on both backends in 3.919 seconds, or 4.082 chunks/second end-to-end. This is one small sample using the configured RCON polling path, not a sustained world-generation benchmark. Raw result is in `chunk-generation.csv`.

Host-wide 75-second monitoring around the chunk-generation probe averaged 91.0%/94.5% CPU idle and 0.17%/0.23% I/O wait (PC1/PC2). `/proc/diskstats` recorded about 0.207/0.199 MiB read and 9.023/3.551 MiB written over those windows, with 676/680 ms of disk busy time. Those disk counters include unrelated host activity and concurrent production service I/O, so they cannot be attributed solely to this chunk test. The quick pre-to-immediate-post samples around the test recorded approximately 0.074/0 MiB read and 1.707/1.352 MiB write. Raw vmstat and diskstats captures are retained here.

The run again logged unloaded-chunk entity unsubscribe/remove notifications on Backend 1 and tick-list notifications on Backend 2 while the test force-load tickets were being removed. It logged no `RequestEntityPacket` timeout or duplicate-UUID warning. A single `recvAddress` connection-reset warning on Backend 1 coincided with expected test-backend shutdown. The test world had been force-loading and unloading the same chunks on both backends; this is teardown evidence from the disposable test, not a reproduced player-world loss. Keep the earlier documented startup lifecycle warning as an open item; do not infer player-case correctness from this probe. Both backend logs confirm RegionFile I/O completion after `save-all flush` and graceful stop, and the separate test Master exited safely.
