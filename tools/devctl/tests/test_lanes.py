from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from tools.devctl.config_models import load_devctl_configs
from tools.devctl.lanes import (
    ScreenshotInventoryItem,
    RuntimeContext,
    SendCaptureSnapshot,
    JourneyStepResult,
    _build_screenshot_inventory_report,
    _ensure_remote_dir,
    _extract_first_session_progress,
    _extract_instrumentation_failure,
    _extract_ui_runtime_fields,
    _media_path_fallbacks,
    _model_sync_cache_dir,
    _normalize_test_mode,
    _parse_model_sync_manifest,
    _parse_journey_args,
    _parse_screenshot_pack_args,
    _promote_screenshot_reference_set,
    _parse_package_uid,
    _remote_file_sha256,
    _remote_file_size_bytes,
    _remote_read_text_file,
    _resolve_available_instrumentation_runner,
    _run_send_capture_stage,
    _parse_stage2_args,
    _run_device_health_preflight,
    _select_gradle_tasks_for_changed_files,
    _ensure_serial,
    _evaluate_loop_output,
    _write_journey_report,
    build_artifact_dir,
    dispatch_lane,
    lane_stage2,
    lane_maestro,
    parse_device_lane_args,
    validate_threshold_columns,
)
from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT


def _extract_shell_script(cmd: list[str]) -> str | None:
    if cmd[:4] != ["adb", "-s", "SER123", "shell"]:
        return None
    if len(cmd) >= 7 and cmd[4:6] == ["sh", "-c"]:
        return cmd[6]
    if len(cmd) >= 5:
        return cmd[4]
    return None


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
        parsed = _parse_journey_args(
            ["--repeats", "2"],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual(2, parsed.repeats)
        self.assertEqual(90, parsed.reply_timeout_seconds)
        self.assertEqual([0, 5, 15, 30, 60, 90], parsed.capture_intervals)
        self.assertEqual("ola, how you doin", parsed.prompt)
        self.assertEqual("strict", parsed.mode)
        self.assertEqual(["instrumentation", "send-capture", "maestro"], parsed.steps)

        with self.assertRaises(DevctlError):
            _parse_journey_args(
                ["--repeats", "0"],
                repeats_default=1,
                repeats_max=5,
                reply_timeout_default=90,
                capture_intervals_default=[5, 15, 30, 60, 90],
                prompt_default="ola, how you doin",
            )
        with self.assertRaises(DevctlError):
            _parse_journey_args(
                ["--repeats", "8"],
                repeats_default=1,
                repeats_max=5,
                reply_timeout_default=90,
                capture_intervals_default=[5, 15, 30, 60, 90],
                prompt_default="ola, how you doin",
            )

    def test_journey_parser_applies_timeout_and_capture_overrides(self) -> None:
        parsed = _parse_journey_args(
            [
                "--reply-timeout-seconds",
                "45",
                "--capture-intervals",
                "3,9,15,60",
                "--prompt",
                "probe prompt",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual(45, parsed.reply_timeout_seconds)
        self.assertEqual([0, 3, 9, 15, 45], parsed.capture_intervals)
        self.assertEqual("probe prompt", parsed.prompt)

    def test_journey_parser_valid_output_mode_applies_long_timeout_defaults(self) -> None:
        parsed = _parse_journey_args(
            [
                "--mode",
                "valid-output",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual("valid-output", parsed.mode)
        self.assertEqual(480, parsed.reply_timeout_seconds)
        self.assertEqual([0, 5, 15, 30, 60, 90, 120, 180, 240, 300, 420, 480], parsed.capture_intervals)

    def test_journey_parser_fast_smoke_mode_applies_short_defaults(self) -> None:
        parsed = _parse_journey_args(
            [
                "--mode",
                "fast-smoke",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual("fast-smoke", parsed.mode)
        self.assertEqual(60, parsed.reply_timeout_seconds)
        self.assertEqual([0, 5, 15, 30, 60], parsed.capture_intervals)

    def test_screenshot_pack_parser_supports_update_reference_flag(self) -> None:
        parsed = _parse_screenshot_pack_args(["--update-reference"])
        self.assertTrue(parsed.update_reference)

    def test_build_screenshot_inventory_report_marks_missing_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact_root = root / "artifacts"
            instrumented_dir = artifact_root / "instrumented"
            maestro_dir = artifact_root / "maestro"
            combined_dir = artifact_root / "combined"
            report_json_path = artifact_root / "inventory-report.json"
            report_md_path = artifact_root / "inventory-report.md"

            instrumented_dir.mkdir(parents=True, exist_ok=True)
            maestro_dir.mkdir(parents=True, exist_ok=True)
            (instrumented_dir / "ui-01-onboarding-page-1.png").write_bytes(b"\x89PNG\r\n\x1a\n")

            inventory = [
                ScreenshotInventoryItem(
                    id="ui-01-onboarding-page-1",
                    filename="ui-01-onboarding-page-1.png",
                    candidates=("instrumented/ui-01-onboarding-page-1.png",),
                ),
                ScreenshotInventoryItem(
                    id="ui-02-onboarding-page-2",
                    filename="ui-02-onboarding-page-2.png",
                    candidates=("instrumented/ui-02-onboarding-page-2.png",),
                ),
            ]

            payload = _build_screenshot_inventory_report(
                inventory=inventory,
                serial="SER123",
                artifact_root=artifact_root,
                instrumented_dir=instrumented_dir,
                maestro_dir=maestro_dir,
                combined_dir=combined_dir,
                report_json_path=report_json_path,
                report_md_path=report_md_path,
            )

            self.assertEqual(["ui-02-onboarding-page-2"], payload["missing_ids"])
            self.assertTrue((combined_dir / "ui-01-onboarding-page-1.png").exists())
            self.assertFalse((combined_dir / "ui-02-onboarding-page-2.png").exists())
            self.assertTrue(report_json_path.exists())
            self.assertTrue(report_md_path.exists())

    def test_promote_screenshot_reference_set_writes_gallery_index(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            combined_dir = root / "combined"
            reference_dir = root / "reference"
            combined_dir.mkdir(parents=True, exist_ok=True)
            (combined_dir / "ui-01-onboarding-page-1.png").write_bytes(b"\x89PNG\r\n\x1a\n")
            (combined_dir / "ui-02-onboarding-page-2.png").write_bytes(b"\x89PNG\r\n\x1a\n")
            inventory = [
                ScreenshotInventoryItem(
                    id="ui-01-onboarding-page-1",
                    filename="ui-01-onboarding-page-1.png",
                    candidates=("instrumented/ui-01-onboarding-page-1.png",),
                ),
                ScreenshotInventoryItem(
                    id="ui-02-onboarding-page-2",
                    filename="ui-02-onboarding-page-2.png",
                    candidates=("instrumented/ui-02-onboarding-page-2.png",),
                ),
            ]

            _promote_screenshot_reference_set(
                combined_dir=combined_dir,
                inventory=inventory,
                reference_dir=reference_dir,
            )

            self.assertTrue((reference_dir / "ui-01-onboarding-page-1.png").exists())
            self.assertTrue((reference_dir / "ui-02-onboarding-page-2.png").exists())
            index_text = (reference_dir / "index.md").read_text(encoding="utf-8")
            self.assertIn("ui-01-onboarding-page-1", index_text)
            self.assertIn("ui-02-onboarding-page-2", index_text)

    def test_send_capture_valid_output_requires_terminal_event(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(returncode=0, stdout="ok\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Loading",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=True,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-1",
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="valid-output",
            )

        self.assertEqual("failed", result.status)
        self.assertEqual("first_token", result.phase)
        self.assertEqual("no_terminal_event", result.failure_signature)

    def test_send_capture_fast_smoke_allows_first_token_without_terminal(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd and cmd[0] == "maestro":
                return Result(returncode=0, stdout="ok\n", stderr="")
            return Result(returncode=0, stdout="", stderr="")

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        snapshot = SendCaptureSnapshot(
            second=0,
            screenshot=None,
            window_dump=None,
            chat_state_snapshot=None,
            runtime_status="Loading",
            backend="NATIVE_JNI",
            active_model_id="QWEN_0_8B",
            placeholder_visible=False,
            runtime_error_visible=False,
            timeout_message_visible=False,
            streaming_text_visible=True,
            response_visible=True,
            response_role="assistant",
            response_non_empty=True,
            first_token_seen=True,
            request_id="req-2",
            finish_reason=None,
            terminal_event_seen=False,
        )

        with tempfile.TemporaryDirectory() as tmpdir, mock.patch(
            "tools.devctl.lanes._capture_send_snapshot", return_value=snapshot
        ), mock.patch(
            "tools.devctl.lanes._capture_logcat", return_value=None
        ), mock.patch(
            "tools.devctl.lanes._collect_maestro_screenshots", return_value=[]
        ):
            result = _run_send_capture_stage(
                context=context,
                maestro_bin="maestro",
                serial="SER123",
                app_package="com.pocketagent.android",
                run_root=Path(tmpdir),
                prompt="hello",
                reply_timeout_seconds=60,
                capture_intervals=[0],
                mode="fast-smoke",
            )

        self.assertEqual("passed", result.status)
        self.assertEqual("first_token", result.phase)
        self.assertIsNone(result.failure_signature)

    def test_extract_ui_runtime_fields_detects_assistant_text(self) -> None:
        dump = (
            '<hierarchy>'
            '<node text="Runtime: Loading" />'
            '<node text="Backend: NATIVE_JNI" />'
            '<node text="Model: QWEN_0_8B" />'
            '<node text="ola, how you doin" />'
            '<node text="Hey! How can I help?" />'
            "</hierarchy>"
        )
        (
            runtime_status,
            backend,
            active_model_id,
            placeholder_visible,
            runtime_error_visible,
            timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(dump, prompt="ola, how you doin")
        self.assertEqual("Loading", runtime_status)
        self.assertEqual("NATIVE_JNI", backend)
        self.assertEqual("QWEN_0_8B", active_model_id)
        self.assertFalse(placeholder_visible)
        self.assertFalse(runtime_error_visible)
        self.assertFalse(timeout_message_visible)
        self.assertTrue(ui_response_visible)

    def test_extract_ui_runtime_fields_ignores_scaffold_text(self) -> None:
        dump = (
            '<hierarchy>'
            '<node text="Runtime: Loading" />'
            '<node text="Backend: NATIVE_JNI" />'
            '<node text="Model: QWEN_0_8B" />'
            '<node text="Pocket GPT" />'
            '<node text="Offline-first" />'
            '<node text="Message" />'
            '<node text="Send" />'
            '<node text="ola, how you doin" />'
            "</hierarchy>"
        )
        (
            _runtime_status,
            _backend,
            _active_model_id,
            _placeholder_visible,
            _runtime_error_visible,
            _timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(dump, prompt="ola, how you doin")
        self.assertFalse(ui_response_visible)

    def test_extract_ui_runtime_fields_ignores_runtime_phase_and_placeholder(self) -> None:
        dump = (
            '<hierarchy>'
            '<node text="Runtime: Loading" />'
            '<node text="Backend: NATIVE_JNI" />'
            '<node text="Model: QWEN_0_8B" />'
            '<node text="Speed &amp; Battery: BALANCED" />'
            '<node text="Prefill..." />'
            '<node text="Still working on this device. Keep waiting or cancel." />'
            '<node text="Cancel" />'
            '<node text="..." />'
            '<node text="ola, how you doin" />'
            "</hierarchy>"
        )
        (
            _runtime_status,
            _backend,
            _active_model_id,
            placeholder_visible,
            _runtime_error_visible,
            _timeout_message_visible,
            ui_response_visible,
        ) = _extract_ui_runtime_fields(dump, prompt="ola, how you doin")
        self.assertTrue(placeholder_visible)
        self.assertFalse(ui_response_visible)

    def test_extract_first_session_progress_reads_state_from_snapshot(self) -> None:
        payload = json.dumps(
            {
                "firstSessionStage": "FOLLOW_UP_DONE",
                "advancedUnlocked": False,
                "firstAnswerCompleted": True,
                "followUpCompleted": True,
            }
        )
        snapshot = f'<map><string name="chat_state_v2">{payload}</string></map>'
        stage, advanced_unlocked, first_answer_completed, follow_up_completed = _extract_first_session_progress(snapshot)
        self.assertEqual("FOLLOW_UP_DONE", stage)
        self.assertFalse(advanced_unlocked)
        self.assertTrue(first_answer_completed)
        self.assertTrue(follow_up_completed)

    def test_journey_parser_supports_step_and_flow_filters(self) -> None:
        parsed = _parse_journey_args(
            [
                "--steps",
                "send-capture,maestro",
                "--maestro-flows",
                "tests/maestro/scenario-a.yaml,tests/maestro/scenario-c.yaml",
            ],
            repeats_default=1,
            repeats_max=5,
            reply_timeout_default=90,
            capture_intervals_default=[5, 15, 30, 60, 90],
            prompt_default="ola, how you doin",
        )
        self.assertEqual(["send-capture", "maestro"], parsed.steps)
        self.assertEqual(
            ["tests/maestro/scenario-a.yaml", "tests/maestro/scenario-c.yaml"],
            parsed.maestro_flows,
        )

    def test_extract_instrumentation_failure_detects_short_msg(self) -> None:
        output = "\n".join(
            [
                "INSTRUMENTATION_STATUS: class=com.pocketagent.android.RealRuntimeProvisioningInstrumentationTest",
                "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
                "INSTRUMENTATION_CODE: 0",
            ]
        )
        self.assertEqual("Process crashed.", _extract_instrumentation_failure(output))

    def test_extract_instrumentation_failure_detects_failed_marker(self) -> None:
        output = "INSTRUMENTATION_FAILED: Process crashed."
        self.assertEqual("Process crashed.", _extract_instrumentation_failure(output))

    def test_extract_instrumentation_failure_ignores_success_output(self) -> None:
        output = "\n".join(
            [
                "INSTRUMENTATION_STATUS: class=com.pocketagent.android.RealRuntimeJourneyInstrumentationTest",
                "INSTRUMENTATION_STATUS_CODE: -1",
                "INSTRUMENTATION_RESULT: stream=",
                "OK (1 test)",
            ]
        )
        self.assertIsNone(_extract_instrumentation_failure(output))

    def test_model_sync_cache_dir_maps_models_to_devctl_cache(self) -> None:
        self.assertEqual(
            "/sdcard/Android/media/com.pocketagent.android/devctl-cache",
            _model_sync_cache_dir("/sdcard/Android/media/com.pocketagent.android/models"),
        )

    def test_parse_model_sync_manifest_rejects_invalid_schema(self) -> None:
        with self.assertRaises(ValueError):
            _parse_model_sync_manifest('{"schema":"wrong","models":{}}')

    def test_remote_file_helpers_parse_probe_outputs_and_use_single_shell_script(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)
        issued_commands: list[list[str]] = []

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            issued_commands.append(cmd)
            script = _extract_shell_script(cmd)
            if script is None:
                return Result()
            if "wc -c" in script:
                return Result(stdout=" 17\n")
            if "sha256sum" in script:
                return Result(
                    stdout=(
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        "  /sdcard/model.gguf\n"
                    )
                )
            if "cat " in script:
                return Result(stdout="manifest-json")
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        size = _remote_file_size_bytes(context, "SER123", "/sdcard/model.gguf")
        sha = _remote_file_sha256(context, "SER123", "/sdcard/model.gguf")
        text = _remote_read_text_file(context, "SER123", "/sdcard/model-sync-v1.json")

        self.assertEqual(17, size)
        self.assertEqual(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            sha,
        )
        self.assertEqual("manifest-json", text)
        shell_calls = [cmd for cmd in issued_commands if cmd[:4] == ["adb", "-s", "SER123", "shell"]]
        self.assertTrue(shell_calls)
        self.assertTrue(all(not (len(cmd) >= 6 and cmd[4:6] == ["sh", "-c"]) for cmd in shell_calls))

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
        original_health_preflight = lanes._run_device_health_preflight
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
            lanes._run_device_health_preflight = lambda *_args, **_kwargs: None
            context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
            lane_maestro([], context)
        finally:
            lanes.shutil.which = original_which
            lanes.prepare_real_runtime_env = original_prepare
            lanes._run_device_health_preflight = original_health_preflight

        maestro_calls = [cmd for cmd in issued_commands if cmd and cmd[0] == "/usr/bin/maestro"]
        self.assertGreaterEqual(len(maestro_calls), 1)
        for call in maestro_calls:
            self.assertEqual("--device", call[1])
            self.assertEqual("SER123", call[2])

    def test_parse_package_uid(self) -> None:
        self.assertEqual(10635, _parse_package_uid("pkgFlags=[ HAS_CODE ]\nuserId=10635\ngids=[3003]"))
        self.assertIsNone(_parse_package_uid("pkgFlags=[ HAS_CODE ]\ngids=[3003]"))

    def test_media_path_fallbacks_maps_to_download_dir(self) -> None:
        self.assertEqual(
            ["/sdcard/Download/com.pocketagent.android/models"],
            _media_path_fallbacks("/sdcard/Android/media/com.pocketagent.android/models"),
        )
        self.assertEqual([], _media_path_fallbacks("/data/local/tmp/models"))

    def test_ensure_remote_dir_falls_back_when_media_path_busy(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["mkdir", "-p", "/sdcard/Android/media/com.pocketagent.android/models"]:
                return Result(returncode=1, stderr="mkdir: Device or resource busy")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["ls", "-ld", "/sdcard/Android/media/com.pocketagent.android/models"]:
                return Result(returncode=1, stderr="ls: cannot access")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["mkdir", "-p", "/sdcard/Download/com.pocketagent.android/models"]:
                return Result(returncode=0)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["ls", "-ld", "/sdcard/Download/com.pocketagent.android/models"]:
                return Result(returncode=0)
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        resolved = _ensure_remote_dir(
            context=context,
            serial="SER123",
            path="/sdcard/Android/media/com.pocketagent.android/models",
            fallback_paths=["/sdcard/Download/com.pocketagent.android/models"],
            failure_label="failed",
        )
        self.assertEqual("/sdcard/Download/com.pocketagent.android/models", resolved)

    def test_resolve_available_instrumentation_runner_prefers_target_match(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            if list(command) == ["adb", "-s", "SER123", "shell", "pm", "list", "instrumentation"]:
                return Result(
                    stdout=(
                        "instrumentation:com.other.app.test/androidx.test.runner.AndroidJUnitRunner "
                        "(target=com.other.app)\n"
                        "instrumentation:com.pocketagent.android.standard.test/"
                        "androidx.test.runner.AndroidJUnitRunner "
                        "(target=com.pocketagent.android.standard)\n"
                    )
                )
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        resolved = _resolve_available_instrumentation_runner(
            context=context,
            serial="SER123",
            preferred_runner="com.pocketagent.android.test/androidx.test.runner.AndroidJUnitRunner",
            app_package="com.pocketagent.android",
        )
        self.assertEqual(
            "com.pocketagent.android.standard.test/androidx.test.runner.AndroidJUnitRunner",
            resolved,
        )

    def test_prepare_real_runtime_env_skips_push_when_remote_size_matches(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3.5-2B-Q4_0.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3.5-2b-q4": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="3\n")
                        if "qwen3.5-2b-q4.gguf" in script:
                            return Result(stdout="5\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                prepared = lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual([], model_push_calls)
                self.assertEqual(
                    "com.pocketagent.android.standard.test/androidx.test.runner.AndroidJUnitRunner",
                    prepared.instrumentation_runner,
                )
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_reuses_loaded_manifest_cache_path(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3.5-2B-Q4_0.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                model_0_sha = hashlib.sha256(b"abc").hexdigest()
                model_2_sha = hashlib.sha256(b"12345").hexdigest()
                manifest_payload = json.dumps(
                    {
                        "schema": "model-sync-v1",
                        "selected_model_dir": "/sdcard/Android/media/com.pocketagent.android/models",
                        "models": {
                            "qwen3.5-0.8b-q4": {
                                "host_sha256": model_0_sha,
                                "host_size": 3,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                            },
                            "qwen3.5-2b-q4": {
                                "host_sha256": model_2_sha,
                                "host_size": 5,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-2b-q4.gguf",
                            },
                        },
                    }
                )

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3.5-2b-q4": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "model-sync-v1.json" in script and "cat " in script:
                            return Result(stdout=manifest_payload)
                        if "qwen3.5-0.8b-q4.gguf" in script and "wc -c" in script:
                            return Result(stdout="3\n")
                        if "qwen3.5-2b-q4.gguf" in script and "wc -c" in script:
                            return Result(stdout="5\n")
                        if "qwen3.5-0.8b-q4.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{model_0_sha}  /sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf\n")
                        if "qwen3.5-2b-q4.gguf" in script and "sha256sum" in script:
                            return Result(stdout=f"{model_2_sha}  /sdcard/Android/media/com.pocketagent.android/models/qwen3.5-2b-q4.gguf\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                artifact_root = tmp_path / "artifacts"
                lanes.prepare_real_runtime_env(context, "SER123", artifact_root=artifact_root)

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual([], model_push_calls)

                metadata = json.loads((artifact_root / "real-runtime-preflight.json").read_text(encoding="utf-8"))
                model_sync = metadata.get("model_sync", {})
                self.assertTrue(str(model_sync.get("loaded_manifest_path", "")).endswith("model-sync-v1.json"))
                decisions = model_sync.get("decisions", [])
                self.assertEqual(2, len(decisions))
                self.assertTrue(all(decision.get("decision") == "cache_hit" for decision in decisions))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_force_sync_pushes_models_even_when_sizes_match(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3.5-2B-Q4_0.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3.5-2b-q4": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="3\n")
                        if "qwen3.5-2b-q4.gguf" in script:
                            return Result(stdout="5\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(
                    repo_root=REPO_ROOT,
                    configs=configs,
                    env={"POCKETGPT_FORCE_MODEL_SYNC": "1"},
                    run=fake_run,
                )
                lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual(2, len(model_push_calls))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_self_heals_corrupt_sync_manifest(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3.5-2B-Q4_0.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")
                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3.5-2b-q4": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "model-sync-v1.json" in script and "cat" in script:
                            return Result(stdout="{corrupt-json")
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="3\n")
                        if "qwen3.5-2b-q4.gguf" in script:
                            return Result(stdout="5\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                manifest_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith("model-sync-v1.json")
                ]
                self.assertEqual([], model_push_calls)
                self.assertEqual(1, len(manifest_push_calls))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_prepare_real_runtime_env_pushes_when_manifest_path_is_stale(self) -> None:
        from tools.devctl import lanes

        original_resolve = lanes._resolve_real_runtime_model_paths
        original_run_instrumentation = lanes._run_instrumentation_class
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                model_0_8b = tmp_path / "Qwen3.5-0.8B-Q4_0.gguf"
                model_2b = tmp_path / "Qwen3.5-2B-Q4_0.gguf"
                model_0_8b.write_bytes(b"abc")
                model_2b.write_bytes(b"12345")

                model_0_sha = hashlib.sha256(b"abc").hexdigest()
                model_2_sha = hashlib.sha256(b"12345").hexdigest()
                stale_manifest = json.dumps(
                    {
                        "schema": "model-sync-v1",
                        "selected_model_dir": "/sdcard/Android/media/com.pocketagent.android/models",
                        "models": {
                            "qwen3.5-0.8b-q4": {
                                "host_sha256": model_0_sha,
                                "host_size": 3,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-0.8b-q4.gguf",
                            },
                            "qwen3.5-2b-q4": {
                                "host_sha256": model_2_sha,
                                "host_size": 5,
                                "device_path": "/sdcard/Android/media/com.pocketagent.android/models/qwen3.5-2b-q4.gguf",
                            },
                        },
                    }
                )

                lanes._resolve_real_runtime_model_paths = lambda _context: {
                    "qwen3.5-0.8b-q4": str(model_0_8b),
                    "qwen3.5-2b-q4": str(model_2b),
                }
                lanes._run_instrumentation_class = lambda **_kwargs: subprocess.CompletedProcess(
                    args=[],
                    returncode=0,
                    stdout="ok",
                    stderr="",
                )

                configs = load_devctl_configs(REPO_ROOT)
                issued_commands: list[list[str]] = []

                class Result:
                    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                        self.returncode = returncode
                        self.stdout = stdout
                        self.stderr = stderr

                def fake_run(command, **_kwargs):
                    cmd = list(command)
                    issued_commands.append(cmd)
                    if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:7] == ["pm", "list", "instrumentation"]:
                        return Result(
                            stdout=(
                                "instrumentation:com.pocketagent.android.standard.test/"
                                "androidx.test.runner.AndroidJUnitRunner "
                                "(target=com.pocketagent.android.standard)\n"
                            )
                        )
                    script = _extract_shell_script(cmd)
                    if script is not None:
                        if "model-sync-v1.json" in script and "cat" in script:
                            return Result(stdout=stale_manifest)
                        if "qwen3.5-0.8b-q4.gguf" in script:
                            return Result(stdout="0\n")
                        if "qwen3.5-2b-q4.gguf" in script:
                            return Result(stdout="0\n")
                        return Result(stdout="")
                    return Result()

                context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
                lanes.prepare_real_runtime_env(context, "SER123")

                model_push_calls = [
                    cmd
                    for cmd in issued_commands
                    if len(cmd) >= 4 and cmd[:4] == ["adb", "-s", "SER123", "push"] and cmd[-1].endswith(".gguf")
                ]
                self.assertEqual(2, len(model_push_calls))
        finally:
            lanes._resolve_real_runtime_model_paths = original_resolve
            lanes._run_instrumentation_class = original_run_instrumentation

    def test_run_device_health_preflight_happy_path(self) -> None:
        configs = load_devctl_configs(REPO_ROOT)

        class Result:
            def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
                self.returncode = returncode
                self.stdout = stdout
                self.stderr = stderr

        def fake_run(command, **_kwargs):
            cmd = list(command)
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:] == ["df", "/data"]:
                return Result(stdout="Filesystem 1K-blocks Used Available Use% Mounted on\n/data 100 40 60 40% /data\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:8] == ["pm", "list", "packages", "--user"]:
                package = cmd[-1]
                return Result(stdout=f"package:{package}\n")
            if cmd[:4] == ["adb", "-s", "SER123", "shell"] and cmd[4:6] == ["dumpsys", "package"]:
                return Result(stdout="Packages:\n  userId=10635\n")
            return Result()

        context = RuntimeContext(repo_root=REPO_ROOT, configs=configs, env={}, run=fake_run)
        _run_device_health_preflight(context, "SER123")

    def test_write_journey_report_includes_owner_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "journey-report.json"
            summary_path = Path(tmp) / "journey-summary.md"
            old_owner = os.environ.get("POCKETGPT_RUN_OWNER")
            old_host = os.environ.get("HOSTNAME")
            try:
                os.environ["POCKETGPT_RUN_OWNER"] = "qa-owner"
                os.environ["HOSTNAME"] = "qa-host"
                _write_journey_report(
                    report_path=report_path,
                    summary_path=summary_path,
                    serial="SER123",
                    steps=[
                        JourneyStepResult(
                            name="run-01:instrumentation",
                            status="passed",
                            duration_seconds=1.23,
                        ),
                    ],
                )
            finally:
                if old_owner is None:
                    os.environ.pop("POCKETGPT_RUN_OWNER", None)
                else:
                    os.environ["POCKETGPT_RUN_OWNER"] = old_owner
                if old_host is None:
                    os.environ.pop("HOSTNAME", None)
                else:
                    os.environ["HOSTNAME"] = old_host

            payload = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual("qa-owner", payload["run_owner"])
            self.assertEqual("qa-host", payload["run_host"])
            summary = summary_path.read_text(encoding="utf-8")
            self.assertIn("Run owner: `qa-owner`", summary)
            self.assertIn("Run host: `qa-host`", summary)

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
