import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmark_parity import build_parity_report, load_pocketpal_rows, normalize_pocket_gpt_rows


class BenchmarkParityTest(unittest.TestCase):
    def test_build_parity_report_passes_decode_and_skips_missing_ttft(self):
        gpt_rows = [
            {
                "decode_tps": 12.0,
                "first_token_ms": 1000.0,
                "prefill_ms": 900.0,
                "model_load_ms": 500.0,
                "scenario": "A",
                "source": "pocket-gpt",
            },
        ]
        pal_rows = [
            {
                "decode_tps": 10.0,
                "first_token_ms": None,
                "prefill_ms": None,
                "model_load_ms": None,
                "scenario": "A",
                "source": "pocketpal",
            },
        ]

        report = build_parity_report(gpt_rows, pal_rows, min_tps_ratio=0.8, max_first_token_ratio=1.25)

        self.assertEqual(report["overall"], "PASS")
        self.assertEqual(report["checks"][0]["status"], "PASS")
        self.assertEqual(report["checks"][1]["status"], "SKIP")

    def test_load_pocketpal_rows_reads_raw_export_shape(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            export_path = Path(temp_dir) / "pocketpal.json"
            export_path.write_text(
                """
                {
                  "deviceInfo": {"model": "Test Phone"},
                  "benchmark": {
                    "tgAvg": 14.2,
                    "time_to_first_token_ms": 800,
                    "scenario": "A"
                  }
                }
                """.strip(),
                encoding="utf-8",
            )

            rows = load_pocketpal_rows(str(export_path), "A")

            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["decode_tps"], 14.2)
            self.assertEqual(rows[0]["first_token_ms"], 800.0)

    def test_normalize_pocket_gpt_rows_filters_scenario(self):
        rows = [
            {"scenario": "A", "decode_tps": "4.0", "first_token_ms": "10", "prefill_ms": "11", "model_load_ms": "12"},
            {"scenario": "B", "decode_tps": "9.0", "first_token_ms": "20", "prefill_ms": "21", "model_load_ms": "22"},
        ]

        normalized = normalize_pocket_gpt_rows(rows, "A")

        self.assertEqual(len(normalized), 1)
        self.assertEqual(normalized[0]["decode_tps"], 4.0)


if __name__ == "__main__":
    unittest.main()
