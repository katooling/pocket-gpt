import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class RankStage2SweepTest(unittest.TestCase):
    def test_rank_stage2_sweep_picks_lowest_first_token_variant(self):
        repo_root = Path(__file__).resolve().parents[3]
        script_path = repo_root / "scripts/benchmarks/rank_stage2_sweep.py"

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_root = Path(temp_dir)
            fast_dir = temp_root / "cpu_fast"
            slow_dir = temp_root / "cpu_slow"
            fast_dir.mkdir()
            slow_dir.mkdir()

            header = [
                "scenario",
                "first_token_ms",
                "prefill_ms",
                "model_load_ms",
                "decode_tps",
                "prefix_cache_hit_rate",
                "prefix_cache_reused_tokens",
                "resident_hit_count",
                "peak_rss_mb",
                "crash_or_oom",
            ]
            with (fast_dir / "scenario-a.csv").open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(header)
                writer.writerow(["A", "1200", "1100", "400", "10.5", "0.50", "80", "2", "900", "false"])
            with (slow_dir / "scenario-a.csv").open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(header)
                writer.writerow(["A", "2400", "2100", "500", "9.0", "0.25", "20", "1", "950", "false"])

            out_json = temp_root / "summary.json"
            result = subprocess.run(
                [
                    sys.executable,
                    str(script_path),
                    "--runs-root",
                    str(temp_root),
                    "--scenario",
                    "A",
                    "--out-json",
                    str(out_json),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, msg=result.stderr)
            payload = json.loads(out_json.read_text(encoding="utf-8"))
            self.assertEqual(payload["best_variant"], "cpu_fast")
            self.assertEqual(payload["variant_count"], 2)


if __name__ == "__main__":
    unittest.main()
