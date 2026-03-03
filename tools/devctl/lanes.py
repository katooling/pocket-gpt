from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Mapping, MutableMapping, Sequence

from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT, format_command, print_step, run_subprocess

if TYPE_CHECKING:
    from tools.devctl.config_models import DevctlConfigs
else:
    DevctlConfigs = Any


@dataclass
class RuntimeContext:
    repo_root: Path
    configs: DevctlConfigs
    env: MutableMapping[str, str]
    run: Callable[..., subprocess.CompletedProcess[str]]


@dataclass
class DeviceLaneArgs:
    runs: int
    label: str
    framework: str
    framework_explicit: bool
    scenario_command: list[str]


def build_artifact_dir(template: str, date: str, device: str, label: str, stamp: str) -> Path:
    return REPO_ROOT / template.format(date=date, device=device, label=label, stamp=stamp)


def build_gradle_test_command(mode: str, android_configured: bool, lane_cfg: object) -> list[str]:
    if mode not in {"full", "quick", "ci"}:
        raise DevctlError("CONFIG_ERROR", f"Unsupported test mode: {mode}")

    gradle_binary = getattr(lane_cfg, "gradle_binary")
    gradle_flags = list(getattr(lane_cfg, "gradle_flags"))
    common_tasks = list(getattr(lane_cfg, "common_tasks"))
    android_tasks = list(getattr(lane_cfg, "android_tasks"))

    command = [gradle_binary, *gradle_flags]
    if mode in {"full", "ci"}:
        command.append("clean")
    command.extend(common_tasks)
    if android_configured:
        command.extend(android_tasks)
    return command


def validate_threshold_columns(csv_path: Path, required_columns: Sequence[str]) -> None:
    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader, None)

    if header is None:
        raise DevctlError("SCHEMA_ERROR", f"CSV is empty: {csv_path}")

    missing = [column for column in required_columns if column not in header]
    if missing:
        raise DevctlError(
            "SCHEMA_ERROR",
            f"Missing threshold column(s) in {csv_path}: {', '.join(missing)}",
        )


def _resolve_android_env(env: MutableMapping[str, str]) -> tuple[bool, MutableMapping[str, str]]:
    resolved = dict(env)
    android_home = resolved.get("ANDROID_HOME")
    if not android_home and resolved.get("ANDROID_SDK_ROOT"):
        android_home = resolved["ANDROID_SDK_ROOT"]
        resolved["ANDROID_HOME"] = android_home

    configured = bool(android_home and Path(android_home).is_dir())
    return configured, resolved


def _ensure_serial(context: RuntimeContext) -> str:
    ensure_command = context.configs.device.preflight.ensure_device_command
    result = context.run(ensure_command, check=False, capture_output=True, env=context.env)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        raise DevctlError(
            "DEVICE_ERROR",
            f"Device preflight failed. Command: {format_command(ensure_command)}\n{stderr}",
        )

    serial_lines = (result.stdout or "").strip().splitlines()
    if not serial_lines:
        raise DevctlError("DEVICE_ERROR", "Device preflight returned empty serial output.")
    return serial_lines[-1].strip()


def _validate_required_device_props(context: RuntimeContext, serial: str) -> None:
    missing: list[str] = []
    for prop in context.configs.device.preflight.required_props:
        result = context.run(
            ["adb", "-s", serial, "shell", "getprop", prop],
            check=False,
            capture_output=True,
            env=context.env,
        )
        if result.returncode != 0 or not (result.stdout or "").strip():
            missing.append(prop)

    if missing:
        raise DevctlError("DEVICE_ERROR", f"Required device properties are unavailable: {', '.join(missing)}")


def _extract_summary_path(output: str) -> Path | None:
    matches = re.findall(r"Run summary written to ([^\n]+summary\.csv)", output)
    if not matches:
        return None
    summary_path = Path(matches[-1].strip())
    if not summary_path.is_absolute():
        summary_path = REPO_ROOT / summary_path
    return summary_path


