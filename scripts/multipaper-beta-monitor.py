#!/usr/bin/env python3
"""Low-overhead, privacy-preserving MultiPaper beta observer (Python 3.10+, stdlib only)."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import platform
import re
import shutil
import socket
import struct
import sys
import threading
import time
import zipfile
from pathlib import Path
from typing import Any

VERSION = "1.0.0"
EVENTS = {
    "joined the game": "player_join", "left the game": "player_leave",
    "logged in with entity id": "player_login", "lost connection": "player_disconnect",
    "Changing dimension": "dimension_change", "changed dimension": "dimension_change",
    "transferring": "backend_transfer", "ownership": "ownership",
    "chunk owner": "ownership", "chunk loaded": "chunk_load",
    "chunk unload": "chunk_unload", "ownership transfer": "ownership",
    "sync latency": "sync_latency", "replication latency": "sync_latency",
    "scheduled tick journal": "scheduled_tick_journal", "tick journal": "scheduled_tick_journal",
    "scheduled tick": "scheduled_tick",
    "entity replication": "entity_replication", "entity snapshot": "entity_replication",
    "inventory inconsisten": "inventory_inconsistency", "grim violation": "grim_violation",
    "[grim]": "grim_violation", "exception": "exception", "error": "error",
    "crash": "crash", "starting minecraft server": "server_start",
    "done (": "backend_ready", "recovery": "recovery",
}
PLAYER_RE = re.compile(r"(?:system chat: )?([^ :\[\]]{1,32})(?:\[[^\]]+\])? (?:joined the game|left the game|logged in with entity id|lost connection)", re.I)
TIME_RE = re.compile(r"^\[(\d{2}:\d{2}:\d{2}) (?:INFO|WARN|ERROR|FATAL)\]")
SECRET_RE = re.compile(r"(?i)(password|passwd|secret|token|authorization|rcon(?:\.password)?)\s*[:=]\s*\S+")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")


def load_config(path: Path) -> dict[str, Any]:
    cfg = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(cfg.get("servers"), list) or not cfg["servers"]:
        raise ValueError("config needs a nonempty servers array")
    cfg.setdefault("poll_seconds", 10)
    cfg.setdefault("retention", {"max_bytes_per_file": 8_388_608, "keep_rotated": 4, "days": 14})
    cfg.setdefault("thresholds", {"tps_warn": 18, "mspt_warn": 50, "cpu_percent_warn": 90, "memory_percent_warn": 92, "disk_percent_warn": 90})
    cfg.setdefault("output_dir", "./monitor-data")
    for server in cfg["servers"]:
        if not server.get("id") or not re.fullmatch(r"[A-Za-z0-9_.-]{1,48}", server["id"]):
            raise ValueError("each server needs a safe id")
    return cfg


class BoundedJsonl:
    def __init__(self, path: Path, max_bytes: int, keep: int):
        self.path, self.max_bytes, self.keep = path, max_bytes, keep
        path.parent.mkdir(parents=True, exist_ok=True)

    def write(self, item: dict[str, Any]) -> None:
        payload = (json.dumps(item, separators=(",", ":"), ensure_ascii=True) + "\n").encode()
        if self.path.exists() and self.path.stat().st_size + len(payload) > self.max_bytes:
            oldest = self.path.with_suffix(self.path.suffix + f".{self.keep}")
            oldest.unlink(missing_ok=True)
            for i in range(self.keep - 1, 0, -1):
                src = self.path.with_suffix(self.path.suffix + f".{i}")
                if src.exists():
                    src.replace(self.path.with_suffix(self.path.suffix + f".{i+1}"))
            self.path.replace(self.path.with_suffix(self.path.suffix + ".1"))
        with self.path.open("ab") as f:
            f.write(payload)


def parse_log_line(line: str, server_id: str, source: str, run_salt: bytes) -> dict[str, Any] | None:
    lower = line.lower()
    category = next((value for key, value in EVENTS.items() if key in lower), None)
    if not category:
        return None
    player = PLAYER_RE.search(line)
    # Hash names with a fresh in-memory salt. Reports can count a session without preserving usernames.
    subject = hashlib.sha256(run_salt + player.group(1).encode("utf-8", "replace")).hexdigest()[:12] if player else None
    t = TIME_RE.search(line)
    event = {"time": utc_now(), "log_time": t.group(1) if t else None, "server": server_id,
            "source": Path(source).name, "event": category, "subject": subject,
            "severity": "error" if category in {"error", "exception", "crash", "inventory_inconsistency"} else "info"}
    if category == "sync_latency":
        latency = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*ms\b", line, re.I)
        event["latency_ms"] = float(latency.group(1)) if latency else None
    if category == "scheduled_tick_journal":
        event["journal_state"] = ("error" if any(x in lower for x in ("error", "failed", "loss")) else
                                  "healthy" if any(x in lower for x in ("healthy", "committed", "acknowledged", "replayed")) else
                                  "pending" if "pending" in lower else "unknown")
    return event


def _rcon_packet(sock: socket.socket, req_id: int, kind: int, body: str) -> None:
    payload = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\0\0"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def _rcon_read(sock: socket.socket) -> tuple[int, int, str]:
    def exact(n: int) -> bytes:
        data = bytearray()
        while len(data) < n:
            b = sock.recv(n - len(data))
            if not b:
                raise ConnectionError("RCON closed")
            data.extend(b)
        return bytes(data)
    size = struct.unpack("<i", exact(4))[0]
    if size < 10 or size > 1_048_576:
        raise ValueError("invalid RCON packet size")
    data = exact(size)
    return struct.unpack("<ii", data[:8])[0], struct.unpack("<ii", data[:8])[1], data[8:-2].decode("utf-8", "replace")


def rcon_command(server: dict[str, Any], command: str, timeout: float = 2.0) -> str:
    r = server.get("rcon", {})
    host = r.get("host", "127.0.0.1")
    if host not in {"127.0.0.1", "::1", "localhost"}:
        raise ValueError("RCON must bind to loopback; run the monitor on the same host")
    password = os.environ.get(r.get("password_env", "MULTIPAPER_RCON_PASSWORD"))
    if not password or not r.get("port"):
        raise ValueError("RCON secret env var or port missing")
    rid = int.from_bytes(os.urandom(3), "little") + 1
    with socket.create_connection((host, int(r["port"])), timeout=timeout) as s:
        s.settimeout(timeout)
        _rcon_packet(s, rid, 3, password)
        auth_id, _, _ = _rcon_read(s)
        if auth_id != rid:
            raise PermissionError("RCON authentication failed")
        _rcon_packet(s, rid + 1, 2, command)
        response_id, _, response = _rcon_read(s)
        if response_id != rid + 1:
            raise ConnectionError("RCON response ID mismatch")
        return response


def parse_metrics(text: str) -> dict[str, float | None]:
    out: dict[str, float | None] = {"tps_1m": None, "mspt_avg_5s": None, "mspt_max_5s": None}
    m = re.search(r"TPS from last([^:]*):\s*([\d., ]+)", text, re.I)
    if m:
        values = [float(x) for x in re.findall(r"\d+(?:\.\d+)?", m.group(2))]
        index = 1 if re.search(r"5s\s*,\s*1m", m.group(1), re.I) else 0
        if len(values) > index: out["tps_1m"] = values[index]
    triplets = re.findall(r"([\d]+(?:\.[\d]+)?)/([\d]+(?:\.[\d]+)?)/([\d]+(?:\.[\d]+)?)", text)
    if triplets:
        out["mspt_avg_5s"], out["mspt_max_5s"] = float(triplets[0][0]), float(triplets[0][2])
    return out


def host_metrics(process_dir: str | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"cpu_percent": None, "memory_percent": None, "network_rx_bytes": None, "network_tx_bytes": None,
                              "process_cpu_percent": None, "process_rss_bytes": None, "monitor_cpu_percent": None, "monitor_rss_bytes": None}
    try:
        import psutil  # optional; if absent, unavailable values stay null and are reported honestly
        vm = psutil.virtual_memory()
        net = psutil.net_io_counters()
        result.update(cpu_percent=psutil.cpu_percent(interval=None), memory_percent=vm.percent,
                      memory_used_bytes=vm.used, memory_total_bytes=vm.total,
                      network_rx_bytes=net.bytes_recv, network_tx_bytes=net.bytes_sent,
                      monitor_cpu_percent=psutil.Process(os.getpid()).cpu_percent(interval=None),
                      monitor_rss_bytes=psutil.Process(os.getpid()).memory_info().rss)
        if process_dir:
            expected = os.path.normcase(os.path.realpath(process_dir))
            for proc in psutil.process_iter(["name", "cwd", "memory_info"]):
                try:
                    if (proc.info.get("name") or "").lower().startswith("java") and os.path.normcase(os.path.realpath(proc.info.get("cwd") or "")) == expected:
                        result["process_cpu_percent"] = proc.cpu_percent(interval=None)
                        result["process_rss_bytes"] = proc.info["memory_info"].rss
                        break
                except (psutil.AccessDenied, psutil.NoSuchProcess, OSError):
                    continue
    except ImportError:
        if sys.platform.startswith("linux"):
            try:
                fields = Path("/proc/meminfo").read_text().splitlines()
                mem = {x.split(":", 1)[0]: int(x.split()[1]) for x in fields if ":" in x}
                total, avail = mem["MemTotal"], mem.get("MemAvailable", 0)
                result.update(memory_percent=round((total-avail)*100/total, 2), memory_used_bytes=(total-avail)*1024, memory_total_bytes=total*1024)
                rx = tx = 0
                for row in Path("/proc/net/dev").read_text().splitlines()[2:]:
                    vals = row.split(":", 1)[1].split(); rx += int(vals[0]); tx += int(vals[8])
                result.update(network_rx_bytes=rx, network_tx_bytes=tx)
            except (OSError, KeyError, ValueError):
                pass
    return result


class Monitor:
    def __init__(self, config_path: Path):
        self.config_path = config_path
        self.cfg = load_config(config_path)
        self.root = Path(self.cfg["output_dir"]).expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        retention = self.cfg["retention"]
        self.max_bytes = int(retention["max_bytes_per_file"])
        self.keep = int(retention["keep_rotated"])
        self.global_out = BoundedJsonl(self.root / "timeline.jsonl", self.max_bytes, self.keep)
        self.salt = os.urandom(32)
        # (inode, byte offset, digest of the bytes immediately before that offset).
        # The digest detects copy-truncate/rewrite rotations that retain an inode and
        # grow back to at least the old size before the next poll.
        self.offsets: dict[str, tuple[int, int, bytes]] = {}
        # Host counters are sampled once per configured server. Keep a separate
        # time baseline per server so sequential samples do not consume one
        # another's byte delta (PC1 commonly has Master, backend, and Geyser).
        self.last_net: dict[str, tuple[float, int, int]] = {}
        self.session_start = utc_now()
        self.last_summary_day: str | None = None
        self.active_alerts: set[tuple[str, str]] = set()
        self.last_event_alert: dict[tuple[str, str], float] = {}

    def emit(self, server: str, event: dict[str, Any]) -> None:
        BoundedJsonl(self.root / server / "events.jsonl", self.max_bytes, self.keep).write(event)
        self.global_out.write(event)

    def read_logs(self, server: dict[str, Any]) -> None:
        for raw_path in server.get("logs", []):
            path = Path(raw_path).expanduser()
            try:
                st = path.stat()
                key = str(path.resolve())
                previous = self.offsets.get(key)
                offset = st.st_size
                if previous:
                    old_inode, old_offset, old_fingerprint = previous
                    same_file = old_inode == st.st_ino and st.st_size >= old_offset
                    same_tail = same_file and self._log_tail_fingerprint(path, old_offset) == old_fingerprint
                    # New files start at EOF; a changed/truncated existing file starts
                    # at byte zero so events written since rotation are not lost.
                    offset = old_offset if same_tail else 0
                with path.open("rb") as f:
                    f.seek(offset)
                    for raw in f:
                        line = raw.decode("utf-8", "replace")
                        if SECRET_RE.search(line):
                            continue
                        ev = parse_log_line(line, server["id"], key, self.salt)
                        if ev:
                            self.emit(server["id"], ev)
                            if ev["event"] in {"exception", "crash", "inventory_inconsistency", "grim_violation"}:
                                alert_key = (server["id"], ev["event"])
                                now = time.monotonic()
                                if now - self.last_event_alert.get(alert_key, 0) >= 60:
                                    self.emit(server["id"], {"time": utc_now(), "server": server["id"], "event": "alert", "reason": ev["event"], "source": ev["source"]})
                                    self.last_event_alert[alert_key] = now
                    new_offset = f.tell()
                    self.offsets[key] = (st.st_ino, new_offset, self._log_tail_fingerprint(path, new_offset))
            except (OSError, UnicodeError):
                continue

    @staticmethod
    def _log_tail_fingerprint(path: Path, offset: int, window_bytes: int = 256) -> bytes:
        if offset <= 0:
            return b""
        start = max(0, offset - window_bytes)
        with path.open("rb") as f:
            f.seek(start)
            return hashlib.sha256(f.read(offset - start)).digest()

    def sample_server(self, server: dict[str, Any]) -> dict[str, Any]:
        metrics: dict[str, Any] = {"time": utc_now(), "server": server["id"], "role": server.get("role", "unknown"), **host_metrics(server.get("process_dir")),
                                   "tps_1m": None, "mspt_avg_5s": None, "mspt_max_5s": None, "players_online": None, "rcon_available": False}
        r = server.get("rcon", {})
        if r.get("port") and os.environ.get(r.get("password_env", "MULTIPAPER_RCON_PASSWORD")):
            try:
                tps = parse_metrics(rcon_command(server, "tps"))
                mspt = parse_metrics(rcon_command(server, "mspt"))
                listing = rcon_command(server, "list")
                metrics.update(tps_1m=tps["tps_1m"], mspt_avg_5s=mspt["mspt_avg_5s"], mspt_max_5s=mspt["mspt_max_5s"],
                               players_online=(int(re.search(r"There are (\d+) of a max", listing).group(1)) if re.search(r"There are (\d+) of a max", listing) else None), rcon_available=True)
            except (OSError, ValueError, PermissionError, ConnectionError, TimeoutError):
                metrics["rcon_available"] = False
        rx, tx = metrics.get("network_rx_bytes"), metrics.get("network_tx_bytes")
        now = time.monotonic()
        previous_net = self.last_net.get(server["id"])
        if isinstance(rx, int) and isinstance(tx, int) and previous_net:
            elapsed = max(0.001, now - previous_net[0])
            metrics["network_rx_bytes_per_sec"] = max(0, rx - previous_net[1]) / elapsed
            metrics["network_tx_bytes_per_sec"] = max(0, tx - previous_net[2]) / elapsed
        else:
            metrics["network_rx_bytes_per_sec"] = None
            metrics["network_tx_bytes_per_sec"] = None
        if isinstance(rx, int) and isinstance(tx, int): self.last_net[server["id"]] = (now, rx, tx)
        for p in server.get("data_paths", []):
            try:
                du = shutil.disk_usage(p)
                metrics["disk_percent"] = round(du.used * 100 / du.total, 2)
                metrics["disk_free_bytes"] = du.free
                break
            except OSError:
                pass
        BoundedJsonl(self.root / server["id"] / "metrics.jsonl", self.max_bytes, self.keep).write(metrics)
        self.global_out.write({k: v for k, v in metrics.items() if k not in {"memory_used_bytes", "memory_total_bytes", "network_rx_bytes", "network_tx_bytes", "disk_free_bytes"}} | {"event": "metrics"})
        self.alerts(server, metrics)
        return metrics

    def alerts(self, server: dict[str, Any], m: dict[str, Any]) -> None:
        thresholds = self.cfg["thresholds"]
        checks = [("tps_1m", "tps_warn", lambda a,b: a < b), ("mspt_max_5s", "mspt_warn", lambda a,b: a > b),
                  ("cpu_percent", "cpu_percent_warn", lambda a,b: a > b), ("memory_percent", "memory_percent_warn", lambda a,b: a > b),
                  ("disk_percent", "disk_percent_warn", lambda a,b: a > b)]
        for metric, threshold, compare in checks:
            value, limit = m.get(metric), thresholds.get(threshold)
            key = (server["id"], metric)
            active = value is not None and limit is not None and compare(value, limit)
            if active and key not in self.active_alerts:
                self.emit(server["id"], {"time": utc_now(), "server": server["id"], "event": "alert", "metric": metric, "value": value, "threshold": limit})
            elif not active and key in self.active_alerts:
                self.emit(server["id"], {"time": utc_now(), "server": server["id"], "event": "alert_recovered", "metric": metric, "value": value})
            if active: self.active_alerts.add(key)
            else: self.active_alerts.discard(key)

    def run_once(self) -> None:
        for server in self.cfg["servers"]:
            self.read_logs(server)
            self.sample_server(server)
        today = dt.datetime.now(dt.timezone.utc).date().isoformat()
        if today != self.last_summary_day:
            self.write_daily_summary()
            self.last_summary_day = today

    def write_daily_summary(self) -> None:
        day = dt.datetime.now(dt.timezone.utc).date().isoformat()
        summary = {"date_utc": day, "generated_utc": utc_now(), "retention_days": self.cfg["retention"].get("days", 14), "servers": {}}
        for server in self.cfg["servers"]:
            metrics = self.root / server["id"] / "metrics.jsonl"
            events = self.root / server["id"] / "events.jsonl"
            summary["servers"][server["id"]] = {"latest_metrics": self._last_json(metrics), "event_counts_today": self._event_counts(events, day)}
        dest = self.root / "summaries" / f"{day}.json"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
        cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=int(self.cfg["retention"].get("days", 14)))
        for p in (self.root / "summaries").glob("*.json"):
            try:
                if dt.date.fromisoformat(p.stem) < cutoff.date(): p.unlink()
            except ValueError: pass

    @staticmethod
    def _last_json(path: Path) -> dict[str, Any] | None:
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
            return json.loads(lines[-1]) if lines else None
        except (OSError, json.JSONDecodeError): return None

    @staticmethod
    def _event_counts(path: Path, day: str) -> dict[str, int]:
        counts: dict[str, int] = {}
        try:
            for line in path.read_text(encoding="utf-8").splitlines():
                e=json.loads(line)
                if str(e.get("time", "")).startswith(day): counts[e.get("event", "unknown")]=counts.get(e.get("event", "unknown"),0)+1
        except (OSError, json.JSONDecodeError): pass
        return counts

    def export(self, destination: Path) -> None:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as z:
            for p in self.root.rglob("*"):
                rel = p.relative_to(self.root)
                name_ok = rel.name == "timeline.jsonl" or re.fullmatch(r"(?:events|metrics)\.jsonl(?:\.\d+)?", rel.name) or (rel.parent.name == "summaries" and re.fullmatch(r"\d{4}-\d{2}-\d{2}\.json", rel.name))
                if p.is_file() and p.resolve() != destination.resolve() and name_ok: z.write(p, rel)
            z.writestr("EXPORT-NOTES.txt", "MultiPaper beta monitor export. No raw Minecraft logs, configuration, RCON credentials, chat, IP addresses, or player names are included. Player identifiers, if present in events, are per-process salted hashes.\n")

    def merge_export(self, source: Path) -> int:
        merged = 0
        with zipfile.ZipFile(source) as z:
            for name in z.namelist():
                p = Path(name)
                if p.is_absolute() or ".." in p.parts or p.name not in {"events.jsonl", "metrics.jsonl"}:
                    continue
                server = p.parent.name
                if not re.fullmatch(r"[A-Za-z0-9_.-]{1,48}", server):
                    continue
                for line in z.read(name).decode("utf-8", "replace").splitlines():
                    try: item = json.loads(line)
                    except json.JSONDecodeError: continue
                    item["merged_from"] = source.name
                    item["event"] = item.get("event", "metrics")
                    self.global_out.write(item)
                    merged += 1
        return merged


def main() -> int:
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--config", type=Path, required=True)
    ap.add_argument("--once", action="store_true", help="collect one sample and exit")
    ap.add_argument("--export", type=Path, help="write a zip of current bounded metrics/events")
    ap.add_argument("--merge", type=Path, help="merge another host's exported JSONL bundle into this host's combined timeline")
    ap.add_argument("--version", action="version", version=VERSION)
    args=ap.parse_args()
    monitor=Monitor(args.config)
    if args.merge:
        print(f"Merged {monitor.merge_export(args.merge)} sanitized records")
        return 0
    if args.export:
        monitor.export(args.export); return 0
    if args.once:
        monitor.run_once(); return 0
    try:
        while True:
            started=time.monotonic()
            monitor.run_once()
            time.sleep(max(0.1, float(monitor.cfg["poll_seconds"])-(time.monotonic()-started)))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
