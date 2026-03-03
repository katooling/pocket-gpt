from __future__ import annotations

import shlex
import subprocess
from pathlib import Path
from typing import Mapping, Sequence

REPO_ROOT = Path(__file__).resolve().parents[2]
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


def print_step(message: str) -> None:
    print(f"[devctl] {message}")


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
    print_step(f"$ {format_command(command)}")
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
        timeout = timeout_seconds if timeout_seconds is not None else 0
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            f"Command timed out after {timeout:.1f}s: {format_command(command)}",
        ) from exc

    if check and completed.returncode != 0:
        stderr = (completed.stderr or "").strip()
        detail = f"Command failed ({completed.returncode}): {format_command(command)}"
        if stderr:
            detail = f"{detail}\n{stderr}"
        raise DevctlError("ENVIRONMENT_ERROR", detail)

    return completed
