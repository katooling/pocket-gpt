from __future__ import annotations

import json
import os
import re
import tempfile
from datetime import datetime
from pathlib import Path

from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT, run_subprocess

try:
    import yaml  # type: ignore
except ModuleNotFoundError:  # pragma: no cover - dependency guard is handled at runtime.
    yaml = None

CANONICAL_DOCS = [
    "scripts/dev/README.md",
    "docs/testing/test-strategy.md",
    "docs/testing/android-dx-and-test-playbook.md",
]

NON_CANONICAL_DOCS = [
    "README.md",
    "docs/README.md",
    "docs/operations/README.md",
    "docs/testing/just-cli-android-validation-plan.md",
]

PR_REQUIRED_PATTERNS: list[tuple[str, str]] = [
    (
        r"- \[x\] I ran `bash scripts/dev/test\.sh` \(or `bash scripts/dev/test\.sh ci`\) and it passed\.",
        "test command checkbox must be checked",
    ),
    (
        r"- \[x\] I used canonical orchestrator lanes \(`python3 tools/devctl/main\.py lane \.\.\.`\) directly or via the `scripts/dev/\*` wrappers\.",
        "orchestrator lane checkbox must be checked",
    ),
    (
        r"- \[x\] I updated docs affected by this change, or confirmed no docs changes are needed\.",
        "docs checkbox must be checked",
    ),
    (
        r"- \[x\] I ran `python3 tools/devctl/main\.py governance docs-health` and it passed\.",
        "docs-health checkbox must be checked",
    ),
    (
        r"- \[x\] For UI-touching changes, I ran `python3 tools/devctl/main\.py lane screenshot-pack` and manually reviewed screenshots \(or documented why not needed\)\.",
        "screenshot workflow checkbox must be checked",
    ),
]

STAGE_EVIDENCE_PATTERN = (
    r"- \[x\] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` "
    r"and linked it below\."
)

RUN_PATH_REGEX = r"scripts/benchmarks/runs/[^\s)`\"]+"
MARKDOWN_LINK_REGEX = re.compile(r"\[[^]]+\]\(([^)]+)\)")
STATUS_LINE_REGEX = re.compile(r"^Status:\s*", re.MULTILINE)

DOCS_HEALTH_REQUIRED_INDEX_MARKERS: dict[str, tuple[str, ...]] = {
    "docs/README.md": ("start-here/new-joiner.md", "start-here/resource-map.md"),
    "docs/operations/README.md": ("execution-board.md", "evidence/index.md", "tickets/"),
    "docs/ux/README.md": ("implemented-behavior-reference.md", "model-management-flow.md"),
}

DOCS_HEALTH_ALLOWED_EVIDENCE_WPS = {"wp-09", "wp-12", "wp-13"}
DOCS_HEALTH_SUMMARY_REQUIRED_WPS = ("WP-01", "WP-02", "WP-03", "WP-04", "WP-05", "WP-06", "WP-07", "WP-08", "WP-11")
DOCS_HEALTH_RETAINED_WP12_NOTES = {
    "2026-03-04-eng-12-model-distribution-implementation.md",
    "2026-03-04-eng-13-native-runtime-proof.md",
    "2026-03-04-eng-17-network-policy-wiring.md",
    "2026-03-04-prod-eng-12-model-distribution-decision.md",
    "2026-03-04-qa-wp12-closeout.md",
    "2026-03-05-eng-13-native-runtime-rerun.md",
    "2026-03-05-qa-wp12-closeout-rerun.md",
}

SCREENSHOT_INVENTORY_SCHEMA = "ui-screenshot-inventory-v1"
SCREENSHOT_INVENTORY_PATH = Path("tests/ui-screenshots/inventory.yaml")
SCREENSHOT_REFERENCE_DIR = Path("tests/ui-screenshots/reference/sm-a515f-android13")


def _read_file(path: Path) -> str:
    if not path.exists() or not path.is_file():
        raise DevctlError("CONFIG_ERROR", f"File not found: {path}")
    return path.read_text(encoding="utf-8")


