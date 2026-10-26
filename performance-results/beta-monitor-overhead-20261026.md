# Beta monitor focused validation and synthetic overhead — 2026-10-26

## Checks

- Six Python `unittest` cases pass: Paper TPS/MSPT parsing, all requested log event categories, scheduled-tick journal state, numeric sync-latency extraction, privacy-safe event records, bounded rotation, and sanitized export/merge behavior.
- `git diff --check` passes. `scripts/multipaper-beta-monitor.py --version` reports `1.0.0`.
- Synthetic overhead command: `python scripts/Measure-BetaMonitorOverhead.py --iterations 200 --events-per-interval 20 --poll-seconds 10`.

## Measurement

```json
{
  "iterations": 200,
  "events_per_interval": 20,
  "assumed_poll_seconds": 10.0,
  "cpu_seconds": 3.15625,
  "wall_seconds": 4.973781,
  "cpu_ms_per_poll": 15.781,
  "estimated_one_core_percent_at_poll_interval": 0.1578,
  "peak_rss_bytes": 24002560,
  "rcon_enabled": false,
  "test_data": "temporary synthetic log and metrics only"
}
```

This is a local synthetic estimate on the Windows development host. It did not measure live RCON, Debian, Crafty scheduling, the target PCs, server load, or a real beta session. The beta launch guide requires a 15-minute idle and normal-load comparison on the deployed hosts; do not treat this estimate as target-hardware certification.

## Boundaries

The collector never stores raw source log lines. It records recognized event categories and a short in-memory-salted player identifier, no chat or IP. RCON commands are limited to `tps`, `mspt`, and `list`, require a protected environment variable, and reject non-loopback destinations. The export is an allowlist of bounded telemetry filenames; config and arbitrary files are excluded. Unavailable internal events remain absent rather than inferred. The Master does not provide Paper TPS/MSPT commands, and ownership/entity/tick-journal/sync-latency events are collected only if the running component emits a matching line.
