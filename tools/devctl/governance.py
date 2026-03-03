from __future__ import annotations

import os
import re
import tempfile
from datetime import datetime
from pathlib import Path

from tools.devctl.subprocess_utils import DevctlError, REPO_ROOT, run_subprocess

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
]

STAGE_EVIDENCE_PATTERN = (
    r"- \[x\] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` "
    r"and linked it below\."
)

RUN_PATH_REGEX = r"scripts/benchmarks/runs/[^\s)`\"]+"


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
            "- [x] If this is stage/work-package work, I added/updated evidence under `docs/operations/evidence/` and linked it below.\n\n"
            "Stage close: yes\n\n"
            f"Evidence note(s): {evidence_note_rel}\n"
            f"Raw run artifacts (`scripts/benchmarks/runs/...`): {run_rel}/artifact.txt\n",
            encoding="utf-8",
        )
        validate_pr_body(str(pr_stage_close), repo_root=sandbox)
        stage_close_gate(str(pr_stage_close), repo_root=sandbox)

    print("Governance self-test passed.")
