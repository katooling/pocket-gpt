from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.devctl import gates


class GatesTest(unittest.TestCase):
    def test_should_run_lifecycle_for_main_push(self) -> None:
        should_run, reason = gates._should_run_lifecycle(
            event_name="push",
            ref_name="main",
            risk_labels=[],
            changed_files=[],
            force=False,
            skip=False,
        )
        self.assertTrue(should_run)
        self.assertIn("main-push", reason)

    def test_should_run_lifecycle_for_risk_label(self) -> None:
        should_run, reason = gates._should_run_lifecycle(
            event_name="pull_request",
            ref_name="feature",
            risk_labels=["risk:runtime"],
            changed_files=[],
            force=False,
            skip=False,
        )
        self.assertTrue(should_run)
        self.assertIn("risk-label", reason)

    def test_classify_journey_failure_accepts_unknown_qa13_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "journey-report.json"
            report_path.write_text(
                json.dumps(
                    {
                        "steps": [
                            {
                                "name": "run-01:send-capture",
                                "status": "failed",
                                "phase": "error",
                                "runtime_status": "unknown",
                                "backend": "unknown",
                                "active_model_id": "unknown",
                                "placeholder_visible": False,
                                "failure_signature": "send-capture-kickoff: tcp forward error",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            failure_kind, failure_reason = gates._classify_journey_failure(f"See {report_path}")
            self.assertEqual("harness_noise_fail", failure_kind)
            self.assertEqual("journey-send-kickoff-harness", failure_reason)

    def test_run_gate_command_downgrades_harness_noise_when_allowed(self) -> None:
        original = gates.run_subprocess
        try:
            gates.run_subprocess = lambda *args, **kwargs: subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="MainActivityUiSmokeTest",
                stderr="No compose hierarchies found in the app",
            )
            step = gates._run_gate_command(
                name="screenshot-pack",
                command=["python3", "tools/devctl/main.py", "lane", "screenshot-pack"],
                env={},
                allow_harness_noise=True,
            )
        finally:
            gates.run_subprocess = original

        self.assertEqual("caveat", step.status)
        self.assertEqual("harness_noise_fail", step.correctness)
        self.assertFalse(step.blocking)

    def test_run_gate_command_keeps_harness_noise_blocking_when_not_allowed(self) -> None:
        original = gates.run_subprocess
        try:
            gates.run_subprocess = lambda *args, **kwargs: subprocess.CompletedProcess(
                args=[],
                returncode=1,
                stdout="MainActivityUiSmokeTest",
                stderr="No compose hierarchies found in the app",
            )
            step = gates._run_gate_command(
                name="screenshot-pack",
                command=["python3", "tools/devctl/main.py", "lane", "screenshot-pack"],
                env={},
                allow_harness_noise=False,
            )
        finally:
            gates.run_subprocess = original

        self.assertEqual("failed", step.status)
        self.assertEqual("harness_noise_fail", step.correctness)
        self.assertTrue(step.blocking)

    def test_write_report_recreates_parent_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "build/devctl/gates/report.json"
            step = gates.GateStepResult(
                name="merge",
                command=["bash", "scripts/dev/test.sh", "merge"],
                started_at="2026-03-10T00:00:00",
                duration_seconds=1.23,
                status="passed",
                correctness="pass",
                blocking=False,
            )
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.parent.rmdir()
            gates._write_report(
                gate_name="merge-unblock",
                steps=[step],
                report_path=report_path,
                metadata={},
            )
            self.assertTrue(report_path.exists())

    def test_extract_failure_reason_prefers_devctl_error_line(self) -> None:
        reason = gates._extract_failure_reason(
            "\n".join(
                [
                    "[devctl] $ python3 tools/devctl/main.py lane android-instrumented",
                    "DEVICE_ERROR: Instrumentation failed for test class",
                    "INSTRUMENTATION_CODE: 0",
                ]
            ),
            exit_code=1,
        )
        self.assertEqual("DEVICE_ERROR: Instrumentation failed for test class", reason)


if __name__ == "__main__":
    unittest.main()
