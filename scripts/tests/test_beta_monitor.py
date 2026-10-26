import importlib.util
import json
import tempfile
import unittest
import zipfile
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


if __name__ == "__main__": unittest.main()