def docs_drift_check(repo_root: Path = REPO_ROOT) -> None:
    violations: list[str] = []

    for rel in NON_CANONICAL_DOCS:
        path = repo_root / rel
        if not path.exists():
            continue

        text = path.read_text(encoding="utf-8")
        if "Source of truth" not in text:
            violations.append(f"Missing source-of-truth pointer in {rel}")

        if "```bash" in text and re.search(
            r"scripts/dev/test\.sh|scripts/dev/device-test\.sh|run_short_loop\.sh|evaluate_thresholds\.py",
            text,
        ):
            violations.append(f"Duplicated runnable command docs detected in non-canonical file: {rel}")

    for rel in CANONICAL_DOCS:
        if not (repo_root / rel).exists():
            violations.append(f"Missing canonical file: {rel}")

    if violations:
        raise DevctlError("CONFIG_ERROR", "\n".join(violations))

    print("Docs drift check passed.")


def _iter_docs_health_files(repo_root: Path) -> list[Path]:
    rel_paths = [
        "README.md",
        "docs",
        "scripts/dev/README.md",
        "scripts/android/README.md",
        "tests/maestro/README.md",
        "apps/mobile-android/README.md",
        "apps/mobile-android-host/README.md",
    ]
    files: set[Path] = set()
    for rel in rel_paths:
        path = repo_root / rel
        if not path.exists():
            continue
        if path.is_file() and path.suffix == ".md":
            files.add(path)
            continue
        if path.is_dir():
            for item in path.rglob("*.md"):
                rel_item = item.relative_to(repo_root).as_posix()
                if rel_item.startswith("docs/operations/evidence/"):
                    continue
                files.add(item)
    return sorted(files)


def _is_placeholder_target(target: str) -> bool:
    placeholder_markers = ("YYYY", "<", ">", "{", "}", "...", "wp-xx", "DEVICE_SERIAL_REDACTED")
    return any(marker in target for marker in placeholder_markers)


def _normalize_link_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    if " " in target and not target.startswith(("http://", "https://")):
        target = target.split(" ", 1)[0]
    target = target.split("#", 1)[0]
    target = target.split("?", 1)[0]
    return target.strip()


def _docs_health_broken_links(repo_root: Path) -> list[str]:
    violations: list[str] = []
    for file_path in _iter_docs_health_files(repo_root):
        text = file_path.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK_REGEX.finditer(text):
            raw_target = match.group(1)
            target = _normalize_link_target(raw_target)
            if not target:
                continue
            if target.startswith(("#", "mailto:", "http://", "https://")):
                continue
            if "://" in target:
                continue
            if _is_placeholder_target(target):
                continue

            if target.startswith("/"):
                resolved = Path(target)
            else:
                resolved = (file_path.parent / target).resolve()

            if not resolved.exists():
                rel_file = file_path.relative_to(repo_root).as_posix()
                violations.append(f"Broken local markdown link in {rel_file}: {raw_target}")
    return violations


def _docs_health_status_policy(repo_root: Path) -> list[str]:
    docs_root = repo_root / "docs"
    if not docs_root.exists():
        return ["Missing docs directory for status policy check."]

    allowed_board = repo_root / "docs/operations/execution-board.md"
    allowed_ticket_root = repo_root / "docs/operations/tickets"
    violations: list[str] = []

    for path in docs_root.rglob("*.md"):
        text = path.read_text(encoding="utf-8")
        if not STATUS_LINE_REGEX.search(text):
            continue
        if path == allowed_board:
            continue
        if allowed_ticket_root.exists() and path.is_relative_to(allowed_ticket_root):
            continue
        rel = path.relative_to(repo_root).as_posix()
        violations.append(f"Status line is only allowed in execution board or ticket specs: {rel}")

    return violations


def _docs_health_required_indexes(repo_root: Path) -> list[str]:
    violations: list[str] = []
    for rel_path, markers in DOCS_HEALTH_REQUIRED_INDEX_MARKERS.items():
        path = repo_root / rel_path
        if not path.exists():
            violations.append(f"Missing required index file: {rel_path}")
            continue
        text = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                violations.append(f"Missing required index pointer '{marker}' in {rel_path}")
    return violations


