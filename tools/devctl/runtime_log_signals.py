from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

_SIGNAL_RE = re.compile(r"\b(MMAP|FLASH_ATTN|SPECULATIVE|PREFIX_CACHE)\|(.+)$")
_SEVERITY_ORDER = {"info": 0, "warning": 1, "error": 2}


def _parse_signal_fields(payload: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    flags: list[str] = []
    for part in payload.split("|"):
        token = part.strip()
        if not token:
            continue
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key.strip()] = value.strip()
        else:
            flags.append(token)
    if flags:
        fields["_flags"] = ",".join(flags)
    return fields


def _read_logcat_text(logcat_path: Path) -> str:
    return logcat_path.read_text(encoding="utf-8", errors="replace")


def _issue(kind: str, severity: str, message: str, evidence: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "kind": kind,
        "severity": severity,
        "message": message,
        "evidence": evidence or {},
    }


def analyze_runtime_log_text(text: str) -> dict[str, Any]:
    mmap_entries: list[dict[str, str]] = []
    flash_entries: list[dict[str, str]] = []
    speculative_entries: list[dict[str, str]] = []
    prefix_cache_entries: list[dict[str, str]] = []
    for raw_line in text.splitlines():
        match = _SIGNAL_RE.search(raw_line)
        if not match:
            continue
        signal_type = match.group(1)
        fields = _parse_signal_fields(match.group(2))
        fields["raw_line"] = raw_line.strip()
        if signal_type == "MMAP":
            mmap_entries.append(fields)
        elif signal_type == "FLASH_ATTN":
            flash_entries.append(fields)
        elif signal_type == "SPECULATIVE":
            speculative_entries.append(fields)
        elif signal_type == "PREFIX_CACHE":
            prefix_cache_entries.append(fields)

    issues: list[dict[str, Any]] = []

    mmap_config = next((entry for entry in reversed(mmap_entries) if "use_mmap" in entry), None)
    readahead_entries = [entry for entry in mmap_entries if entry.get("stage") == "readahead"]
    readahead_failures = [entry for entry in readahead_entries if entry.get("result") not in {None, "0"}]
    if not mmap_entries:
        issues.append(_issue("missing_mmap_diagnostics", "warning", "No MMAP| diagnostics were found in logcat."))
    else:
        if mmap_config is None:
            issues.append(_issue("missing_mmap_config", "warning", "MMAP diagnostics were present, but the model mmap configuration line was missing."))
        elif mmap_config.get("use_mmap") != "true":
            issues.append(
                _issue(
                    "mmap_disabled",
                    "warning",
                    "Model loading did not run with mmap enabled.",
                    {"use_mmap": mmap_config.get("use_mmap")},
                ),
            )
        if not readahead_entries:
            issues.append(_issue("missing_readahead", "warning", "No mmap readahead signal was captured."))
        for entry in readahead_failures:
            issues.append(
                _issue(
                    "readahead_failed",
                    "error",
                    "Model mmap readahead failed.",
                    {"label": entry.get("label"), "result": entry.get("result")},
                ),
            )

    flash_config = flash_entries[-1] if flash_entries else None
    if not flash_entries:
        issues.append(_issue("missing_flash_attn_diagnostics", "warning", "No FLASH_ATTN| diagnostics were found in logcat."))
    else:
        if flash_config is not None and flash_config.get("requested") != "true":
            issues.append(
                _issue(
                    "flash_attention_not_requested",
                    "warning",
                    "Flash attention was not requested for the active runtime config.",
                    {"requested": flash_config.get("requested")},
                ),
            )
        if flash_config is not None and flash_config.get("requested") == "true" and flash_config.get("gpu_ops") != "true":
            issues.append(
                _issue(
                    "flash_attention_without_gpu_ops",
                    "warning",
                    "Flash attention was requested but GPU ops were disabled.",
                    {"gpu_ops": flash_config.get("gpu_ops")},
                ),
            )

    speculative_metric_entries: list[dict[str, str]] = []
    speculative_disabled_entries: list[dict[str, str]] = []
    accepted_total = 0
    drafted_total = 0
    for entry in speculative_entries:
        flags = set(filter(None, entry.get("_flags", "").split(",")))
        if "disabled" in flags:
            speculative_disabled_entries.append(entry)
        if "accepted" in entry and "drafted" in entry:
            speculative_metric_entries.append(entry)
            try:
                accepted_total += int(entry.get("accepted", "0"))
                drafted_total += int(entry.get("drafted", "0"))
            except ValueError:
                continue
    aggregate_acceptance = (accepted_total / drafted_total) if drafted_total > 0 else None
    if speculative_disabled_entries:
        disabled_entry = speculative_disabled_entries[-1]
        issues.append(
            _issue(
                "speculative_disabled",
                "warning",
                "Speculative decoding was disabled during runtime initialization.",
                {"reason": disabled_entry.get("reason") or disabled_entry.get("_flags", "")},
            ),
        )
    if aggregate_acceptance is not None and drafted_total >= 16 and aggregate_acceptance < 0.4:
        issues.append(
            _issue(
                "low_speculative_acceptance",
                "warning",
                "Speculative decoding acceptance rate is low enough that it may regress throughput.",
                {
                    "accepted_total": accepted_total,
                    "drafted_total": drafted_total,
                    "acceptance_rate": round(aggregate_acceptance, 3),
                },
            ),
        )

    prefix_target_entries = [entry for entry in prefix_cache_entries if entry.get("stage") == "target"]
    prefix_draft_entries = [entry for entry in prefix_cache_entries if entry.get("stage") == "draft"]
    prefix_store_state_entries = [entry for entry in prefix_cache_entries if entry.get("stage") == "store_state"]
    prefix_restore_state_entries = [entry for entry in prefix_cache_entries if entry.get("stage") == "restore_state"]
    prefix_target_hits = sum(1 for entry in prefix_target_entries if entry.get("hit") == "true")
    prefix_draft_hits = sum(1 for entry in prefix_draft_entries if entry.get("hit") == "true")
    prefix_store_success = sum(1 for entry in prefix_store_state_entries if entry.get("success") == "true")
    prefix_restore_success = sum(1 for entry in prefix_restore_state_entries if entry.get("success") == "true")
    prefix_restore_failures = [entry for entry in prefix_restore_state_entries if entry.get("success") == "false"]
    prefix_restore_over_budget = [entry for entry in prefix_restore_failures if entry.get("reason") == "over_budget"]
    prefix_restore_empty = [entry for entry in prefix_restore_failures if entry.get("reason") == "empty"]
    prefix_restore_other_failures = [
        entry for entry in prefix_restore_failures if entry.get("reason") not in {"over_budget", "empty"}
    ]
    for entry in prefix_restore_over_budget:
        issues.append(
            _issue(
                "prefix_restore_over_budget",
                "warning",
                "Prefix-cache state restore was skipped because the snapshot exceeded budget.",
                {"slot": entry.get("slot"), "bytes": entry.get("bytes"), "reason": entry.get("reason")},
            ),
        )
    for entry in prefix_restore_empty:
        issues.append(
            _issue(
                "prefix_restore_missing_state",
                "warning",
                "Prefix-cache restore was attempted but no saved state was available for the slot.",
                {"slot": entry.get("slot"), "reason": entry.get("reason")},
            ),
        )
    for entry in prefix_restore_other_failures:
        issues.append(
            _issue(
                "prefix_restore_failed",
                "error",
                "Prefix-cache restore failed for a non-retryable reason.",
                {"slot": entry.get("slot"), "reason": entry.get("reason")},
            ),
        )

    highest_severity = "info"
    for issue in issues:
        if _SEVERITY_ORDER[issue["severity"]] > _SEVERITY_ORDER[highest_severity]:
            highest_severity = issue["severity"]
    status = "pass" if not issues else ("fail" if highest_severity == "error" else "warn")

    return {
        "status": status,
        "issue_count": len(issues),
        "issues": issues,
        "mmap": {
            "present": bool(mmap_entries),
            "entry_count": len(mmap_entries),
            "config": mmap_config,
            "readahead_entry_count": len(readahead_entries),
            "readahead_failure_count": len(readahead_failures),
        },
        "flash_attention": {
            "present": bool(flash_entries),
            "entry_count": len(flash_entries),
            "latest": flash_config,
        },
        "speculative": {
            "present": bool(speculative_entries),
            "entry_count": len(speculative_entries),
            "metric_entry_count": len(speculative_metric_entries),
            "disabled_entry_count": len(speculative_disabled_entries),
            "accepted_total": accepted_total,
            "drafted_total": drafted_total,
            "aggregate_acceptance_rate": round(aggregate_acceptance, 3) if aggregate_acceptance is not None else None,
            "latest": speculative_entries[-1] if speculative_entries else None,
        },
        "prefix_cache": {
            "present": bool(prefix_cache_entries),
            "entry_count": len(prefix_cache_entries),
            "target_entry_count": len(prefix_target_entries),
            "draft_entry_count": len(prefix_draft_entries),
            "store_state_entry_count": len(prefix_store_state_entries),
            "restore_state_entry_count": len(prefix_restore_state_entries),
            "target_hit_count": prefix_target_hits,
            "draft_hit_count": prefix_draft_hits,
            "store_state_success_count": prefix_store_success,
            "restore_state_success_count": prefix_restore_success,
            "restore_state_failure_count": len(prefix_restore_failures),
            "restore_state_over_budget_count": len(prefix_restore_over_budget),
            "restore_state_empty_count": len(prefix_restore_empty),
            "latest": prefix_cache_entries[-1] if prefix_cache_entries else None,
        },
    }


