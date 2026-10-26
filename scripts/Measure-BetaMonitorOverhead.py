#!/usr/bin/env python3
"""Reproducible synthetic CPU/RSS overhead check; no game server or RCON required."""
import argparse
import importlib.util
import json
import os
import tempfile
import time
from pathlib import Path

source = Path(__file__).with_name("multipaper-beta-monitor.py")
spec = importlib.util.spec_from_file_location("beta_monitor", source)
monitor_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor_module)

ap = argparse.ArgumentParser()
ap.add_argument("--iterations", type=int, default=100)
ap.add_argument("--events-per-interval", type=int, default=10)
ap.add_argument("--poll-seconds", type=float, default=10)
args = ap.parse_args()
if args.iterations < 1 or args.events_per_interval < 0 or args.poll_seconds <= 0:
    raise SystemExit("iterations and poll interval must be positive; events cannot be negative")

with tempfile.TemporaryDirectory(prefix="multipaper-monitor-overhead-") as td:
    root = Path(td)
    log = root / "latest.log"
    log.touch()
    config = root / "monitor.json"
    config.write_text(json.dumps({"output_dir": str(root / "out"), "poll_seconds": args.poll_seconds,
                                  "servers": [{"id": "synthetic-backend", "role": "backend", "logs": [str(log)], "data_paths": [str(root)]}]}))
    m = monitor_module.Monitor(config)
    cpu_start = time.process_time()
    wall_start = time.perf_counter()
    for i in range(args.iterations):
        with log.open("a", encoding="utf-8") as f:
            for n in range(args.events_per_interval):
                f.write(f"[12:00:00 INFO]: Probe{(i+n)%1000} joined the game\n")
        m.run_once()
    cpu = time.process_time() - cpu_start
    wall = time.perf_counter() - wall_start
    try:
        import psutil
        rss = psutil.Process(os.getpid()).memory_info().rss
    except ImportError:
        rss = None
    result = {"iterations": args.iterations, "events_per_interval": args.events_per_interval,
              "assumed_poll_seconds": args.poll_seconds, "cpu_seconds": round(cpu, 6), "wall_seconds": round(wall, 6),
              "cpu_ms_per_poll": round(cpu * 1000 / args.iterations, 3),
              "estimated_one_core_percent_at_poll_interval": round(cpu / (args.iterations * args.poll_seconds) * 100, 4),
              "peak_rss_bytes": rss, "rcon_enabled": False, "test_data": "temporary synthetic log and metrics only"}
    print(json.dumps(result, indent=2))
