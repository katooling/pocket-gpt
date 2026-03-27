from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from tools.devctl.reporting import find_latest_bundle


class ReportingTest(unittest.TestCase):
    def test_find_latest_journey_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            older = root / "tmp/devctl-artifacts/2026-03-10/SER123/journey/010101/journey-report.json"
            newer = root / "tmp/devctl-artifacts/2026-03-11/SER123/journey/020202/journey-report.json"
            older.parent.mkdir(parents=True, exist_ok=True)
            newer.parent.mkdir(parents=True, exist_ok=True)
            older.write_text("{}\n", encoding="utf-8")
            newer.write_text("{}\n", encoding="utf-8")
            os.utime(older, (1_700_000_000, 1_700_000_000))
            os.utime(newer, (1_700_000_100, 1_700_000_100))

            bundle = find_latest_bundle("journey", repo_root=root)

        self.assertEqual(newer.parent, bundle.artifact_root)
        self.assertEqual([newer], list(bundle.report_files))

    def test_find_latest_screenshot_pack_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            older = root / "scripts/benchmarks/runs/2026-03-10/SER123/screenshot-pack/010101/inventory-report.json"
            newer = root / "scripts/benchmarks/runs/2026-03-11/SER123/screenshot-pack/020202/inventory-report.json"
            older.parent.mkdir(parents=True, exist_ok=True)
            newer.parent.mkdir(parents=True, exist_ok=True)
            older.write_text("{}\n", encoding="utf-8")
            newer.write_text("{}\n", encoding="utf-8")
            os.utime(older, (1_700_000_000, 1_700_000_000))
            os.utime(newer, (1_700_000_200, 1_700_000_200))

            bundle = find_latest_bundle("screenshot-pack", repo_root=root)

        self.assertEqual(newer.parent, bundle.artifact_root)
        self.assertEqual([newer], list(bundle.report_files))


if __name__ == "__main__":
    unittest.main()