def _docs_health_evidence_retention(repo_root: Path) -> list[str]:
    evidence_root = repo_root / "docs/operations/evidence"
    violations: list[str] = []
    if not evidence_root.exists():
        return ["Missing docs/operations/evidence directory."]

    index_path = evidence_root / "index.md"
    if not index_path.exists():
        violations.append("Missing evidence summary index: docs/operations/evidence/index.md")
    else:
        index_text = index_path.read_text(encoding="utf-8")
        for wp_label in DOCS_HEALTH_SUMMARY_REQUIRED_WPS:
            if wp_label not in index_text:
                violations.append(f"Evidence index missing summary for {wp_label}")

    wp_dirs = [item for item in evidence_root.iterdir() if item.is_dir() and item.name.startswith("wp-")]
    for wp_dir in sorted(wp_dirs):
        markdown_files = sorted(wp_dir.glob("*.md"))
        if wp_dir.name not in DOCS_HEALTH_ALLOWED_EVIDENCE_WPS and markdown_files:
            rel = wp_dir.relative_to(repo_root).as_posix()
            violations.append(f"Historical evidence markdown should be pruned from {rel}")

    for wp in DOCS_HEALTH_ALLOWED_EVIDENCE_WPS:
        wp_dir = evidence_root / wp
        if not wp_dir.exists():
            violations.append(f"Missing active evidence directory: docs/operations/evidence/{wp}")
            continue
        if not list(wp_dir.glob("*.md")):
            violations.append(f"Active evidence directory has no markdown notes: docs/operations/evidence/{wp}")

    wp12_dir = evidence_root / "wp-12"
    if wp12_dir.exists():
        wp12_notes = {path.name for path in wp12_dir.glob("*.md")}
        missing = sorted(DOCS_HEALTH_RETAINED_WP12_NOTES - wp12_notes)
        extra = sorted(wp12_notes - DOCS_HEALTH_RETAINED_WP12_NOTES)
        for name in missing:
            violations.append(f"Missing required retained WP-12 evidence note: docs/operations/evidence/wp-12/{name}")
        for name in extra:
            violations.append(f"Unexpected WP-12 evidence note after prune: docs/operations/evidence/wp-12/{name}")

    return violations


def docs_health_check(repo_root: Path = REPO_ROOT) -> None:
    violations: list[str] = []
    violations.extend(_docs_health_broken_links(repo_root))
    violations.extend(_docs_health_status_policy(repo_root))
    violations.extend(_docs_health_required_indexes(repo_root))
    violations.extend(_docs_health_evidence_retention(repo_root))

    if violations:
        raise DevctlError("CONFIG_ERROR", "\n".join(sorted(violations)))

    print("Docs health check passed.")


