from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.devctl import governance
from tools.devctl.subprocess_utils import DevctlError


class GovernanceTest(unittest.TestCase):
    def _seed_docs_health_repo(self, root: Path) -> None:
        (root / "docs/start-here").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/tickets").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/evidence/wp-09").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/evidence/wp-12").mkdir(parents=True, exist_ok=True)
        (root / "docs/operations/evidence/wp-13").mkdir(parents=True, exist_ok=True)
        (root / "scripts/dev").mkdir(parents=True, exist_ok=True)
        (root / "scripts/android").mkdir(parents=True, exist_ok=True)
        (root / "tests/maestro").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android").mkdir(parents=True, exist_ok=True)
        (root / "apps/mobile-android-host").mkdir(parents=True, exist_ok=True)
        (root / "docs/ux").mkdir(parents=True, exist_ok=True)

        (root / "README.md").write_text("# Root\n", encoding="utf-8")
        (root / "scripts/dev/README.md").write_text("# Dev\n", encoding="utf-8")
        (root / "scripts/android/README.md").write_text("# Android\n", encoding="utf-8")
        (root / "tests/maestro/README.md").write_text("# Maestro\n", encoding="utf-8")
        (root / "apps/mobile-android/README.md").write_text("# App\n", encoding="utf-8")
        (root / "apps/mobile-android-host/README.md").write_text("# Host\n", encoding="utf-8")

        (root / "docs/start-here/new-joiner.md").write_text("# New Joiner\n", encoding="utf-8")
        (root / "docs/start-here/resource-map.md").write_text("# Resource Map\n", encoding="utf-8")

        (root / "docs/README.md").write_text(
            "- start-here/new-joiner.md\n- start-here/resource-map.md\n",
            encoding="utf-8",
        )
        (root / "docs/operations/README.md").write_text(
            "- execution-board.md\n- evidence/index.md\n- tickets/\n",
            encoding="utf-8",
        )
        (root / "docs/ux/README.md").write_text(
            "- implemented-behavior-reference.md\n- model-management-flow.md\n",
            encoding="utf-8",
        )
        (root / "docs/operations/execution-board.md").write_text("Status: In Progress\n", encoding="utf-8")
        (root / "docs/operations/tickets/ticket.md").write_text("Status: Ready\n", encoding="utf-8")
        (root / "docs/ux/implemented-behavior-reference.md").write_text("# Impl\n", encoding="utf-8")
        (root / "docs/ux/model-management-flow.md").write_text("# Flow\n", encoding="utf-8")

        (root / "docs/operations/evidence/index.md").write_text(
            "\n".join(["WP-01", "WP-02", "WP-03", "WP-04", "WP-05", "WP-06", "WP-07", "WP-08", "WP-11"]),
            encoding="utf-8",
        )
        (root / "docs/operations/evidence/wp-09/2026-03-04-note.md").write_text("# WP09\n", encoding="utf-8")
        (root / "docs/operations/evidence/wp-13/2026-03-05-note.md").write_text("# WP13\n", encoding="utf-8")

        for note in governance.DOCS_HEALTH_RETAINED_WP12_NOTES:
            (root / "docs/operations/evidence/wp-12" / note).write_text("# WP12\n", encoding="utf-8")

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

    def test_docs_health_passes_for_compliant_structure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            governance.docs_health_check(root)

    def test_docs_health_rejects_status_outside_board_or_tickets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            (root / "docs/product").mkdir(parents=True, exist_ok=True)
            (root / "docs/product/bad.md").write_text("Status: Stale\n", encoding="utf-8")
            with self.assertRaises(DevctlError) as raised:
                governance.docs_health_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_docs_health_rejects_broken_local_markdown_links(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._seed_docs_health_repo(root)
            (root / "docs/README.md").write_text(
                "- start-here/new-joiner.md\n- start-here/resource-map.md\n- [bad](missing.md)\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                governance.docs_health_check(root)
            self.assertEqual("CONFIG_ERROR", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
