from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.devctl.config_models import load_devctl_configs
from tools.devctl.lanes import (
    RuntimeContext,
    _ensure_serial,
    _evaluate_loop_output,
    build_artifact_dir,
    dispatch_lane,
    lane_maestro,
    parse_device_lane_args,
    validate_threshold_columns,
)
from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT


class LanesTest(unittest.TestCase):
    def test_build_artifact_dir_is_deterministic(self) -> None:
        path = build_artifact_dir(
            "scripts/benchmarks/runs/{date}/{device}/{label}-{stamp}",
            "2026-03-03",
            "SER123",
            "scenario-a",
            "20260303-101010",
        )
        self.assertEqual(
            REPO_ROOT / "scripts/benchmarks/runs/2026-03-03/SER123/scenario-a-20260303-101010",
            path,
        )

    def test_parse_device_lane_args_defaults(self) -> None:
        parsed = parse_device_lane_args([], ["./gradlew", ":apps:mobile-android-host:run"])
        self.assertEqual(10, parsed.runs)
        self.assertEqual("scenario-a-stage-run", parsed.label)
        self.assertEqual("both", parsed.framework)

    def test_parse_device_lane_args_rejects_unknown_flag(self) -> None:
        with self.assertRaises(DevctlError) as raised:
            parse_device_lane_args(["--unknown"], ["echo"])
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_validate_threshold_columns_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            csv_file = Path(tmp) / "stage2.csv"
            csv_file.write_text("scenario,first_token_ms\nA,100\n", encoding="utf-8")

            with self.assertRaises(DevctlError) as raised:
                validate_threshold_columns(csv_file, ["scenario", "first_token_ms", "decode_tps"])
            self.assertEqual("SCHEMA_ERROR", raised.exception.code)

    def test_ensure_serial_returns_device_error_when_preflight_fails(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        def fake_run(*_args, **_kwargs):
            class Result:
                returncode = 1
                stdout = ""
                stderr = "no devices"

            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        with self.assertRaises(DevctlError) as raised:
            _ensure_serial(context)
        self.assertEqual("DEVICE_ERROR", raised.exception.code)

    def test_evaluate_loop_output_rejects_invalid_regex(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            log = tmp_path / "run-1-logcat.txt"
            log.write_text("normal log", encoding="utf-8")
            summary = tmp_path / "summary.csv"
            summary.write_text(
                "run,exit_code,crash_detected,oom_detected,log_file,command_output\n"
                f"1,0,false,false,{log},run-1.log\n",
                encoding="utf-8",
            )
            with self.assertRaises(DevctlError) as raised:
                _evaluate_loop_output(summary, ["(bad"], ["OutOfMemoryError"])
            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_dispatch_lane_rejects_unknown_lane(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        with self.assertRaises(DevctlError) as raised:
            dispatch_lane("unknown-lane", [], context)
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_lane_maestro_requires_cli(self) -> None:
        from tools.devctl import lanes

        original_which = lanes.shutil.which
        try:
            lanes.shutil.which = lambda _name: None
            configs = load_devctl_configs(REPO_ROOT)
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
            with self.assertRaises(DevctlError) as raised:
                lane_maestro([], context)
            self.assertEqual("ENVIRONMENT_ERROR", raised.exception.code)
        finally:
            lanes.shutil.which = original_which

    def test_lane_maestro_passes_resolved_device_serial(self) -> None:
        from tools.devctl import lanes

        original_which = lanes.shutil.which
        issued_commands: list[list[str]] = []
        configs = load_devctl_configs(REPO_ROOT)
        ensure_command = configs.device.preflight.ensure_device_command

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            issued_commands.append(list(command))
            if list(command) == list(ensure_command):
                return Result(returncode=0, stdout="SER123\n")
            return Result(returncode=0, stdout="", stderr="")

        try:
            lanes.shutil.which = lambda name: "/usr/bin/maestro" if name == "maestro" else None
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lane_maestro([], context)
        finally:
            lanes.shutil.which = original_which

        maestro_calls = [cmd for cmd in issued_commands if cmd and cmd[0] == "/usr/bin/maestro"]
        self.assertGreaterEqual(len(maestro_calls), 1)
        for call in maestro_calls:
            self.assertEqual("--device", call[1])
            self.assertEqual("SER123", call[2])


if __name__ == "__main__":
    unittest.main()
