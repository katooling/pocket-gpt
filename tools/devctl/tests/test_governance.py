from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.devctl import governance
from tools.devctl.subprocess_utils import DevctlError


class GovernanceTest(unittest.TestCase):
    def test_evidence_check_pass_and_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_path = root / "scripts/benchmarks/runs/2026-03-03/DEVICE/run"
            run_path.mkdir(parents=True, exist_ok=True)
            (run_path / "artifact.txt").write_text("ok", encoding="utf-8")

            ok_note = root / "ok.md"
            ok_note.write_text(
                "Evidence\n- raw: scripts/benchmarks/runs/2026-03-03/DEVICE/run/artifact.txt\n",
                encoding="utf-8",
            )
            governance.evidence_check("ok.md", repo_root=root)

            bad_note = root / "bad.md"
            bad_note.write_text(
                "Evidence\n- raw: scripts/benchmarks/runs/2026-03-03/DEVICE/run/missing.txt\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.evidence_check("bad.md", repo_root=root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_validate_pr_body_rejects_unchecked_requirements(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pr = root / "pr.md"
            pr.write_text("- [ ] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n", encoding="utf-8")
            with self.assertRaises(DevctlError) as raised:
                governance.validate_pr_body("pr.md", repo_root=root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_stage_close_gate_skip_when_not_requested(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pr = root / "pr.md"
            pr.write_text("Stage close: no\n", encoding="utf-8")
            governance.stage_close_gate("pr.md", repo_root=root)

    def test_evidence_check_changed_skips_outside_pr(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            governance.evidence_check_changed(event_name="push", base_ref="main", repo_root=root)

    def test_docs_drift_missing_source_of_truth_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "scripts/dev").mkdir(parents=True, exist_ok=True)
            (root / "docs/testing").mkdir(parents=True, exist_ok=True)
            (root / "docs/operations").mkdir(parents=True, exist_ok=True)

            (root / "scripts/dev/README.md").write_text("Source of truth", encoding="utf-8")
            (root / "docs/testing/test-strategy.md").write_text("Source of truth", encoding="utf-8")
            (root / "docs/testing/android-dx-and-test-playbook.md").write_text("Source of truth", encoding="utf-8")
            (root / "README.md").write_text("no marker", encoding="utf-8")

            with self.assertRaises(DevctlError) as raised:
                governance.docs_drift_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