def _read_loop_summary_rows(summary_path: Path) -> list[dict[str, str]]:
    if not summary_path.exists():
        raise DevctlError("DEVICE_ERROR", f"Loop summary file missing: {summary_path}")

    with summary_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        rows = [dict(row) for row in reader]

    if not rows:
        raise DevctlError("DEVICE_ERROR", f"Loop summary is empty: {summary_path}")
    return rows


def _scan_log_file(path: Path, patterns: Sequence[str]) -> bool:
    if not path.exists():
        return False

    text = path.read_text(encoding="utf-8", errors="replace")
    for pattern in patterns:
        try:
            if re.search(pattern, text):
                return True
        except re.error as exc:
            raise DevctlError("CONFIG_ERROR", f"Invalid regex pattern '{pattern}': {exc}") from exc
    return False


def _evaluate_loop_output(summary_path: Path, crash_patterns: Sequence[str], oom_patterns: Sequence[str]) -> None:
    rows = _read_loop_summary_rows(summary_path)
    failing_runs: list[str] = []

    for row in rows:
        run_id = row.get("run", "?")
        crash = (row.get("crash_detected") or "").strip().lower() == "true"
        oom = (row.get("oom_detected") or "").strip().lower() == "true"

        log_file = (row.get("log_file") or "").strip()
        if log_file:
            log_path = Path(log_file)
            if not log_path.is_absolute():
                log_path = REPO_ROOT / log_path
            crash = crash or _scan_log_file(log_path, crash_patterns)
            oom = oom or _scan_log_file(log_path, oom_patterns)

        if crash or oom:
            labels: list[str] = []
            if crash:
                labels.append("crash")
            if oom:
                labels.append("oom")
            failing_runs.append(f"{run_id} ({'+'.join(labels)})")

    if failing_runs:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Crash/OOM signals detected in short-loop results: {', '.join(failing_runs)}",
        )


def parse_device_lane_args(raw_args: Sequence[str], default_scenario_command: Sequence[str]) -> DeviceLaneArgs:
    runs: int | None = None
    label: str | None = None
    framework = "both"
    framework_explicit = False
    scenario_command: list[str] = []

    args = list(raw_args)
    idx = 0
    while idx < len(args):
        token = args[idx]
        if token == "--":
            scenario_command = args[idx + 1 :]
            break

        if token == "--framework":
            if idx + 1 >= len(args):
                raise DevctlError("CONFIG_ERROR", "Missing value for --framework")
            framework = args[idx + 1].strip().lower()
            framework_explicit = True
            idx += 2
            continue

        if token.startswith("--framework="):
            framework = token.split("=", 1)[1].strip().lower()
            framework_explicit = True
            idx += 1
            continue

        if token.startswith("--"):
            raise DevctlError("CONFIG_ERROR", f"Unknown device lane flag: {token}")

        if runs is None:
            try:
                runs = int(token)
            except ValueError as exc:
                raise DevctlError("CONFIG_ERROR", f"Invalid runs value: {token}") from exc
            idx += 1
            continue

        if label is None:
            label = token
            idx += 1
            continue

        raise DevctlError("CONFIG_ERROR", f"Unexpected argument before '--': {token}")

    if runs is None:
        runs = 10
    if runs <= 0:
        raise DevctlError("CONFIG_ERROR", "runs must be > 0")

    if label is None:
        label = "scenario-a-stage-run"

    if not scenario_command:
        scenario_command = list(default_scenario_command)

    if framework not in {"espresso", "maestro", "both"}:
        raise DevctlError("CONFIG_ERROR", "framework must be one of: espresso|maestro|both")

    return DeviceLaneArgs(
        runs=runs,
        label=label,
        framework=framework,
        framework_explicit=framework_explicit,
        scenario_command=scenario_command,
    )


