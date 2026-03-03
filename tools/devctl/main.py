#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Mapping, MutableMapping, Sequence

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG_DIR = REPO_ROOT / "config" / "devctl"
VALID_ERROR_CODES = {
    "CONFIG_ERROR",
    "ENVIRONMENT_ERROR",
    "DEVICE_ERROR",
    "SCHEMA_ERROR",
    "THRESHOLD_FAIL",
}


class DevctlError(RuntimeError):
    def __init__(self, code: str, message: str):
        if code not in VALID_ERROR_CODES:
            raise ValueError(f"Invalid devctl error code: {code}")
        super().__init__(message)
        self.code = code
        self.message = message


def _print_step(message: str) -> None:
    print(f"[devctl] {message}")


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise DevctlError("CONFIG_ERROR", f"Missing config file: {path}")

    text = path.read_text(encoding="utf-8")
    data: Any
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(text)
    except ModuleNotFoundError:
        # Fallback supports YAML files authored as JSON (YAML 1.2-compatible).
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise DevctlError(
                "CONFIG_ERROR",
                f"Failed to parse {path} without PyYAML (JSON-compatible YAML required): {exc}",
            ) from exc
    except Exception as exc:  # pragma: no cover - defensive path
        raise DevctlError("CONFIG_ERROR", f"Failed to parse {path}: {exc}") from exc

    if not isinstance(data, dict):
        raise DevctlError("CONFIG_ERROR", f"Config root must be an object in {path}")
    return data


def load_devctl_configs(repo_root: Path = REPO_ROOT) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    config_root = repo_root / "config" / "devctl"
    lanes = load_config(config_root / "lanes.yaml")
    device = load_config(config_root / "device.yaml")
    stage2 = load_config(config_root / "stage2.yaml")
    return lanes, device, stage2


def format_command(command: Sequence[str]) -> str:
    return shlex.join(list(command))


def run_subprocess(
    command: Sequence[str],
    *,
    check: bool = True,
    capture_output: bool = False,
    env: Mapping[str, str] | None = None,
    cwd: Path = REPO_ROOT,
    timeout_seconds: float | None = None,
) -> subprocess.CompletedProcess[str]:
    _print_step(f"$ {format_command(command)}")
    try:
        completed = subprocess.run(
            list(command),
            cwd=str(cwd),
            env=dict(env) if env is not None else None,
            text=True,
            capture_output=capture_output,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            f"Command timed out after {timeout_seconds:.1f}s: {format_command(command)}",
        ) from exc

    if check and completed.returncode != 0:
        stderr = (completed.stderr or "").strip()
        detail = f"Command failed ({completed.returncode}): {format_command(command)}"
        if stderr:
            detail = f"{detail}\n{stderr}"
        raise DevctlError("ENVIRONMENT_ERROR", detail)
    return completed


def build_artifact_dir(template: str, date: str, device: str, label: str, stamp: str) -> Path:
    resolved = template.format(date=date, device=device, label=label, stamp=stamp)
    return REPO_ROOT / resolved


def build_gradle_test_command(mode: str, android_configured: bool, lane_cfg: dict[str, Any]) -> list[str]:
    if mode not in {"full", "quick", "ci"}:
        raise DevctlError("CONFIG_ERROR", f"Unsupported test mode: {mode}")

    gradle = lane_cfg.get("gradle_binary")
    flags = lane_cfg.get("gradle_flags")
    common_tasks = lane_cfg.get("common_tasks")
    android_tasks = lane_cfg.get("android_tasks")

    if not isinstance(gradle, str):
        raise DevctlError("CONFIG_ERROR", "lanes.test.gradle_binary must be a string")
    if not isinstance(flags, list) or not all(isinstance(v, str) for v in flags):
        raise DevctlError("CONFIG_ERROR", "lanes.test.gradle_flags must be a string list")
    if not isinstance(common_tasks, list) or not all(isinstance(v, str) for v in common_tasks):
        raise DevctlError("CONFIG_ERROR", "lanes.test.common_tasks must be a string list")
    if not isinstance(android_tasks, list) or not all(isinstance(v, str) for v in android_tasks):
        raise DevctlError("CONFIG_ERROR", "lanes.test.android_tasks must be a string list")

    command = [gradle, *flags]
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


