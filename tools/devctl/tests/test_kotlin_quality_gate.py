from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.devctl.kotlin_quality_gate import Finding, collect_findings, run_gate


class KotlinQualityGateTest(unittest.TestCase):
    def test_collect_findings_parses_detekt_and_ktlint_reports(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            detekt_report = root / "apps/mobile-android/build/reports/detekt/detekt.xml"
            detekt_report.parent.mkdir(parents=True, exist_ok=True)
            detekt_report.write_text(
                """
                <smell-baseline>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/A.kt">
                    <error line="12" source="style:MagicNumber" message="Magic number." />
                  </file>
                </smell-baseline>
                """.strip(),
                encoding="utf-8",
            )
            ktlint_report = root / "packages/core-domain/build/reports/ktlint/ktlintMainSourceSetCheck.xml"
            ktlint_report.parent.mkdir(parents=True, exist_ok=True)
            ktlint_report.write_text(
                """
                <checkstyle>
                  <file name="packages/core-domain/src/commonMain/kotlin/com/example/B.kt">
                    <error line="3" source="standard:max-line-length" message="Too long." />
                  </file>
                </checkstyle>
                """.strip(),
                encoding="utf-8",
            )

            findings = collect_findings(root)

            self.assertEqual(2, len(findings))
            self.assertEqual(
                Finding(
                    tool="detekt",
                    path=Path("apps/mobile-android/src/main/kotlin/com/example/A.kt"),
                    line_number=12,
                    rule_id="style:MagicNumber",
                    message="Magic number.",
                ),
                findings[0],
            )
            self.assertEqual(
                Finding(
                    tool="ktlint",
                    path=Path("packages/core-domain/src/commonMain/kotlin/com/example/B.kt"),
                    line_number=3,
                    rule_id="standard:max-line-length",
                    message="Too long.",
                ),
                findings[1],
            )

    def test_run_gate_filters_findings_to_changed_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            detekt_report = root / "apps/mobile-android/build/reports/detekt/detekt.xml"
            detekt_report.parent.mkdir(parents=True, exist_ok=True)
            detekt_report.write_text(
                """
                <smell-baseline>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/A.kt">
                    <error line="12" source="style:MagicNumber" message="Magic number." />
                  </file>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/C.kt">
                    <error line="22" source="complexity:LongMethod" message="Long method." />
                  </file>
                </smell-baseline>
                """.strip(),
                encoding="utf-8",
            )

            with mock.patch(
                "tools.devctl.kotlin_quality_gate._git_changed_files",
                return_value=[Path("apps/mobile-android/src/main/kotlin/com/example/A.kt")],
            ):
                findings, changed_findings, changed_files = run_gate(
                    root,
                    strict_changed_only=True,
                    skip_gradle=True,
                )

            self.assertEqual(2, len(findings))
            self.assertEqual(1, len(changed_findings))
            self.assertEqual(Path("apps/mobile-android/src/main/kotlin/com/example/A.kt"), changed_files[0])

    def test_run_gate_keeps_changed_findings_empty_when_changed_files_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            detekt_report = root / "apps/mobile-android/build/reports/detekt/detekt.xml"
            detekt_report.parent.mkdir(parents=True, exist_ok=True)
            detekt_report.write_text(
                """
                <smell-baseline>
                  <file name="apps/mobile-android/src/main/kotlin/com/example/A.kt">
                    <error line="12" source="style:MagicNumber" message="Magic number." />
                  </file>
                </smell-baseline>
                """.strip(),
                encoding="utf-8",
            )

            with mock.patch("tools.devctl.kotlin_quality_gate._git_changed_files", return_value=[]):
                findings, changed_findings, changed_files = run_gate(
                    root,
                    strict_changed_only=True,
                    skip_gradle=True,
                )

            self.assertEqual(1, len(findings))
            self.assertEqual(0, len(changed_findings))
            self.assertEqual([], changed_files)


if __name__ == "__main__":
    unittest.main()
