from __future__ import annotations

import unittest

from tools.devctl import cli
from tools.devctl.subprocess_utils import DevctlError


class CliTest(unittest.TestCase):
    def test_main_dispatches_gate_command(self) -> None:
        captured: list[tuple[str, ...]] = []
        original = cli.dispatch_gate
        try:
            cli.dispatch_gate = lambda args: captured.append(tuple(args))  # type: ignore[assignment]
            exit_code = cli.main(["gate", "merge-unblock", "--skip-lifecycle"])
        finally:
            cli.dispatch_gate = original  # type: ignore[assignment]

        self.assertEqual(0, exit_code)
        self.assertEqual([("merge-unblock", "--skip-lifecycle")], captured)

    def test_main_returns_one_on_gate_error(self) -> None:
        original = cli.dispatch_gate
        try:
            cli.dispatch_gate = (  # type: ignore[assignment]
                lambda _args: (_ for _ in ()).throw(DevctlError("DEVICE_ERROR", "gate failed"))
            )
            exit_code = cli.main(["gate", "promotion"])
        finally:
            cli.dispatch_gate = original  # type: ignore[assignment]

        self.assertEqual(1, exit_code)


if __name__ == "__main__":
    unittest.main()