def _ensure_serial(context: "RuntimeContext") -> str:
    ensure_command = context.device_cfg.get("preflight", {}).get("ensure_device_command")
    if not isinstance(ensure_command, list) or not all(isinstance(v, str) for v in ensure_command):
        raise DevctlError("CONFIG_ERROR", "device.preflight.ensure_device_command must be a string list")

    result = context.run(ensure_command, check=False, capture_output=True, env=context.env)
    if result.returncode != 0:
        stderr = (result.stderr or "").strip()
        raise DevctlError(
            "DEVICE_ERROR",
            f"Device preflight failed. Command: {format_command(ensure_command)}\n{stderr}",
        )

    serial = (result.stdout or "").strip().splitlines()
    if not serial:
        raise DevctlError("DEVICE_ERROR", "Device preflight returned empty serial output.")
    return serial[-1].strip()


def _validate_required_device_props(context: "RuntimeContext", serial: str) -> None:
    required_props = context.device_cfg.get("preflight", {}).get("required_props")
    if required_props is None:
        return
    if not isinstance(required_props, list) or not all(isinstance(v, str) for v in required_props):
        raise DevctlError("CONFIG_ERROR", "device.preflight.required_props must be a string list")

    missing: list[str] = []
    for prop in required_props:
        result = context.run(
            ["adb", "-s", serial, "shell", "getprop", prop],
            check=False,
            capture_output=True,
            env=context.env,
        )
        value = (result.stdout or "").strip()
        if result.returncode != 0 or not value:
            missing.append(prop)

    if missing:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Required device properties are unavailable: {', '.join(missing)}",
        )


def _extract_summary_path(output: str) -> Path | None:
    matches = re.findall(r"Run summary written to ([^\n]+summary\.csv)", output)
    if not matches:
        return None
    summary_text = matches[-1].strip()
    summary_path = Path(summary_text)
    if not summary_path.is_absolute():
        summary_path = REPO_ROOT / summary_path
    return summary_path


def _normalize_signature_patterns(value: Any, key: str) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
        raise DevctlError("CONFIG_ERROR", f"{key} must be a string list")
    return value


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
            if re.search(pattern, text) is not None:
                return True
        except re.error as exc:
            raise DevctlError("CONFIG_ERROR", f"Invalid regex pattern '{pattern}': {exc}") from exc
    return False


def _evaluate_loop_output(
    summary_path: Path,
    crash_patterns: Sequence[str],
    oom_patterns: Sequence[str],
) -> None:
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
            flags: list[str] = []
            if crash:
                flags.append("crash")
            if oom:
                flags.append("oom")
            failing_runs.append(f"{run_id} ({'+'.join(flags)})")

    if failing_runs:
        raise DevctlError(
            "DEVICE_ERROR",
            f"Crash/OOM signals detected in short-loop results: {', '.join(failing_runs)}",
        )


@dataclass
class DeviceLaneArgs:
    runs: int
    label: str
    framework: str
    framework_explicit: bool
    scenario_command: list[str]


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


def _run_lane_commands(context: "RuntimeContext", commands: Sequence[Sequence[str]]) -> None:
    for command in commands:
        context.run(command, check=True, env=context.env)


def lane_test(raw_args: Sequence[str], context: "RuntimeContext") -> None:
    mode = raw_args[0] if raw_args else "full"
    if len(raw_args) > 1:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane test [full|quick|ci]")

    lanes = context.lanes_cfg.get("lanes")
    if not isinstance(lanes, dict):
        raise DevctlError("CONFIG_ERROR", "lanes.yaml missing 'lanes' object")
    lane_cfg = lanes.get("test")
    if not isinstance(lane_cfg, dict):
        raise DevctlError("CONFIG_ERROR", "lanes.yaml missing lanes.test")

    python_commands = [
        step.get("argv")
        for step in lane_cfg.get("commands", [])
        if isinstance(step, dict) and step.get("name") == "python_unit_tests"
    ]
    for command in python_commands:
        if not isinstance(command, list) or not all(isinstance(v, str) for v in command):
            raise DevctlError("CONFIG_ERROR", "Invalid python_unit_tests command in lanes.test.commands")
        context.run(command, check=True, env=context.env)

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        _print_step("Android SDK not configured; running host/JVM test lane only.")

    gradle_command = build_gradle_test_command(mode, android_configured, lane_cfg)
    context.run(gradle_command, check=True, env=resolved_env)


