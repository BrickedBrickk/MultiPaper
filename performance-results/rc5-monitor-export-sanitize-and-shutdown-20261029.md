# RC5 beta monitor export privacy and shutdown follow-up — 2026-10-29

## Changes

- `--merge` now accepts only recognized event/metric fields, validates timestamps and scalar values, uses the server ID from the archive path, and records a fixed `peer-export` provenance marker instead of copying an untrusted archive filename or JSON fields.
- `--export` applies the same telemetry allowlist to JSONL/timeline records, validates and reconstructs daily summaries, and regenerates text summaries from the sanitized JSON. Arbitrary extra fields in existing monitor files are omitted.
- Ctrl+C performs a final bounded log-tail read for each configured server and writes a final daily summary before exiting. It does not issue Minecraft/RCON commands during shutdown.
- `monitoring/README.md` now states these privacy guarantees and the actual measurement boundaries: Java process metrics require `psutil` and an exact process working-directory match, disk metrics describe the first accessible filesystem path, network counters are host-wide, and Master TPS/MSPT/queue internals are unavailable unless emitted externally.

## Verification

- Python 3.11.9 focused suite: **16/16 passed**, including malformed/untrusted peer bundles, tampered local JSONL/summary fields, Ctrl+C final flush, expired-summary retention, rotation, copy-truncate, timestamps, privacy, and truthful null RCON/TPS metrics.
- `py_compile` passed for monitor and tests; focused `git diff --check` passed.
- Disposable CLI smoke ran one monitor process against a synthetic log with RCON disabled, appended synthetic persistence-warning and private-chat lines, then stopped the monitor with Ctrl+C. It recorded `chunk_write_rejected` but omitted synthetic coordinates, player name and chat; produced UTC-timestamped JSON/text summaries; exported the summaries/metrics/events/timeline and export note, with no config or raw source log. Export: `C:\Users\elibo\AppData\Local\Temp\rc5-monitor-cli-smoke-20261029-36613603c95f452c80545ecb53fa1821\support-export.zip`, 2,319 bytes, SHA-256 `72013D7FF208B349A3F0777AF0E64675CFA0C7E8ECAAC9970CCF1A7CBEE6E502`.
- Refreshed synthetic overhead (200 intervals, 20 events/interval, 10-second assumed cadence, no RCON): 4.046875 CPU seconds / 6.037766 wall seconds, 20.234 CPU ms per poll, estimated 0.2023% of one core at that cadence, peak RSS 23,961,600 bytes. This is a Windows development-host synthetic estimate only.
- Current source hashes: monitor `C7381F3F507BA2D13B25B32CF6C5157D39814AAE0F639D8DB849C55DA98B9C43`; tests `D839A2E25E816BBB270453AAC989B21EFC223947B8042D67613D34A918ADD51F`.

## Limits

The CLI smoke ran on the development Windows host with synthetic logs and no RCON. Its host CPU/RAM/disk/network values are not target-PC or Minecraft-process measurements; no Crafty PC was contacted, no real player data was used, and target-host overhead remains a manual beta gate. Ownership transfer, scheduled ticks, entity replication, dimension travel, inventory warnings, GrimAC and plugin exceptions are categorized only if a component emits a matching normal-level log line; missing events are not evidence that the event did not happen. RC5 remains blocked from deployment/publication by the P0 chest and InvSee++ gates.
