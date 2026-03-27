from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.maestro_android import cli
from tools.maestro_android.common import MaestroAndroidError


class CliTest(unittest.TestCase):
    def test_main_dispatches_lane(self) -> None:
        captured: list[list[str]] = []
        original = cli.run_subprocess
        try:
            cli.run_subprocess = lambda command, **_kwargs: captured.append(list(command))  # type: ignore[assignment]
            exit_code = cli.main(["lane", "smoke"])
        finally:
            cli.run_subprocess = original  # type: ignore[assignment]

        self.assertEqual(0, exit_code)
        self.assertEqual([["python3", "tools/devctl/main.py", "lane", "maestro"]], captured)

    def test_scoped_requires_title_description_comments(self) -> None:
        tmp_root = Path.cwd() / "tmp"
        tmp_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=tmp_root) as tmp:
            flow_path = Path(tmp) / "bad.yaml"
            flow_path.write_text("appId: x\n---\n- launchApp\n", encoding="utf-8")
            with self.assertRaises(MaestroAndroidError):
                cli._validate_scoped_flow(flow_path, cli.load_config())


if __name__ == "__main__":
    unittest.main()