def lane_android_instrumented(raw_args: Sequence[str], context: "RuntimeContext", strict: bool = True) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane android-instrumented")

    android_configured, resolved_env = _resolve_android_env(context.env)
    if not android_configured:
        message = "Android SDK not configured for instrumentation lane."
        if strict:
            raise DevctlError("ENVIRONMENT_ERROR", message)
        _print_step(f"Skipping instrumentation lane: {message}")
        return

    lane_cfg = context.lanes_cfg.get("lanes", {}).get("android-instrumented")
    if not isinstance(lane_cfg, dict):
        raise DevctlError("CONFIG_ERROR", "lanes.yaml missing lanes.android-instrumented")

    commands = lane_cfg.get("commands")
    if not isinstance(commands, list):
        raise DevctlError("CONFIG_ERROR", "lanes.android-instrumented.commands must be a list")

    for step in commands:
        if not isinstance(step, dict) or not isinstance(step.get("argv"), list):
            raise DevctlError("CONFIG_ERROR", "Invalid step in lanes.android-instrumented.commands")
        argv = step["argv"]
        if not all(isinstance(value, str) for value in argv):
            raise DevctlError("CONFIG_ERROR", "android-instrumented argv entries must be strings")
        context.run(argv, check=True, env=resolved_env)


def lane_maestro(raw_args: Sequence[str], context: "RuntimeContext", strict: bool = True) -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane maestro")

    lane_cfg = context.lanes_cfg.get("lanes", {}).get("maestro")
    if not isinstance(lane_cfg, dict):
        raise DevctlError("CONFIG_ERROR", "lanes.yaml missing lanes.maestro")

    maestro_bin = shutil.which("maestro")
    if maestro_bin is None:
        message = "Maestro CLI is not installed. Install with: curl -Ls https://get.maestro.mobile.dev | bash"
        if strict:
            raise DevctlError("ENVIRONMENT_ERROR", message)
        _print_step(f"Skipping Maestro lane: {message}")
        return

    flows = lane_cfg.get("flows")
    if not isinstance(flows, list) or not all(isinstance(flow, str) for flow in flows):
        raise DevctlError("CONFIG_ERROR", "lanes.maestro.flows must be a string list")

    for flow in flows:
        flow_path = REPO_ROOT / flow
        if not flow_path.exists():
            raise DevctlError("CONFIG_ERROR", f"Missing Maestro flow: {flow_path}")
        context.run([maestro_bin, "test", str(flow_path)], check=True, env=context.env)


