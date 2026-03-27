from __future__ import annotations

import webbrowser
from dataclasses import dataclass
from pathlib import Path

from tools.devctl.subprocess_utils import DevctlError


@dataclass(frozen=True)
class ReportBundle:
    kind: str
    artifact_root: Path
    report_files: tuple[Path, ...]


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _sorted_candidates(paths: list[Path]) -> list[Path]:
    return sorted(paths, key=lambda path: (path.stat().st_mtime, str(path)))


def _find_latest(path_patterns: tuple[str, ...], repo_root: Path) -> Path:
    candidates: list[Path] = []
    for pattern in path_patterns:
        candidates.extend(path for path in repo_root.glob(pattern) if path.is_file())
    if not candidates:
        raise DevctlError("CONFIG_ERROR", "No matching Maestro report artifacts were found.")
    return _sorted_candidates(candidates)[-1]


def find_latest_bundle(kind: str, repo_root: Path | None = None) -> ReportBundle:
    root = repo_root or _repo_root()
    normalized = kind.strip().lower()
    if normalized == "journey":
        report_path = _find_latest(
            (
                "tmp/devctl-artifacts/*/*/journey/*/journey-report.json",
                "scripts/benchmarks/runs/*/*/journey/*/journey-report.json",
            ),
            root,
        )
        artifact_root = report_path.parent
        summary_path = artifact_root / "journey-summary.md"
        logcat_path = artifact_root / "logcat.txt"
        files = tuple(path for path in (summary_path, report_path, logcat_path) if path.exists())
        return ReportBundle(kind="journey", artifact_root=artifact_root, report_files=files)

    if normalized == "screenshot-pack":
        report_path = _find_latest(
            (
                "scripts/benchmarks/runs/*/*/screenshot-pack/*/inventory-report.json",
                "tmp/devctl-artifacts/*/*/screenshot-pack/*/inventory-report.json",
            ),
            root,
        )
        artifact_root = report_path.parent
        markdown_path = artifact_root / "inventory-report.md"
        logcat_path = artifact_root / "logcat.txt"
        files = tuple(path for path in (markdown_path, report_path, logcat_path) if path.exists())
        return ReportBundle(kind="screenshot-pack", artifact_root=artifact_root, report_files=files)

    raise DevctlError("CONFIG_ERROR", f"Unknown report kind '{kind}'")


def print_bundle(bundle: ReportBundle) -> None:
    print(f"{bundle.kind} report artifacts:")
    print(f"  Artifact root: {bundle.artifact_root}")
    for path in bundle.report_files:
        print(f"  {path}")


def open_bundle(bundle: ReportBundle) -> None:
    for path in bundle.report_files:
        webbrowser.open(path.resolve().as_uri())


def show_report(kind: str, repo_root: Path | None = None, open_files: bool = False) -> ReportBundle:
    bundle = find_latest_bundle(kind, repo_root=repo_root)
    print_bundle(bundle)
    if open_files:
        open_bundle(bundle)
    return bundle