def render_runtime_log_signal_markdown(logcat_path: Path, analysis: dict[str, Any]) -> str:
    lines = [
        "# Runtime Log Signals",
        "",
        f"- Logcat: `{logcat_path}`",
        f"- Status: `{analysis['status']}`",
        f"- Issue count: `{analysis['issue_count']}`",
        "",
        "## Summary",
        "",
        f"- MMAP entries: `{analysis['mmap']['entry_count']}`; readahead failures: `{analysis['mmap']['readahead_failure_count']}`",
        f"- FLASH_ATTN entries: `{analysis['flash_attention']['entry_count']}`",
        f"- SPECULATIVE entries: `{analysis['speculative']['entry_count']}`; aggregate acceptance: `{analysis['speculative']['aggregate_acceptance_rate'] if analysis['speculative']['aggregate_acceptance_rate'] is not None else '-'}`",
        f"- PREFIX_CACHE entries: `{analysis['prefix_cache']['entry_count']}`; restore failures: `{analysis['prefix_cache']['restore_state_failure_count']}`",
        "",
        "## Findings",
        "",
    ]
    if not analysis["issues"]:
        lines.append("- No runtime log signal issues were detected.")
    else:
        for issue in analysis["issues"]:
            lines.append(f"- [{issue['severity']}] {issue['kind']}: {issue['message']}")
            if issue["evidence"]:
                evidence = ", ".join(f"{key}={value}" for key, value in issue["evidence"].items())
                lines.append(f"  Evidence: `{evidence}`")
    lines.extend([
        "",
        "## Latest Signals",
        "",
        f"- MMAP latest: `{json.dumps(analysis['mmap']['config'] or {}, sort_keys=True)}`",
        f"- FLASH_ATTN latest: `{json.dumps(analysis['flash_attention']['latest'] or {}, sort_keys=True)}`",
        f"- SPECULATIVE latest: `{json.dumps(analysis['speculative']['latest'] or {}, sort_keys=True)}`",
        f"- PREFIX_CACHE latest: `{json.dumps(analysis['prefix_cache']['latest'] or {}, sort_keys=True)}`",
        "",
    ])
    return "\n".join(lines)


def write_runtime_log_signal_reports(
    logcat_path: Path,
    json_output_path: Path,
    markdown_output_path: Path,
) -> dict[str, Any]:
    analysis = analyze_runtime_log_text(_read_logcat_text(logcat_path))
    json_output_path.parent.mkdir(parents=True, exist_ok=True)
    markdown_output_path.parent.mkdir(parents=True, exist_ok=True)
    json_output_path.write_text(json.dumps(analysis, indent=2) + "\n", encoding="utf-8")
    markdown_output_path.write_text(render_runtime_log_signal_markdown(logcat_path, analysis), encoding="utf-8")
    return analysis
