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