def lane_test(raw_args: Sequence[str], context: RuntimeContext) -> None:
    mode = raw_args[0] if raw_args else "full"
    if len(raw_args) > 1:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane test [full|quick|ci]")

    lane_cfg = context.configs.lanes.lanes.test

    for step in lane_cfg.commands:
        if step.name == "python_unit_tests":
            context.run(step.argv, check=True, env=context.env)

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        print_step("Android SDK not configured; running host/JVM test lane only.")

    gradle_command = build_gradle_test_command(mode, android_configured, lane_cfg)
    context.run(gradle_command, check=True, env=resolved_env)


def lane_android_instrumented(raw_args: Sequence[str], context: RuntimeContext, strict: bool = True) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane android-instrumented")

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        message = "Android SDK not configured for instrumentation lane."
        if strict:
            raise DevctlError("ENVIRONMENT_ERROR", message)
        print_step(f"Skipping instrumentation lane: {message}")
        return

    for step in context.configs.lanes.lanes.android_instrumented.commands:
        context.run(step.argv, check=True, env=resolved_env)


def lane_maestro(raw_args: Sequence[str], context: RuntimeContext, strict: bool = True) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane maestro")

    maestro_bin = shutil.which("maestro")
    if maestro_bin is None:
        message = "Maestro CLI is not installed. Install with: curl -Ls https://get.maestro.mobile.dev | bash"
        if strict:
            raise DevctlError("ENVIRONMENT_ERROR", message)
        print_step(f"Skipping Maestro lane: {message}")
        return

    serial = _ensure_serial(context)
    lane_cfg = context.configs.lanes.lanes.maestro

    for command in lane_cfg.preflight_commands:
        context.run(command, check=True, env=context.env)

    for flow in lane_cfg.flows:
        flow_path = REPO_ROOT / flow
        if not flow_path.exists():
            raise DevctlError("CONFIG_ERROR", f"Missing Maestro flow: {flow_path}")
        context.run([maestro_bin, "--device", serial, "test", str(flow_path)], check=True, env=context.env)


def lane_device(raw_args: Sequence[str], context: RuntimeContext) -> None:
    lane_cfg = context.configs.lanes.lanes.device

    parsed = parse_device_lane_args(raw_args, lane_cfg.default_scenario_command)
    serial = _ensure_serial(context)
    _validate_required_device_props(context, serial)

    date_value = datetime.now().strftime("%Y-%m-%d")
    stamp_value = datetime.now().strftime("%Y%m%d-%H%M%S")
    resolved_dir = build_artifact_dir(lane_cfg.artifacts.output_dir_template, date_value, serial, parsed.label, stamp_value)
    print_step(f"Resolved artifact output directory: {resolved_dir}")

    command_map = {step.name: step.argv for step in lane_cfg.commands}
    required = {
        "stage_checks",
        "capture_baseline",
        "apply_benchmark_settings",
        "reset_benchmark_settings",
        "run_short_loop",
    }
    missing = sorted(required - set(command_map))
    if missing:
        raise DevctlError("CONFIG_ERROR", f"lanes.device.commands missing required steps: {', '.join(missing)}")

    context.run(command_map["stage_checks"], check=True, env=context.env)
    context.run(command_map["capture_baseline"], check=True, env=context.env)
    context.run(command_map["apply_benchmark_settings"], check=True, env=context.env)

    try:
        loop_command = [
            *command_map["run_short_loop"],
            "--runs",
            str(parsed.runs),
            "--label",
            parsed.label,
            "--",
            *parsed.scenario_command,
        ]
        loop_result = context.run(loop_command, check=True, capture_output=True, env=context.env)
        if loop_result.stdout:
            print(loop_result.stdout, end="")
        if loop_result.stderr:
            print(loop_result.stderr, end="", file=sys.stderr)

        summary_path = _extract_summary_path(loop_result.stdout or "")
        if summary_path is not None:
            _evaluate_loop_output(
                summary_path,
                context.configs.device.crash_signatures,
                context.configs.device.oom_signatures,
            )
        else:
            print_step("Short-loop summary path not found in output; skipping crash/OOM post-scan.")

        if parsed.framework in {"espresso", "both"}:
            try:
                lane_android_instrumented([], context, strict=parsed.framework_explicit)
            except DevctlError as exc:
                if not parsed.framework_explicit and exc.code == "ENVIRONMENT_ERROR":
                    print_step(f"Skipping espresso lane (default framework mode): {exc.message}")
                else:
                    raise

        if parsed.framework in {"maestro", "both"}:
            try:
                lane_maestro([], context, strict=parsed.framework_explicit)
            except DevctlError as exc:
                if not parsed.framework_explicit and exc.code == "ENVIRONMENT_ERROR":
                    print_step(f"Skipping Maestro lane (default framework mode): {exc.message}")
                else:
                    raise
    finally:
        context.run(command_map["reset_benchmark_settings"], check=False, env=context.env)


