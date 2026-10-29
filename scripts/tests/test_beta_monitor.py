import importlib.util
import json
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

MODULE = Path(__file__).parents[1] / "multipaper-beta-monitor.py"
spec = importlib.util.spec_from_file_location("beta_monitor", MODULE)
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class BetaMonitorTests(unittest.TestCase):
    def test_paper_tps_and_mspt_output(self):
        parsed = monitor.parse_metrics("TPS from last 5s, 1m, 5m, 15m: 19.9, 18.5, 20.0, 20.0\nServer tick times avg/min/max from last 5s, 10s, 1m: 3.0/1.0/7.0, 4.0/2.0/8.0, 5.0/3.0/9.0")
        self.assertEqual(parsed["tps_1m"], 18.5)
        self.assertEqual(parsed["mspt_avg_5s"], 3.0)
        self.assertEqual(parsed["mspt_max_5s"], 7.0)

    def test_sync_latency_is_numeric_and_sanitized(self):
        event = monitor.parse_log_line("[12:00:00 INFO]: sync latency player DeltaPvP_ 42.5 ms token=hidden", "master", "latest.log", b"salt")
        self.assertEqual(event["event"], "sync_latency")
        self.assertEqual(event["latency_ms"], 42.5)
        self.assertNotIn("hidden", json.dumps(event))

    def test_requested_runtime_event_categories(self):
        cases = {
            "backend ownership transfer": "ownership",
            "chunk loaded": "chunk_load",
            "scheduled tick journal acknowledged": "scheduled_tick_journal",
            "entity replication completed": "entity_replication",
            "player changed dimension": "dimension_change",
            "plugin exception": "exception",
            "Grim violation": "grim_violation",
            "server recovery completed": "recovery",
            "inventory inconsistency": "inventory_inconsistency",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                event = monitor.parse_log_line(text, "backend2", "latest.log", b"salt")
                self.assertEqual(event["event"], expected)
        journal = monitor.parse_log_line("scheduled tick journal acknowledged", "backend2", "latest.log", b"salt")
        self.assertEqual(journal["journal_state"], "healthy")

    def test_crafty_data_integrity_warnings_are_categorized_without_raw_details(self):
        cases = {
            "[20:09:52 WARN]: Paper I/O Worker #0 has no chunk holder for reading chunk world,region,12,34, reading it straight from disk instead": "chunk_read_fallback",
            "[16:27:01 WARN]: Entity uuid already exists: 12345678-1234-1234-1234-123456789abc": "entity_uuid_conflict",
            "[16:27:01 WARN]: UUID of added entity already exists: Pig['pig'/42, ...]": "entity_uuid_conflict",
            "[16:27:02 WARN]: Tried to create an entity from nbt, but the entities for that chunk aren't loaded: Pig['pig'/42, ...]": "entity_update_unavailable",
            "[16:27:03 WARN]: Could not route removal of entity 12345678-1234-1234-1234-123456789abc from non-owner backend to its chunk owner; broadcasting removal as fallback": "entity_removal_route_fallback",
            "[16:27:04 WARN]: Mona: Ignoring stale inventory replacement 1 minecraft:diamond -> 0 minecraft:air. Re-syncing the authoritative slot.": "inventory_stale_replacement",
            "[16:27:05 WARN]: Mona moved too quickly!": "movement_correction",
        }
        for line, expected in cases.items():
            with self.subTest(event=expected):
                event = monitor.parse_log_line(line, "backend1", "latest.log", b"salt")
                self.assertEqual(event["event"], expected)
                self.assertEqual(event["severity"], "warning")
                self.assertNotIn("Mona", json.dumps(event))
                self.assertNotIn("12345678", json.dumps(event))
                self.assertNotIn("Pig", json.dumps(event))

    def test_master_rejected_chunk_write_is_urgent_and_sanitized(self):
        line = "java.io.IOException: Master rejected persistence of region chunk -3,14 in world rc5world"
        event = monitor.parse_log_line(line, "backend1", "latest.log", b"salt")
        self.assertEqual(event["event"], "chunk_write_rejected")
        self.assertEqual(event["severity"], "error")
        serialized = json.dumps(event)
        self.assertNotIn("-3,14", serialized)
        self.assertNotIn("rc5world", serialized)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / "latest.log"
            log.write_text("")
            cfg = root / "private.json"
            cfg.write_text(json.dumps({
                "output_dir": str(root / "monitor"),
                "servers": [{"id": "backend1", "logs": [str(log)]}],
            }))
            m = monitor.Monitor(cfg)
            server = m.cfg["servers"][0]
            m.read_logs(server)  # Establish the current end-of-log offset.
            with log.open("a", encoding="utf-8") as stream:
                stream.write(line + "\n")
            m.read_logs(server)
            events = root / "monitor" / "backend1" / "events.jsonl"
            records = [json.loads(value) for value in events.read_text().splitlines()]
            self.assertEqual([value["event"] for value in records], ["chunk_write_rejected", "alert"])
            self.assertEqual(records[1]["reason"], "chunk_write_rejected")
            self.assertNotIn("-3,14", json.dumps(records))
            self.assertNotIn("rc5world", json.dumps(records))

    def test_integrity_warning_alerts_are_immediate_rate_limited_and_sanitized(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / "latest.log"
            log.write_bytes(b"")
            cfg = root / "private.json"
            cfg.write_text(json.dumps({
                "output_dir": str(root / "monitor"),
                "servers": [{"id": "backend1", "logs": [str(log)]}],
            }))
            m = monitor.Monitor(cfg)
            server = m.cfg["servers"][0]
            m.read_logs(server)  # Establish an empty-file offset.

            warning = ("[20:09:52 WARN]: Worker has no chunk holder for reading chunk "
                       "world,region,12,34, reading it straight from disk instead\n")
            with patch.object(monitor.time, "monotonic", side_effect=[0.0, 59.0, 60.0]):
                for _ in range(3):
                    with log.open("ab") as f:
                        f.write(warning.encode())
                    m.read_logs(server)

            event_path = root / "monitor" / "backend1" / "events.jsonl"
            records = [json.loads(line) for line in event_path.read_text().splitlines()]
            self.assertEqual([r["event"] for r in records].count("chunk_read_fallback"), 3)
            alerts = [r for r in records if r["event"] == "alert"]
            self.assertEqual(len(alerts), 2, "alert immediately, suppress within 60s, alert at 60s")
            self.assertTrue(all(r["reason"] == "chunk_read_fallback" for r in alerts))
            serialized = json.dumps(records)
            self.assertNotIn("12,34", serialized)
            self.assertNotIn("Worker", serialized)

    def test_log_events_keep_no_chat_or_player_name(self):
        event = monitor.parse_log_line("[12:00:00 INFO]: DeltaPvP_ joined the game", "backend1", "/logs/latest.log", b"salt")
        self.assertEqual(event["event"], "player_join")
        self.assertNotIn("DeltaPvP_", json.dumps(event))
        self.assertEqual(len(event["subject"]), 12)
        self.assertIsNone(monitor.parse_log_line("[12:00:00 INFO]: <DeltaPvP_> hello", "backend1", "latest.log", b"salt"))

    def test_bounded_rotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = monitor.BoundedJsonl(Path(tmp) / "events.jsonl", 55, 2)
            for i in range(5): out.write({"i": i, "data": "0123456789"})
            files = list(Path(tmp).glob("events.jsonl*"))
            self.assertLessEqual(len(files), 3)
            self.assertTrue((Path(tmp) / "events.jsonl.1").exists())

    def test_host_network_counter_baselines_are_independent_per_server(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / "private.json"
            cfg.write_text(json.dumps({
                "output_dir": str(root / "monitor"),
                "servers": [{"id": "master"}, {"id": "backend1"}],
            }))
            m = monitor.Monitor(cfg)
            master = m.cfg["servers"][0]
            backend = m.cfg["servers"][1]
            snapshots = [
                {"network_rx_bytes": 100, "network_tx_bytes": 200},
                {"network_rx_bytes": 100, "network_tx_bytes": 200},
                {"network_rx_bytes": 500, "network_tx_bytes": 800},
                {"network_rx_bytes": 500, "network_tx_bytes": 800},
            ]
            with patch.object(monitor, "host_metrics", side_effect=snapshots), \
                    patch.object(monitor.time, "monotonic", side_effect=[0.0, 0.0, 10.0, 10.0]):
                self.assertIsNone(m.sample_server(master)["network_rx_bytes_per_sec"])
                self.assertIsNone(m.sample_server(backend)["network_rx_bytes_per_sec"])
                master_sample = m.sample_server(master)
                backend_sample = m.sample_server(backend)

            for sample in (master_sample, backend_sample):
                self.assertEqual(sample["network_rx_bytes_per_sec"], 40.0)
                self.assertEqual(sample["network_tx_bytes_per_sec"], 60.0)

    def test_log_tail_detects_same_inode_rewrite_after_copy_truncate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / "latest.log"
            cfg = root / "private.json"
            cfg.write_text(json.dumps({
                "output_dir": str(root / "monitor"),
                "servers": [{"id": "backend1", "logs": [str(log)]}],
            }))
            log.write_bytes(b"[12:00:00 INFO]: Alpha joined the game\n")
            m = monitor.Monitor(cfg)
            server = m.cfg["servers"][0]
            m.read_logs(server)  # Initial tail starts at the current end of file.
            inode_before = log.stat().st_ino

            replacement = b"[12:00:01 INFO]: Bravo joined the game\n"
            self.assertEqual(len(replacement), log.stat().st_size)
            log.write_bytes(replacement)
            self.assertEqual(inode_before, log.stat().st_ino, "Fixture must model same-inode copy-truncate")
            m.read_logs(server)

            events_path = root / "monitor" / "backend1" / "events.jsonl"
            events = [json.loads(line) for line in events_path.read_text().splitlines()] if events_path.exists() else []
            self.assertEqual([event["event"] for event in events], ["player_join"])
            self.assertNotIn("Bravo", json.dumps(events), "Player names must remain redacted")

            with log.open("ab") as f:
                f.write(b"[12:00:02 INFO]: Charlie joined the game\n")
            m.read_logs(server)
            events = [json.loads(line) for line in events_path.read_text().splitlines()]
            self.assertEqual([event["event"] for event in events], ["player_join", "player_join"])
            self.assertNotIn("Charlie", json.dumps(events), "Appended player names must remain redacted")

    def test_export_merge_only_sanitized_record_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / "private.json"
            cfg.write_text(json.dumps({"output_dir": str(root / "monitor"), "servers": [{"id": "pc2-backend"}]}))
            m = monitor.Monitor(cfg)
            m.emit("pc2-backend", {"time": monitor.utc_now(), "server": "pc2-backend", "event": "backend_ready"})
            (root / "monitor" / "accidental-secret.txt").write_text("never export me")
            bundle = root / "pc2.zip"
            m.export(bundle)
            with zipfile.ZipFile(bundle) as z:
                self.assertFalse(any("private.json" in n for n in z.namelist()))
                self.assertFalse(any("accidental-secret" in n for n in z.namelist()))
            cfg2 = root / "pc1.json"
            cfg2.write_text(json.dumps({"output_dir": str(root / "master"), "servers": [{"id": "master"}]}))
            master = monitor.Monitor(cfg2)
            self.assertEqual(master.merge_export(bundle), 1)
            self.assertIn("pc2-backend", (root / "master" / "timeline.jsonl").read_text())

    def test_merge_rejects_private_fields_and_invalid_record_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / "master.json"
            cfg.write_text(json.dumps({"output_dir": str(root / "master"), "servers": [{"id": "master"}]}))
            master = monitor.Monitor(cfg)
            bundle = root / "private-player-name-and-secret.zip"
            record = {
                "time": monitor.utc_now(), "server": "forged-server", "event": "player_join",
                "subject": "012345abcdef", "severity": "info", "source": "latest.log",
                "player_name": "PrivatePlayerName", "chat": "PrivateChatContents",
                "password": "SyntheticRconSecret", "token": "SyntheticToken",
                "arbitrary": {"world": "PrivateWorld"}, "merged_from": "SyntheticOriginCredential",
            }
            malformed = {"time": monitor.utc_now(), "event": "player_leave", "journal_state": ["not", "a", "state"]}
            metrics = {
                "time": monitor.utc_now(), "role": "backend", "cpu_percent": 12.5,
                "rcon_available": True, "private_note": "SyntheticPrivateNote",
            }
            with zipfile.ZipFile(bundle, "w") as z:
                z.writestr("pc2-backend/events.jsonl", json.dumps(record) + "\n" + json.dumps(malformed) + "\n")
                z.writestr("pc2-backend/metrics.jsonl", json.dumps(metrics) + "\n")

            self.assertEqual(master.merge_export(bundle), 3)
            timeline = (root / "master" / "timeline.jsonl").read_text(encoding="utf-8")
            self.assertNotIn("PrivatePlayerName", timeline)
            self.assertNotIn("PrivateChatContents", timeline)
            self.assertNotIn("SyntheticRconSecret", timeline)
            self.assertNotIn("SyntheticToken", timeline)
            self.assertNotIn("PrivateWorld", timeline)
            self.assertNotIn("SyntheticOriginCredential", timeline)
            self.assertNotIn("SyntheticPrivateNote", timeline)
            self.assertNotIn(bundle.name, timeline)
            records = [json.loads(line) for line in timeline.splitlines()]
            self.assertTrue(all(item["server"] == "pc2-backend" for item in records))
            self.assertTrue(all(item["merged_from"] == "peer-export" for item in records))
            self.assertNotIn("journal_state", records[1])
            metric_record = next(item for item in records if item["event"] == "metrics")
            self.assertEqual(metric_record["cpu_percent"], 12.5)
            self.assertEqual(metric_record["rcon_available"], True)

    def test_keyboard_interrupt_flushes_logs_and_summary(self):
        class FakeMonitor:
            cfg = {"poll_seconds": 10, "servers": [{"id": "master"}, {"id": "backend1"}]}

            def __init__(self, _config):
                self.run_once_calls = 0
                self.read_servers = []
                self.summary_calls = 0

            def run_once(self):
                self.run_once_calls += 1

            def read_logs(self, server):
                self.read_servers.append(server["id"])

            def write_daily_summary(self):
                self.summary_calls += 1

        fake_instance = FakeMonitor(Path("private.json"))
        with patch.object(monitor, "Monitor", return_value=fake_instance), \
                patch.object(monitor, "time") as mocked_time, \
                patch("sys.argv", ["multipaper-beta-monitor.py", "--config", "private.json"]):
            mocked_time.monotonic.return_value = 10.0
            mocked_time.sleep.side_effect = KeyboardInterrupt
            result = monitor.main()

        self.assertEqual(result, 0)
        self.assertEqual(fake_instance.run_once_calls, 1)
        self.assertEqual(fake_instance.read_servers, ["master", "backend1"])
        self.assertEqual(fake_instance.summary_calls, 1)

    def test_daily_human_summary_is_readable_and_exported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / "private.json"
            cfg.write_text(json.dumps({"output_dir": str(root / "monitor"), "servers": [{"id": "backend1", "role": "backend"}]}))
            m = monitor.Monitor(cfg)
            metrics = {"time": "2026-10-29T12:00:00Z", "server": "backend1", "role": "backend",
                       "tps_1m": 19.5, "mspt_avg_5s": None, "players_online": 4,
                       "cpu_percent": 22.0, "network_rx_bytes_per_sec": 100.0}
            monitor.BoundedJsonl(m.root / "backend1" / "metrics.jsonl", 10000, 2).write(metrics)
            m.emit("backend1", {"time": monitor.utc_now(), "server": "backend1", "event": "player_join", "subject": "ab12cd34ef56"})
            m.write_daily_summary()

            summaries = m.root / "summaries"
            report = next(summaries.glob("*.txt"))
            rendered = report.read_text(encoding="utf-8")
            self.assertIn("TPS (1m): 19.5", rendered)
            self.assertIn("MSPT average (5s): unavailable", rendered)
            self.assertIn("Players online: 4", rendered)
            self.assertIn("player_join: 1", rendered)
            self.assertNotIn("ab12cd34ef56", rendered)
            self.assertNotIn("private.json", rendered)

            secret_markers = ("ExportPrivatePlayer", "ExportPrivateChat", "ExportSyntheticPassword", "ExportSyntheticToken", "ExportPrivateNote")
            with (m.root / "backend1" / "events.jsonl").open("a", encoding="utf-8") as f:
                f.write(json.dumps({"time": monitor.utc_now(), "server": "backend1", "event": "player_join",
                                    "password": secret_markers[2], "chat": secret_markers[1], "player_name": secret_markers[0]}) + "\n")
            with (m.root / "backend1" / "metrics.jsonl").open("a", encoding="utf-8") as f:
                f.write(json.dumps({"time": monitor.utc_now(), "server": "backend1", "event": "metrics",
                                    "cpu_percent": 1.0, "private_note": secret_markers[4], "token": secret_markers[3]}) + "\n")
            with (m.root / "timeline.jsonl").open("a", encoding="utf-8") as f:
                f.write(json.dumps({"time": monitor.utc_now(), "server": "backend1", "event": "error",
                                    "message": secret_markers[1], "password": secret_markers[2]}) + "\n")
            summary_json = summaries / report.with_suffix(".json").name
            summary_data = json.loads(summary_json.read_text(encoding="utf-8"))
            summary_data["private_note"] = secret_markers[4]
            summary_data["servers"]["backend1"]["private_note"] = secret_markers[4]
            summary_data["servers"]["backend1"]["event_counts_today"][secret_markers[1]] = 4
            summary_json.write_text(json.dumps(summary_data), encoding="utf-8")
            report.write_text(report.read_text(encoding="utf-8") + "\n" + " ".join(secret_markers), encoding="utf-8")

            bundle = root / "support.zip"
            m.export(bundle)
            with zipfile.ZipFile(bundle) as z:
                self.assertIn(f"summaries/{report.name}", z.namelist())
                self.assertFalse(any("private.json" in name for name in z.namelist()))
                exported = "\n".join(z.read(name).decode("utf-8") for name in z.namelist())
                for marker in secret_markers:
                    self.assertNotIn(marker, exported)
                exported_report = z.read(f"summaries/{report.name}").decode("utf-8")
                self.assertIn("TPS (1m): 19.5", exported_report)

    def test_daily_summary_refreshes_at_five_minute_interval(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / "private.json"
            cfg.write_text(json.dumps({"output_dir": str(root / "monitor"), "servers": [{"id": "backend1"}]}))
            m = monitor.Monitor(cfg)
            with patch.object(m, "read_logs"), patch.object(m, "sample_server"), \
                    patch.object(m, "write_daily_summary", wraps=m.write_daily_summary) as write_summary, \
                    patch.object(monitor.time, "monotonic", side_effect=[0.0, 299.0, 300.0]):
                m.run_once()
                m.run_once()
                m.run_once()
            self.assertEqual(write_summary.call_count, 2)

    def test_daily_summary_retention_removes_only_expired_date_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cfg = root / "private.json"
            cfg.write_text(json.dumps({
                "output_dir": str(root / "monitor"),
                "retention": {"max_bytes_per_file": 10000, "keep_rotated": 2, "days": 3},
                "servers": [{"id": "backend1"}],
            }))
            m = monitor.Monitor(cfg)
            summaries = m.root / "summaries"
            summaries.mkdir()
            today = monitor.dt.datetime.now(monitor.dt.timezone.utc).date()
            expired = today - monitor.dt.timedelta(days=4)
            retained = today - monitor.dt.timedelta(days=2)
            old_json = summaries / f"{expired.isoformat()}.json"
            old_text = summaries / f"{expired.isoformat()}.txt"
            retained_text = summaries / f"{retained.isoformat()}.txt"
            unrelated = summaries / "notes.txt"
            for path in (old_json, old_text, retained_text, unrelated):
                path.write_text("synthetic retention fixture", encoding="utf-8")

            m.write_daily_summary()

            self.assertFalse(old_json.exists())
            self.assertFalse(old_text.exists())
            self.assertTrue(retained_text.exists())
            self.assertTrue(unrelated.exists())
            self.assertTrue((summaries / f"{today.isoformat()}.json").exists())
            self.assertTrue((summaries / f"{today.isoformat()}.txt").exists())


if __name__ == "__main__": unittest.main()