def _load_screenshot_inventory_entries(repo_root: Path) -> list[tuple[str, str]]:
    if yaml is None:
        raise DevctlError(
            "ENVIRONMENT_ERROR",
            "PyYAML is required. Install dependencies with: python3 -m pip install -r tools/devctl/requirements.txt",
        )
    inventory_path = repo_root / SCREENSHOT_INVENTORY_PATH
    if not inventory_path.exists():
        raise DevctlError("CONFIG_ERROR", f"Missing screenshot inventory file: {inventory_path}")

    try:
        data = yaml.safe_load(inventory_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise DevctlError("CONFIG_ERROR", f"Failed to parse screenshot inventory {inventory_path}: {exc}") from exc
    if not isinstance(data, dict):
        raise DevctlError("CONFIG_ERROR", f"Invalid screenshot inventory root in {inventory_path}; expected object.")
    if data.get("schema") != SCREENSHOT_INVENTORY_SCHEMA:
        raise DevctlError(
            "CONFIG_ERROR",
            f"Unsupported screenshot inventory schema in {inventory_path}. "
            f"Expected '{SCREENSHOT_INVENTORY_SCHEMA}'.",
        )
    screenshots = data.get("screenshots")
    if not isinstance(screenshots, list) or not screenshots:
        raise DevctlError("CONFIG_ERROR", f"Screenshot inventory must define a non-empty screenshots list: {inventory_path}")

    id_pattern = re.compile(r"^ui-\d{2}-[a-z0-9-]+$")
    entries: list[tuple[str, str]] = []
    seen_ids: set[str] = set()
    seen_filenames: set[str] = set()
    for index, item in enumerate(screenshots, start=1):
        if not isinstance(item, dict):
            raise DevctlError("CONFIG_ERROR", f"Invalid screenshot inventory entry #{index} in {inventory_path}.")
        shot_id = str(item.get("id", "")).strip()
        filename = str(item.get("filename", "")).strip()
        if not shot_id or not filename:
            raise DevctlError(
                "CONFIG_ERROR",
                f"Screenshot inventory entry #{index} in {inventory_path} is missing id or filename.",
            )
        if not id_pattern.match(shot_id):
            raise DevctlError("CONFIG_ERROR", f"Invalid screenshot id '{shot_id}' in {inventory_path}.")
        if not filename.endswith(".png"):
            raise DevctlError(
                "CONFIG_ERROR",
                f"Screenshot filename must end with .png in {inventory_path}: {filename}",
            )
        if shot_id in seen_ids:
            raise DevctlError("CONFIG_ERROR", f"Duplicate screenshot id in {inventory_path}: {shot_id}")
        if filename in seen_filenames:
            raise DevctlError("CONFIG_ERROR", f"Duplicate screenshot filename in {inventory_path}: {filename}")
        seen_ids.add(shot_id)
        seen_filenames.add(filename)
        entries.append((shot_id, filename))
    return entries


def _latest_screenshot_inventory_report(repo_root: Path) -> Path | None:
    reports_root = repo_root / "scripts/benchmarks/runs"
    if not reports_root.exists():
        return None
    reports = sorted(
        reports_root.glob("*/*/screenshot-pack/*/inventory-report.json"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    return reports[0] if reports else None


def screenshot_inventory_check(repo_root: Path = REPO_ROOT) -> None:
    entries = _load_screenshot_inventory_entries(repo_root)
    violations: list[str] = []

    reference_dir = repo_root / SCREENSHOT_REFERENCE_DIR
    if not reference_dir.exists():
        violations.append(f"Missing screenshot reference directory: {SCREENSHOT_REFERENCE_DIR.as_posix()}")
    else:
        for shot_id, filename in entries:
            if not (reference_dir / filename).exists():
                violations.append(f"Missing screenshot reference for {shot_id}: {SCREENSHOT_REFERENCE_DIR.as_posix()}/{filename}")
        index_path = reference_dir / "index.md"
        if not index_path.exists():
            violations.append(f"Missing screenshot reference gallery index: {SCREENSHOT_REFERENCE_DIR.as_posix()}/index.md")

    latest_report_path = _latest_screenshot_inventory_report(repo_root)
    if latest_report_path is None:
        violations.append("No screenshot inventory report found under scripts/benchmarks/runs/*/*/screenshot-pack/*.")
    else:
        try:
            report_payload = json.loads(latest_report_path.read_text(encoding="utf-8"))
        except Exception as exc:
            violations.append(f"Failed to parse latest screenshot inventory report {latest_report_path}: {exc}")
            report_payload = {}

        if isinstance(report_payload, dict):
            missing_ids = report_payload.get("missing_ids")
            if not isinstance(missing_ids, list):
                violations.append(f"Latest screenshot inventory report is missing list field 'missing_ids': {latest_report_path}")
            elif missing_ids:
                violations.append(
                    "Latest screenshot inventory report has missing required IDs: "
                    f"{', '.join(str(item) for item in missing_ids)} ({latest_report_path})"
                )
            entry_rows = report_payload.get("entries")
            if isinstance(entry_rows, list):
                status_by_id = {
                    str(row.get("id")): str(row.get("status"))
                    for row in entry_rows
                    if isinstance(row, dict) and row.get("id") is not None
                }
                for shot_id, _filename in entries:
                    if status_by_id.get(shot_id) != "PASS":
                        violations.append(
                            f"Latest screenshot report does not mark {shot_id} as PASS: {latest_report_path}"
                        )
            else:
                violations.append(f"Latest screenshot inventory report is missing list field 'entries': {latest_report_path}")

    if violations:
        raise DevctlError("CONFIG_ERROR", "\n".join(sorted(violations)))

    print("Screenshot inventory check passed.")


def evidence_check(evidence_file: str, repo_root: Path = REPO_ROOT) -> None:
    if not evidence_file:
        raise DevctlError("CONFIG_ERROR", "Usage: evidence-check <evidence-markdown-file>")

    path = repo_root / evidence_file
    if not path.exists():
        raise DevctlError("CONFIG_ERROR", f"Evidence file not found: {evidence_file}")

    text = path.read_text(encoding="utf-8")
    run_paths = sorted(set(re.findall(RUN_PATH_REGEX, text)))
    if not run_paths:
        raise DevctlError("CONFIG_ERROR", f"No raw run artifact paths referenced in {evidence_file}")

    missing = [run_path for run_path in run_paths if not (repo_root / run_path).exists()]
    if missing:
        raise DevctlError("CONFIG_ERROR", "\n".join(f"Missing referenced artifact path: {item}" for item in missing))

    print(f"Evidence check passed: {evidence_file}")


def evidence_check_changed(event_name: str | None = None, base_ref: str | None = None, repo_root: Path = REPO_ROOT) -> None:
    event = event_name or os.environ.get("GITHUB_EVENT_NAME", "")
    base = base_ref or os.environ.get("GITHUB_BASE_REF", "")

    if event != "pull_request":
        print("Evidence changed-file check skipped: not a pull_request event.")
        return

    if not base:
        print("Evidence changed-file check skipped: GITHUB_BASE_REF is unset.")
        return

    run_subprocess(["git", "fetch", "--no-tags", "--depth=1", "origin", base], check=False, cwd=repo_root)
    diff_result = run_subprocess(
        ["git", "diff", "--name-only", f"origin/{base}...HEAD"],
        check=False,
        capture_output=True,
        cwd=repo_root,
    )

    changed = [
        line.strip()
        for line in (diff_result.stdout or "").splitlines()
        if re.match(r"^docs/operations/evidence/.+\.md$", line.strip())
    ]

    if not changed:
        print("No changed evidence markdown files detected.")
        return

    failures: list[str] = []
    for rel in changed:
        file_path = repo_root / rel
        if not file_path.exists():
            print(f"Changed evidence file not present in checkout (possibly deleted): {rel}")
            continue

        text = file_path.read_text(encoding="utf-8")
        if re.search(RUN_PATH_REGEX, text):
            try:
                evidence_check(rel, repo_root=repo_root)
            except DevctlError as exc:
                failures.append(exc.message)
        else:
            print(f"No raw run artifact references in {rel}; skipping path validation.")

    if failures:
        raise DevctlError("CONFIG_ERROR", "\n".join(failures))


def validate_pr_body(pr_body_file: str, repo_root: Path = REPO_ROOT) -> None:
    if not pr_body_file:
        raise DevctlError("CONFIG_ERROR", "Usage: validate-pr-body <pr-body-file>")

    path = repo_root / pr_body_file
    text = _read_file(path)

    for pattern, message in PR_REQUIRED_PATTERNS:
        if not re.search(pattern, text):
            raise DevctlError("CONFIG_ERROR", f"PR template requirement failed: {message}")

    if re.search(r"(?i)wp-|work package|stage", text):
        if not re.search(STAGE_EVIDENCE_PATTERN, text):
            raise DevctlError("CONFIG_ERROR", "PR template requirement failed: stage/work-package evidence checkbox must be checked")
        if "docs/operations/evidence/" not in text:
            raise DevctlError("CONFIG_ERROR", "Stage/work-package PR must include evidence note link.")

    print("PR body validation passed.")


def stage_close_gate(pr_body_file: str, repo_root: Path = REPO_ROOT) -> None:
    if not pr_body_file:
        raise DevctlError("CONFIG_ERROR", "Usage: stage-close-gate <pr-body-file>")

    path = repo_root / pr_body_file
    text = _read_file(path)

    if not re.search(r"(?i)stage close:\s*yes", text):
        print("Stage close gate not requested; skipping.")
        return

    evidence_match = re.search(r"docs/operations/evidence/wp-[0-9]{2}/[0-9]{4}-[0-9]{2}-[0-9]{2}[^ )]*\.md", text)
    if not evidence_match:
        raise DevctlError("CONFIG_ERROR", "Stage-close PR must link a WP evidence markdown file.")

    evidence_path = evidence_match.group(0)
    if not (repo_root / evidence_path).exists():
        raise DevctlError("CONFIG_ERROR", f"Linked evidence file does not exist in repo: {evidence_path}")

    evidence_check(evidence_path, repo_root=repo_root)
    print("Stage-close evidence gate passed.")


def governance_self_test(repo_root: Path = REPO_ROOT) -> None:
    with tempfile.TemporaryDirectory(prefix="devctl-gov-") as tmp:
        sandbox = Path(tmp)
        date_dir = datetime.now().strftime("%Y-%m-%d")
        run_rel = Path("scripts/benchmarks/runs") / date_dir / "SELFTEST" / "governance-self-test"
        run_dir = sandbox / run_rel
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "artifact.txt").write_text("selftest\n", encoding="utf-8")

        evidence_note_rel = Path("docs/operations/evidence/wp-03/2026-03-03-selftest.md")
        evidence_note = sandbox / evidence_note_rel
        evidence_note.parent.mkdir(parents=True, exist_ok=True)
        evidence_note.write_text(
            "Evidence note\n"
            f"- raw path: {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )

        evidence_ok = sandbox / "evidence-ok.md"
        evidence_bad = sandbox / "evidence-bad.md"
        pr_ok = sandbox / "pr-ok.md"
        pr_bad = sandbox / "pr-bad.md"
        pr_stage_close = sandbox / "pr-stage-close.md"

        evidence_ok.write_text(
            "Evidence note\n"
            f"- raw path: {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )
        evidence_bad.write_text(
            "Evidence note\n"
            f"- raw path: {run_rel}/missing.txt\n",
            encoding="utf-8",
        )

        evidence_check(str(evidence_ok), repo_root=sandbox)

        try:
            evidence_check(str(evidence_bad), repo_root=sandbox)
        except DevctlError:
            pass
        else:
            raise DevctlError("CONFIG_ERROR", "Expected evidence-check failure did not occur")

        pr_ok.write_text(
            "- [x] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n"
            "- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.\n"
            "- [x] I updated docs affected by this change, or confirmed no docs changes are needed.\n"
            "- [x] I ran `python3 tools/devctl/main.py governance docs-health` and it passed.\n"
            "- [x] For UI-touching changes, I ran `python3 tools/devctl/main.py lane screenshot-pack` and manually reviewed screenshots (or documented why not needed).\n"
            "- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.\n\n"
            "Stage close: no\n\n"
            f"Evidence note(s): {evidence_note_rel}\n",
            encoding="utf-8",
        )
        validate_pr_body(str(pr_ok), repo_root=sandbox)
        stage_close_gate(str(pr_ok), repo_root=sandbox)

        pr_bad.write_text(
            "- [ ] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n"
            "- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.\n"
            "- [x] I updated docs affected by this change, or confirmed no docs changes are needed.\n",
            encoding="utf-8",
        )
        try:
            validate_pr_body(str(pr_bad), repo_root=sandbox)
        except DevctlError:
            pass
        else:
            raise DevctlError("CONFIG_ERROR", "Expected validate-pr-body failure did not occur")

        pr_stage_close.write_text(
            "- [x] I ran `bash scripts/dev/test.sh` (or `bash scripts/dev/test.sh ci`) and it passed.\n"
            "- [x] I used canonical orchestrator lanes (`python3 tools/devctl/main.py lane ...`) directly or via the `scripts/dev/*` wrappers.\n"
            "- [x] I updated docs affected by this change, or confirmed no docs changes are needed.\n"
            "- [x] I ran `python3 tools/devctl/main.py governance docs-health` and it passed.\n"
            "- [x] For UI-touching changes, I ran `python3 tools/devctl/main.py lane screenshot-pack` and manually reviewed screenshots (or documented why not needed).\n"
            "- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.\n\n"
            "Stage close: yes\n\n"
            f"Evidence note(s): {evidence_note_rel}\n"
            f"Raw run artifacts (`scripts/benchmarks/runs/...`): {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )
        validate_pr_body(str(pr_stage_close), repo_root=sandbox)
        stage_close_gate(str(pr_stage_close), repo_root=sandbox)

    print("Governance self-test passed.")