def _parse_stage2_args(raw_args: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="devctl lane stage2")
    parser.add_argument("--device", required=True)
    parser.add_argument("--date", default=datetime.now().strftime("%Y-%m-%d"))
    parser.add_argument("--scenario-a")
    parser.add_argument("--scenario-b")
    return parser.parse_args(list(raw_args))


def lane_stage2(raw_args: Sequence[str], context: RuntimeContext) -> None:
    args = _parse_stage2_args(raw_args)
    lane_cfg = context.configs.lanes.lanes.stage2
    stage2_cfg = context.configs.stage2

    run_dir = REPO_ROOT / lane_cfg.artifacts.output_dir_template.format(date=args.date, device=args.device)
    run_dir.mkdir(parents=True, exist_ok=True)

    notes_template = REPO_ROOT / stage2_cfg.templates.notes
    scenario_a_template = REPO_ROOT / stage2_cfg.templates.scenario_a
    scenario_b_template = REPO_ROOT / stage2_cfg.templates.scenario_b
    for path in (notes_template, scenario_a_template, scenario_b_template):
        if not path.exists():
            raise DevctlError("CONFIG_ERROR", f"Missing template file: {path}")

    notes_path = run_dir / "notes.md"
    scenario_a_path = run_dir / "scenario-a.csv"
    scenario_b_path = run_dir / "scenario-b.csv"
    threshold_input_path = run_dir / "stage-2-threshold-input.csv"
    threshold_report_path = run_dir / "threshold-report.txt"
    logcat_path = run_dir / "logcat.txt"
    summary_path = run_dir / "summary.json"

    shutil.copy2(notes_template, notes_path)
    shutil.copy2(scenario_a_template, scenario_a_path)
    shutil.copy2(scenario_b_template, scenario_b_path)

    if args.scenario_a:
        shutil.copy2(REPO_ROOT / args.scenario_a, scenario_a_path)
    if args.scenario_b:
        shutil.copy2(REPO_ROOT / args.scenario_b, scenario_b_path)

    with scenario_a_path.open("r", encoding="utf-8") as handle_a, threshold_input_path.open("w", encoding="utf-8") as handle_out:
        rows = handle_a.readlines()
        if not rows:
            raise DevctlError("SCHEMA_ERROR", f"Empty scenario A CSV: {scenario_a_path}")
        handle_out.write(rows[0])
        for row in rows[1:]:
            handle_out.write(row)

    with scenario_b_path.open("r", encoding="utf-8") as handle_b, threshold_input_path.open("a", encoding="utf-8") as handle_out:
        rows = handle_b.readlines()
        for row in rows[1:]:
            handle_out.write(row)

    validate_threshold_columns(threshold_input_path, stage2_cfg.threshold_csv.columns)

    if shutil.which("adb"):
        try:
            logcat_result = context.run(
                ["adb", "-s", args.device, "logcat", "-d"],
                check=False,
                capture_output=True,
                env=context.env,
                timeout_seconds=float(stage2_cfg.adb_logcat_timeout_seconds),
            )
            if logcat_result.returncode == 0:
                logcat_path.write_text(logcat_result.stdout or "", encoding="utf-8")
            else:
                logcat_path.write_text(
                    f"adb logcat collection failed for {args.device}\n{logcat_result.stderr or ''}",
                    encoding="utf-8",
                )
        except DevctlError as exc:
            logcat_path.write_text(
                f"adb logcat collection timed out or failed for {args.device}\n{exc.code}: {exc.message}\n",
                encoding="utf-8",
            )
    else:
        logcat_path.write_text("adb not found on host\n", encoding="utf-8")

    threshold_result = context.run(
        [*lane_cfg.threshold_command, str(threshold_input_path)],
        check=False,
        capture_output=True,
        env=context.env,
    )
    threshold_report_path.write_text((threshold_result.stdout or "") + (threshold_result.stderr or ""), encoding="utf-8")

    summary_data = {
        "stage": "stage2",
        "device_id": args.device,
        "run_date": args.date,
        "run_dir": str(run_dir.relative_to(REPO_ROOT)),
        "scenario_a_csv": str(scenario_a_path.relative_to(REPO_ROOT)),
        "scenario_b_csv": str(scenario_b_path.relative_to(REPO_ROOT)),
        "threshold_input_csv": str(threshold_input_path.relative_to(REPO_ROOT)),
        "threshold_report": str(threshold_report_path.relative_to(REPO_ROOT)),
        "logcat": str(logcat_path.relative_to(REPO_ROOT)),
        "notes": str(notes_path.relative_to(REPO_ROOT)),
        "summary_json": str(summary_path.relative_to(REPO_ROOT)),
        "threshold_exit_code": threshold_result.returncode,
    }
    filtered = {field: summary_data[field] for field in stage2_cfg.summary_json.fields if field in summary_data}
    summary_path.write_text(json.dumps(filtered, indent=2) + "\n", encoding="utf-8")

    missing_files = [name for name in stage2_cfg.required_files if not (run_dir / name).exists()]
    if missing_files:
        raise DevctlError("SCHEMA_ERROR", f"stage2 lane did not produce required file(s): {', '.join(missing_files)}")

    if threshold_result.returncode != 0:
        raise DevctlError(
            "THRESHOLD_FAIL",
            f"Threshold evaluation failed with exit code {threshold_result.returncode}. See {threshold_report_path}",
        )

    print_step(f"Stage-2 benchmark artifacts: {run_dir}")
    print_step(f"Summary JSON: {summary_path}")


