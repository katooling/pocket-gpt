from __future__ import annotations

import subprocess
import tempfile
import time
import unittest
from pathlib import Path

from tools.devctl.config_models import load_devctl_configs
from tools.devctl.lanes import (
    RuntimeContext,
    _normalize_test_mode,
    _parse_journey_args,
    _parse_stage2_args,
    _select_gradle_tasks_for_changed_files,
    _ensure_serial,
    _evaluate_loop_output,
    build_artifact_dir,
    dispatch_lane,
    lane_stage2,
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

    def test_test_profile_aliases_resolve(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        self.assertEqual("core", _normalize_test_mode("quick", context))
        self.assertEqual("merge", _normalize_test_mode("ci", context))

    def test_changed_file_selection_maps_tasks_and_lanes(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        tasks, lanes, include_android = _select_gradle_tasks_for_changed_files(
            [
                "packages/native-bridge/src/commonMain/kotlin/com/pocketagent/nativebridge/NativeJniLlamaCppBridge.kt",
                "apps/mobile-android/src/main/kotlin/com/pocketagent/android/ui/ChatScreen.kt",
            ],
            context,
        )
        self.assertIn(":packages:native-bridge:test", tasks)
        self.assertIn(":apps:mobile-android:testDebugUnitTest", tasks)
        self.assertIn("android-instrumented", lanes)
        self.assertTrue(include_android)

    def test_stage2_parser_supports_profiles_and_resume(self) -> None:
        parsed = _parse_stage2_args(
            [
                "--device",
                "SER123",
                "--profile",
                "quick",
                "--models",
                "0.8b",
                "--scenarios",
                "a",
                "--resume",
                "--install-mode",
                "auto",
                "--logcat",
                "filtered",
            ]
        )
        self.assertEqual("SER123", parsed.device)
        self.assertEqual("quick", parsed.profile)
        self.assertEqual("0.8b", parsed.models)
        self.assertEqual("a", parsed.scenarios)
        self.assertTrue(parsed.resume)
        self.assertEqual("auto", parsed.install_mode)
        self.assertEqual("filtered", parsed.logcat)

    def test_journey_parser_enforces_repeat_bounds(self) -> None:
        parsed = _parse_journey_args(["--repeats", "2"], repeats_default=1, repeats_max=5)
        self.assertEqual(2, parsed.repeats)

        with self.assertRaises(DevctlError):
            _parse_journey_args(["--repeats", "0"], repeats_default=1, repeats_max=5)
        with self.assertRaises(DevctlError):
            _parse_journey_args(["--repeats", "8"], repeats_default=1, repeats_max=5)

    def test_stage2_closure_requires_models_both(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        with self.assertRaises(DevctlError) as raised:
            lane_stage2(
                [
                    "--device",
                    "SER123",
                    "--profile",
                    "closure",
                    "--models",
                    "0.8b",
                    "--scenarios",
                    "both",
                ],
                context,
            )
        self.assertEqual("CONFIG_ERROR", raised.exception.code)

    def test_stage2_closure_requires_scenarios_both(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=lambda *_a, **_k: None)
        with self.assertRaises(DevctlError) as raised:
            lane_stage2(
                [
                    "--device",
                    "SER123",
                    "--profile",
                    "closure",
                    "--models",
                    "both",
                    "--scenarios",
                    "a",
                ],
                context,
            )
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
        original_prepare = lanes.prepare_real_runtime_env
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

        def fake_prepare(_context, device_serial: str, artifact_root=None):
            return lanes.RealRuntimePreparedEnv(
                serial=device_serial,
                model_device_paths_by_id={
                    "qwen3.5-0.8b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                    "qwen3.5-2b-q4": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-2b-q4.gguf",
                },
                model_host_paths_by_id={
                    "qwen3.5-0.8b-q4": "/tmp/qwen3.5-0.8b-q4.gguf",
                    "qwen3.5-2b-q4": "/tmp/qwen3.5-2b-q4.gguf",
                },
            )

        try:
            lanes.shutil.which = lambda name: "/usr/bin/maestro" if name == "maestro" else None
            lanes.prepare_real_runtime_env = fake_prepare
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lane_maestro([], context)
        finally:
            lanes.shutil.which = original_which
            lanes.prepare_real_runtime_env = original_prepare

        maestro_calls = [cmd for cmd in issued_commands if cmd and cmd[0] == "/usr/bin/maestro"]
        self.assertGreaterEqual(len(maestro_calls), 1)
        for call in maestro_calls:
            self.assertEqual("--device", call[1])
            self.assertEqual("SER123", call[2])

    def test_device_lock_is_reentrant_for_same_process(self) -> None:
        from tools.devctl import lanes

        lock_path = lanes._device_lock_path("SER-LOCK-REENTRANT")
        try:
            with lanes._device_lock("SER-LOCK-REENTRANT", owner="test:outer", timeout_seconds=1):
                with lanes._device_lock("SER-LOCK-REENTRANT", owner="test:inner", timeout_seconds=1):
                    self.assertTrue(True)
        finally:
            lock_path.unlink(missing_ok=True)
            if lock_path.parent.exists() and not any(lock_path.parent.iterdir()):
                lock_path.parent.rmdir()

    def test_device_lock_times_out_when_held_by_another_process(self) -> None:
        from tools.devctl import lanes

        if lanes.fcntl is None:
            self.skipTest("fcntl is unavailable on this platform")

        lock_path = lanes._device_lock_path("SER-LOCK-TIMEOUT")
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        holder_script = (
            "import fcntl, pathlib, time\n"
            f"p = pathlib.Path({str(lock_path)!r})\n"
            "p.parent.mkdir(parents=True, exist_ok=True)\n"
            "f = p.open('a+')\n"
            "fcntl.flock(f.fileno(), fcntl.LOCK_EX)\n"
            "time.sleep(8)\n"
        )
        holder = subprocess.Popen(["python3", "-c", holder_script])
        try:
            time.sleep(0.25)
            with self.assertRaises(DevctlError) as raised:
                with lanes._device_lock("SER-LOCK-TIMEOUT", owner="test:timeout", timeout_seconds=1):
                    self.fail("Expected timeout while waiting for held device lock")
            self.assertEqual("DEVICE_ERROR", raised.exception.code)
        finally:
            holder.terminate()
            holder.wait(timeout=5)
            lock_path.unlink(missing_ok=True)
            if lock_path.parent.exists() and not any(lock_path.parent.iterdir()):
                lock_path.parent.rmdir()


if __name__ == "__main__":
    unittest.main()