def lane_device(raw_args: Sequence[str], context: "RuntimeContext") -> None:
    lane_cfg = context.lanes_cfg.get("lanes", {}).get("device")
    if not isinstance(lane_cfg, dict):
        raise DevctlError("CONFIG_ERROR", "lanes.yaml missing lanes.device")

    default_command = lane_cfg.get("default_scenario_command")
    if not isinstance(default_command, list) or not all(isinstance(v, str) for v in default_command):
        raise DevctlError("CONFIG_ERROR", "lanes.device.default_scenario_command must be a string list")

    parsed = parse_device_lane_args(raw_args, default_command)
    serial = _ensure_serial(context)
    _validate_required_device_props(context, serial)

    artifacts_cfg = lane_cfg.get("artifacts", {})
    output_template = artifacts_cfg.get("output_dir_template")
    if isinstance(output_template, str):
        date_value = datetime.now().strftime("%Y-%m-%d")
        stamp_value = datetime.now().strftime("%Y%m%d-%H%M%S")
        resolved_dir = build_artifact_dir(output_template, date_value, serial, parsed.label, stamp_value)
        _print_step(f"Resolved artifact output directory: {resolved_dir}")

    commands = lane_cfg.get("commands")
    if not isinstance(commands, list):
        raise DevctlError("CONFIG_ERROR", "lanes.device.commands must be a list")

    command_map: dict[str, list[str]] = {}
    for step in commands:
        if not isinstance(step, dict):
            raise DevctlError("CONFIG_ERROR", "Each lanes.device.commands entry must be an object")
        name = step.get("name")
        argv = step.get("argv")
        if not isinstance(name, str) or not isinstance(argv, list) or not all(isinstance(v, str) for v in argv):
            raise DevctlError("CONFIG_ERROR", "Invalid lanes.device.commands entry")
        command_map[name] = argv

    required_command_names = {
        "stage_checks",
        "capture_baseline",
        "apply_benchmark_settings",
        "reset_benchmark_settings",
        "run_short_loop",
    }
    missing_names = sorted(required_command_names - set(command_map.keys()))
    if missing_names:
        raise DevctlError(
            "CONFIG_ERROR",
            f"lanes.device.commands missing required steps: {', '.join(missing_names)}",
        )

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

        crash_patterns = _normalize_signature_patterns(
            context.device_cfg.get("crash_signatures", []),
            "device.crash_signatures",
        )
        oom_patterns = _normalize_signature_patterns(
            context.device_cfg.get("oom_signatures", []),
            "device.oom_signatures",
        )
        summary_path = _extract_summary_path(loop_result.stdout or "")
        if summary_path is not None:
            _evaluate_loop_output(summary_path, crash_patterns, oom_patterns)
        else:
            _print_step("Short-loop summary path not found in output; skipping crash/OOM post-scan.")

        if parsed.framework in {"espresso", "both"}:
            try:
                lane_android_instrumented([], context, strict=parsed.framework_explicit)
            except DevctlError as exc:
                if not parsed.framework_explicit and exc.code == "ENVIRONMENT_ERROR":
                    _print_step(f"Skipping espresso lane (default framework mode): {exc.message}")
                else:
                    raise

        if parsed.framework in {"maestro", "both"}:
            try:
                lane_maestro([], context, strict=parsed.framework_explicit)
            except DevctlError as exc:
                if not parsed.framework_explicit and exc.code == "ENVIRONMENT_ERROR":
                    _print_step(f"Skipping Maestro lane (default framework mode): {exc.message}")
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


