from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ArtifactsConfig(_StrictModel):
    output_dir_template: str


class CommandStep(_StrictModel):
    name: str
    argv: list[str]


class TestLaneConfig(_StrictModel):
    requires_android_sdk: bool
    requires_device: bool
    artifacts: ArtifactsConfig
    commands: list[CommandStep]
    gradle_binary: str
    gradle_flags: list[str]
    common_tasks: list[str]
    android_tasks: list[str]


class DeviceLaneConfig(_StrictModel):
    requires_android_sdk: bool
    requires_device: bool
    artifacts: ArtifactsConfig
    commands: list[CommandStep]
    default_scenario_command: list[str]


class Stage2LaneConfig(_StrictModel):
    requires_android_sdk: bool
    requires_device: bool
    artifacts: ArtifactsConfig
    threshold_command: list[str]


class AndroidInstrumentedLaneConfig(_StrictModel):
    requires_android_sdk: bool
    requires_device: bool
    artifacts: ArtifactsConfig
    commands: list[CommandStep]


class MaestroLaneConfig(_StrictModel):
    requires_android_sdk: bool
    requires_device: bool
    artifacts: ArtifactsConfig
    version: str
    preflight_commands: list[list[str]] = Field(default_factory=list)
    flows: list[str]


class NightlyHardwareLaneConfig(_StrictModel):
    requires_android_sdk: bool
    requires_device: bool
    artifacts: ArtifactsConfig
    commands: list[CommandStep] = Field(default_factory=list)


class LanesContainer(_StrictModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    test: TestLaneConfig
    device: DeviceLaneConfig
    stage2: Stage2LaneConfig
    android_instrumented: AndroidInstrumentedLaneConfig = Field(alias="android-instrumented")
    maestro: MaestroLaneConfig
    nightly_hardware: NightlyHardwareLaneConfig = Field(alias="nightly-hardware")


class LanesRoot(_StrictModel):
    lanes: LanesContainer


class PreflightConfig(_StrictModel):
    required_props: list[str]
    ensure_device_command: list[str]


class BenchmarkSettingsConfig(_StrictModel):
    apply: list[str]
    reset: list[str]


class DeviceConfig(_StrictModel):
    preflight: PreflightConfig
    benchmark_settings: BenchmarkSettingsConfig
    crash_signatures: list[str]
    oom_signatures: list[str]


class ThresholdCsvConfig(_StrictModel):
    columns: list[str]


class SummaryJsonConfig(_StrictModel):
    fields: list[str]


class TemplatesConfig(_StrictModel):
    notes: str
    scenario_a: str
    scenario_b: str


class Stage2Config(_StrictModel):
    required_files: list[str]
    threshold_csv: ThresholdCsvConfig
    summary_json: SummaryJsonConfig
    templates: TemplatesConfig
    adb_logcat_timeout_seconds: float = 15.0


@dataclass(frozen=True)
class DevctlConfigs:
    lanes: LanesRoot
    device: DeviceConfig
    stage2: Stage2Config


def _load_yaml_file(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise DevctlError("CONFIG_ERROR", f"Missing config file: {path}")

    try:
        import yaml  # type: ignore
    except ModuleNotFoundError as exc:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "PyYAML is required. Install dependencies with: python3 -m pip install -r tools/devctl/requirements.txt",
        ) from exc

    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise DevctlError("CONFIG_ERROR", f"Failed to parse {path}: {exc}") from exc

    if not isinstance(data, dict):
        raise DevctlError("CONFIG_ERROR", f"Config root must be an object in {path}")
    return data


def _validate_model(model_type: type[BaseModel], data: dict[str, Any], path: Path) -> BaseModel:
    try:
        return model_type.model_validate(data)
    except ValidationError as exc:
        raise DevctlError("CONFIG_ERROR", f"Schema validation failed for {path}: {exc}") from exc


def load_devctl_configs(repo_root: Path = REPO_ROOT) -> DevctlConfigs:
    config_root = repo_root / "config" / "devctl"
    lanes_path = config_root / "lanes.yaml"
    device_path = config_root / "device.yaml"
    stage2_path = config_root / "stage2.yaml"

    lanes = _validate_model(LanesRoot, _load_yaml_file(lanes_path), lanes_path)
    device = _validate_model(DeviceConfig, _load_yaml_file(device_path), device_path)
    stage2 = _validate_model(Stage2Config, _load_yaml_file(stage2_path), stage2_path)

    return DevctlConfigs(
        lanes=lanes,  # type: ignore[arg-type]
        device=device,  # type: ignore[arg-type]
        stage2=stage2,  # type: ignore[arg-type]
    )