def lane_nightly_hardware(raw_args: Sequence[str], context: RuntimeContext) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane nightly-hardware")

    if shutil.which("adb") is None:
        print_step("Nightly hardware lane prerequisite missing: adb not installed.")
        return

    devices = context.run(["adb", "devices", "-l"], check=False, capture_output=True, env=context.env)
    if devices.returncode != 0 or " device " not in (devices.stdout or ""):
        print_step("Nightly hardware lane prerequisite missing: no authorized physical device attached.")
        return

    print_step("Authorized device detected. Running nightly hardware smoke lane.")
    lane_device(["1", "nightly-hardware-smoke"], context)


def dispatch_lane(lane_name: str, lane_args: Sequence[str], context: RuntimeContext) -> None:
    handlers = {
        "test": lane_test,
        "device": lane_device,
        "stage2": lane_stage2,
        "android-instrumented": lane_android_instrumented,
        "maestro": lane_maestro,
        "nightly-hardware": lane_nightly_hardware,
    }
    handler = handlers.get(lane_name)
    if handler is None:
        raise DevctlError("CONFIG_ERROR", f"Unknown lane '{lane_name}'.")
    handler(lane_args, context)


def build_runtime_context(configs: DevctlConfigs, env: Mapping[str, str] | None = None) -> RuntimeContext:
    return RuntimeContext(
        repo_root=REPO_ROOT,
        configs=configs,
        env=dict(env or os.environ),
        run=run_subprocess,
    )
