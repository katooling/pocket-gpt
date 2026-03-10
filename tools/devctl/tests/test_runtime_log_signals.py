from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.devctl.runtime_log_signals import analyze_runtime_log_text, write_runtime_log_signal_reports


class RuntimeLogSignalsTest(unittest.TestCase):
    def test_analyze_runtime_log_text_flags_missing_readahead_and_low_acceptance(self) -> None:
        analysis = analyze_runtime_log_text(
            "\n".join(
                [
                    "I/PocketLlamaJNI: MMAP|use_mmap=false|use_mlock=false|use_direct_io=false",
                    "I/PocketLlamaJNI: FLASH_ATTN|requested=true|type=auto|gpu_ops=false|type_k=Q8_0|type_v=Q8_0|n_ctx=2048|n_batch=512|n_ubatch=256",
                    "I/PocketLlamaJNI: SPECULATIVE|accepted=3|drafted=12|max_draft=6|remaining=40|acceptance_rate=0.250",
                    "I/PocketLlamaJNI: SPECULATIVE|accepted=1|drafted=8|max_draft=4|remaining=20|acceptance_rate=0.200",
                    "I/PocketLlamaJNI: PREFIX_CACHE|stage=restore_state|label=target|slot=1|bytes=234881024|success=false|reason=over_budget",
                ],
            ),
        )

        self.assertEqual("warn", analysis["status"])
        self.assertGreaterEqual(analysis["issue_count"], 4)
        issue_kinds = {issue["kind"] for issue in analysis["issues"]}
        self.assertIn("mmap_disabled", issue_kinds)
        self.assertIn("missing_readahead", issue_kinds)
        self.assertIn("flash_attention_without_gpu_ops", issue_kinds)
        self.assertIn("low_speculative_acceptance", issue_kinds)
        self.assertIn("prefix_restore_over_budget", issue_kinds)

    def test_write_runtime_log_signal_reports_emits_json_and_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat_path = Path(tmp) / "logcat.txt"
            json_path = Path(tmp) / "runtime-log-signals.json"
            markdown_path = Path(tmp) / "runtime-log-signals.md"
            logcat_path.write_text(
                "\n".join(
                    [
                        "I/PocketLlamaJNI: MMAP|use_mmap=true|use_mlock=false|use_direct_io=false",
                        "I/PocketLlamaJNI: MMAP|stage=readahead|label=target|bytes=123|result=0",
                        "I/PocketLlamaJNI: FLASH_ATTN|requested=true|type=auto|gpu_ops=true|type_k=Q8_0|type_v=Q8_0|n_ctx=4096|n_batch=768|n_ubatch=384",
                        "I/PocketLlamaJNI: SPECULATIVE|accepted=6|drafted=8|max_draft=6|remaining=64|acceptance_rate=0.750",
                        "I/PocketLlamaJNI: PREFIX_CACHE|stage=store_state|label=target|slot=0|bytes=4096|success=true|reason=stored",
                        "I/PocketLlamaJNI: PREFIX_CACHE|stage=restore_state|label=target|slot=0|bytes=4096|success=true|reason=restored",
                    ],
                ),
                encoding="utf-8",
            )

            analysis = write_runtime_log_signal_reports(logcat_path, json_path, markdown_path)

            self.assertEqual("pass", analysis["status"])
            self.assertTrue(json_path.exists())
            self.assertTrue(markdown_path.exists())
            payload = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual("pass", payload["status"])
            self.assertEqual(2, payload["prefix_cache"]["entry_count"])
            self.assertEqual(1, payload["prefix_cache"]["restore_state_success_count"])
            markdown = markdown_path.read_text(encoding="utf-8")
            self.assertIn("Runtime Log Signals", markdown)
            self.assertIn("No runtime log signal issues were detected.", markdown)


if __name__ == "__main__":
    unittest.main()
