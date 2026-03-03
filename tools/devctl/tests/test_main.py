from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.devctl import main as devctl


class DevctlMainTest(unittest.TestCase):
    def test_load_config_returns_config_error_for_invalid_content(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "broken.yaml"
            path.write_text("{ this is not valid json or yaml", encoding="utf-8")

            with self.assertRaises(devctl.DevctlError) as raised:
                devctl.load_config(path)

            self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_dispatch_lane_rejects_unknown_lane(self) -> None:
        context = devctl.RuntimeContext(
            repo_root=Path("."),
            lanes_cfg={"lanes": {}},
            device_cfg={},
            stage2_cfg={},
            env={},
            run=lambda *args, **kwargs: None,
        )

        with self.assertRaises(devctl.DevctlError) as raised:
            devctl.dispatch_lane("unknown-lane", [], context)

        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_ensure_serial_returns_device_error_when_preflight_fails(self) -> None:
        def fake_run(*_args, **_kwargs):
            class Result:
                returncode = 1
                stdout = ""
                stderr = "no devices"

            return Result()

        context = devctl.RuntimeContext(
            repo_root=Path("."),
            lanes_cfg={"lanes": {}},
            device_cfg={
                "preflight": {
                    "ensure_device_command": ["bash", "scripts/android/ensure_device.sh"],
                },
            },
            stage2_cfg={},
            env={},
            run=fake_run,
        )

        with self.assertRaises(devctl.DevctlError) as raised:
            devctl._ensure_serial(context)

        self.assertEqual("DEVICE_ERROR", raised.exception.code)

    def test_build_artifact_dir_is_deterministic(self) -> None:
        path = devctl.build_artifact_dir(
            "scripts/benchmarks/runs/{date}/{device}/{label}-{stamp}",
            "2026-03-03",
            "SER123",
            "scenario-a",
            "20260303-101010",
        )
        self.assertEqual(
            devctl.REPO_ROOT / "scripts/benchmarks/runs/2026-03-03/SER123/scenario-a-20260303-101010",
            path,
        )

    def test_build_gradle_test_command_quick_omits_clean(self) -> None:
        lane_cfg = {
            "gradle_binary": "./gradlew",
            "gradle_flags": ["--no-daemon"],
            "common_tasks": [":packages:core-domain:test"],
            "android_tasks": [":apps:mobile-android:testDebugUnitTest"],
        }
        command = devctl.build_gradle_test_command("quick", android_configured=False, lane_cfg=lane_cfg)
        self.assertEqual(["./gradlew", "--no-daemon", ":packages:core-domain:test"], command)

    def test_build_gradle_test_command_ci_includes_clean_and_android_tasks(self) -> None:
        lane_cfg = {
            "gradle_binary": "./gradlew",
            "gradle_flags": ["--no-daemon"],
            "common_tasks": [":packages:core-domain:test"],
            "android_tasks": [":apps:mobile-android:testDebugUnitTest"],
        }
        command = devctl.build_gradle_test_command("ci", android_configured=True, lane_cfg=lane_cfg)
        self.assertEqual(
            [
                "./gradlew",
                "--no-daemon",
                "clean",
                ":packages:core-domain:test",
                ":apps:mobile-android:testDebugUnitTest",
            ],
            command,
        )

    def test_validate_threshold_columns_returns_schema_error_for_missing_columns(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            csv_file = Path(tmp_dir) / "stage2.csv"
            csv_file.write_text("scenario,first_token_ms\nA,100\n", encoding="utf-8")

            with self.assertRaises(devctl.DevctlError) as raised:
                devctl.validate_threshold_columns(csv_file, ["scenario", "first_token_ms", "decode_tps"])

            self.assertEqual("SCHEMA_ERROR", raised.exception.code)

    def test_parse_device_lane_args_defaults(self) -> None:
        parsed = devctl.parse_device_lane_args([], ["./gradlew", ":apps:mobile-android-host:run"])
        self.assertEqual(10, parsed.runs)
        self.assertEqual("scenario-a-stage-run", parsed.label)
        self.assertEqual("both", parsed.framework)
        self.assertFalse(parsed.framework_explicit)
        self.assertEqual(["./gradlew", ":apps:mobile-android-host:run"], parsed.scenario_command)

    def test_parse_device_lane_args_with_framework_and_custom_command(self) -> None:
        parsed = devctl.parse_device_lane_args(
            ["5", "smoke", "--framework", "espresso", "--", "echo", "ok"],
            ["./gradlew", ":apps:mobile-android-host:run"],
        )
        self.assertEqual(5, parsed.runs)
        self.assertEqual("smoke", parsed.label)
        self.assertEqual("espresso", parsed.framework)
        self.assertTrue(parsed.framework_explicit)
        self.assertEqual(["echo", "ok"], parsed.scenario_command)

    def test_parse_device_lane_args_rejects_unknown_flag(self) -> None:
        with self.assertRaises(devctl.DevctlError) as raised:
            devctl.parse_device_lane_args(["--unknown"], ["echo"])
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_validate_required_device_props_returns_device_error(self) -> None:
        def fake_run(argv, **_kwargs):
            class Result:
                returncode = 1 if "ro.build.version.release" in argv else 0
                stdout = "" if "ro.build.version.release" in argv else "Pixel\n"
                stderr = "error" if "ro.build.version.release" in argv else ""

            return Result()

        context = devctl.RuntimeContext(
            repo_root=Path("."),
            lanes_cfg={"lanes": {}},
            device_cfg={"preflight": {"required_props": ["ro.product.model", "ro.build.version.release"]}},
            stage2_cfg={},
            env={},
            run=fake_run,
        )
        with self.assertRaises(devctl.DevctlError) as raised:
            devctl._validate_required_device_props(context, "SER123")
        self.assertEqual("DEVICE_ERROR", raised.exception.code)

    def test_evaluate_loop_output_detects_crash_or_oom(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            log = tmp / "run-1-logcat.txt"
            log.write_text("FATAL EXCEPTION: main", encoding="utf-8")
            summary = tmp / "summary.csv"
            summary.write_text(
                "run,exit_code,crash_detected,oom_detected,log_file,command_output\n"
                f"1,0,false,false,{log},run-1.log\n",
                encoding="utf-8",
            )
            with self.assertRaises(devctl.DevctlError) as raised:
                devctl._evaluate_loop_output(summary, ["FATAL EXCEPTION"], ["OutOfMemoryError"])
            self.assertEqual("DEVICE_ERROR", raised.exception.code)

    def test_extract_summary_path_returns_none_when_missing(self) -> None:
        self.assertIsNone(devctl._extract_summary_path("no summary line here"))

    def test_evaluate_loop_output_rejects_invalid_regex(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            log = tmp / "run-1-logcat.txt"
            log.write_text("normal log", encoding="utf-8")
            summary = tmp / "summary.csv"
            summary.write_text(
                "run,exit_code,crash_detected,oom_detected,log_file,command_output\n"
                f"1,0,false,false,{log},run-1.log\n",
                encoding="utf-8",
            )
            with self.assertRaises(devctl.DevctlError) as raised:
                devctl._evaluate_loop_output(summary, ["(bad"], ["OutOfMemoryError"])
            self.assertEqual("CONFIG_ERROR", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