def lane_stage2(raw_args: Sequence[str], context: "RuntimeContext") -> None:
    args = _parse_stage2_args(raw_args)
    lane_cfg = context.lanes_cfg.get("lanes", {}).get("stage2")
    if not isinstance(lane_cfg, dict):
        raise DevctlError("CONFIG_ERROR", "lanes.yaml missing lanes.stage2")

    stage2_cfg = context.stage2_cfg

    output_template = lane_cfg.get("artifacts", {}).get("output_dir_template")
    if not isinstance(output_template, str):
        raise DevctlError("CONFIG_ERROR", "lanes.stage2.artifacts.output_dir_template must be a string")

    run_dir = REPO_ROOT / output_template.format(date=args.date, device=args.device)
    run_dir.mkdir(parents=True, exist_ok=True)

    templates = stage2_cfg.get("templates")
    if not isinstance(templates, dict):
        raise DevctlError("CONFIG_ERROR", "stage2.yaml missing templates map")

    notes_template = REPO_ROOT / str(templates.get("notes", ""))
    scenario_a_template = REPO_ROOT / str(templates.get("scenario_a", ""))
    scenario_b_template = REPO_ROOT / str(templates.get("scenario_b", ""))
    if not notes_template.exists() or not scenario_a_template.exists() or not scenario_b_template.exists():
        raise DevctlError("CONFIG_ERROR", "Missing one or more stage2 template files")

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

    with scenario_a_path.open("r", encoding="utf-8") as handle_a, threshold_input_path.open(
        "w", encoding="utf-8"
    ) as handle_out:
        lines = handle_a.readlines()
        if not lines:
            raise DevctlError("SCHEMA_ERROR", f"Empty scenario A CSV: {scenario_a_path}")
        handle_out.write(lines[0])
        for line in lines[1:]:
            handle_out.write(line)

    with scenario_b_path.open("r", encoding="utf-8") as handle_b, threshold_input_path.open(
        "a", encoding="utf-8"
    ) as handle_out:
        lines = handle_b.readlines()
        for line in lines[1:]:
            handle_out.write(line)

    required_columns = stage2_cfg.get("threshold_csv", {}).get("columns", [])
    if not isinstance(required_columns, list) or not all(isinstance(col, str) for col in required_columns):
        raise DevctlError("CONFIG_ERROR", "stage2.yaml threshold_csv.columns must be a string list")
    validate_threshold_columns(threshold_input_path, required_columns)

    if shutil.which("adb"):
        timeout_seconds = stage2_cfg.get("adb_logcat_timeout_seconds", 15)
        if not isinstance(timeout_seconds, (int, float)) or timeout_seconds <= 0:
            raise DevctlError("CONFIG_ERROR", "stage2.yaml adb_logcat_timeout_seconds must be a positive number")

        try:
            logcat_result = context.run(
                ["adb", "-s", args.device, "logcat", "-d"],
                check=False,
                capture_output=True,
                env=context.env,
                timeout_seconds=float(timeout_seconds),
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

    threshold_command = lane_cfg.get("threshold_command")
    if not isinstance(threshold_command, list) or not all(isinstance(v, str) for v in threshold_command):
        raise DevctlError("CONFIG_ERROR", "lanes.stage2.threshold_command must be a string list")

    threshold_result = context.run(
        [*threshold_command, str(threshold_input_path)],
        check=False,
        capture_output=True,
        env=context.env,
    )
    threshold_report_path.write_text(
        (threshold_result.stdout or "") + (threshold_result.stderr or ""),
        encoding="utf-8",
    )

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

    summary_fields = stage2_cfg.get("summary_json", {}).get("fields")
    if not isinstance(summary_fields, list) or not all(isinstance(field, str) for field in summary_fields):
        raise DevctlError("CONFIG_ERROR", "stage2.yaml summary_json.fields must be a string list")
    filtered_summary = {field: summary_data[field] for field in summary_fields if field in summary_data}
    summary_path.write_text(json.dumps(filtered_summary, indent=2) + "\n", encoding="utf-8")

    required_files = stage2_cfg.get("required_files")
    if not isinstance(required_files, list) or not all(isinstance(name, str) for name in required_files):
        raise DevctlError("CONFIG_ERROR", "stage2.yaml required_files must be a string list")
    missing_files = [name for name in required_files if not (run_dir / name).exists()]
    if missing_files:
        raise DevctlError(
            "SCHEMA_ERROR",
            f"stage2 lane did not produce required file(s): {', '.join(missing_files)}",
        )

    if threshold_result.returncode != 0:
        raise DevctlError(
            "THRESHOLD_FAIL",
            f"Threshold evaluation failed with exit code {threshold_result.returncode}. See {threshold_report_path}",
        )

    _print_step(f"Stage-2 benchmark artifacts: {run_dir}")
    _print_step(f"Summary JSON: {summary_path}")


def lane_nightly_hardware(raw_args: Sequence[str], context: "RuntimeContext") -> None:
    if raw_args:
        raise DevctlError("CONFIG_ERROR", "Usage: devctl lane nightly-hardware")

    if shutil.which("adb") is None:
        _print_step("Nightly hardware lane prerequisite missing: adb not installed.")
        return

    devices = context.run(["adb", "devices", "-l"], check=False, capture_output=True, env=context.env)
    if devices.returncode != 0 or " device " not in (devices.stdout or ""):
        _print_step("Nightly hardware lane prerequisite missing: no authorized physical device attached.")
        return

    _print_step("Authorized device detected. Running nightly hardware smoke lane.")
    lane_device(["1", "nightly-hardware-smoke"], context)


@dataclass
class RuntimeContext:
    repo_root: Path
    lanes_cfg: dict[str, Any]
    device_cfg: dict[str, Any]
    stage2_cfg: dict[str, Any]
    env: MutableMapping[str, str]
    run: Callable[..., subprocess.CompletedProcess[str]]


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


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="devctl")
    subparsers = parser.add_subparsers(dest="command", required=True)

    lane_parser = subparsers.add_parser("lane", help="run a named lane")
    lane_parser.add_argument("name", help="lane name")
    lane_parser.add_argument("args", nargs=argparse.REMAINDER, help="lane-specific args")

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    parsed = parser.parse_args(list(argv) if argv is not None else None)

    try:
        lanes_cfg, device_cfg, stage2_cfg = load_devctl_configs(REPO_ROOT)
        context = RuntimeContext(
            repo_root=REPO_ROOT,
            lanes_cfg=lanes_cfg,
            device_cfg=device_cfg,
            stage2_cfg=stage2_cfg,
            env=dict(os.environ),
            run=run_subprocess,
        )
        dispatch_lane(parsed.name, parsed.args, context)
        return 0
    except DevctlError as exc:
        print(f"{exc.code}: {exc.message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
