#!/usr/bin/env python3
import argparse
import csv
import json
import math
from pathlib import Path

from benchmark_parity import build_parity_report, load_csv_rows, load_pocketpal_rows, median, normalize_pocket_gpt_rows, parse_float


SCENARIO_FILE_MAP = {
    "A": "scenario-a.csv",
    "B": "scenario-b.csv",
}


def summarize_rows(rows):
    if not rows:
        return None
    crash_count = 0
    metrics = {
        "first_token_ms": [],
        "prefill_ms": [],
        "model_load_ms": [],
        "decode_tps": [],
        "prefix_cache_hit_rate": [],
        "prefix_cache_reused_tokens": [],
        "resident_hit_count": [],
        "peak_rss_mb": [],
    }
    for row in rows:
        for key in metrics:
            metrics[key].append(parse_float(row.get(key)))
        crash = (row.get("crash_or_oom") or "").strip().lower()
        if crash in {"true", "1", "yes"}:
            crash_count += 1
    summary = {
        "rows": len(rows),
        "crash_count": crash_count,
    }
    for key, values in metrics.items():
        summary[key] = median(values)
    return summary


def sort_key(summary):
    crash_penalty = 1 if summary["crash_count"] else 0
    parity_penalty = 1 if summary.get("parity_overall") == "FAIL" else 0
    first_token_ms = summary.get("first_token_ms")
    decode_tps = summary.get("decode_tps")
    prefix_cache_hit_rate = summary.get("prefix_cache_hit_rate")
    prefill_ms = summary.get("prefill_ms")
    return (
        crash_penalty,
        parity_penalty,
        math.inf if first_token_ms is None else first_token_ms,
        -(decode_tps if decode_tps is not None else -1),
        -(prefix_cache_hit_rate if prefix_cache_hit_rate is not None else -1),
        math.inf if prefill_ms is None else prefill_ms,
    )


def summarize_variant(variant_dir, scenario, pocketpal_rows, min_tps_ratio, max_first_token_ratio):
    scenario_file = variant_dir / SCENARIO_FILE_MAP[scenario]
    if not scenario_file.exists():
        return None
    raw_rows = load_csv_rows(scenario_file)
    gpt_rows = normalize_pocket_gpt_rows(raw_rows, scenario)
    if not gpt_rows:
        return None
    summary = summarize_rows(raw_rows)
    summary["variant"] = variant_dir.name
    summary["scenario_file"] = str(scenario_file)
    if pocketpal_rows:
        parity = build_parity_report(
            gpt_rows=gpt_rows,
            pal_rows=pocketpal_rows,
            min_tps_ratio=min_tps_ratio,
            max_first_token_ratio=max_first_token_ratio,
        )
        summary["parity_overall"] = parity["overall"]
        summary["parity_decode_ratio"] = parity["ratios"]["decode_tps_ratio_gpt_over_pal"]
        summary["parity_first_token_ratio"] = parity["ratios"]["first_token_ratio_gpt_over_pal"]
        summary["parity_checks"] = parity["checks"]
    else:
        summary["parity_overall"] = ""
        summary["parity_decode_ratio"] = None
        summary["parity_first_token_ratio"] = None
        summary["parity_checks"] = []
    return summary


def write_csv(path, summaries):
    fieldnames = [
        "variant",
        "rows",
        "crash_count",
        "first_token_ms",
        "prefill_ms",
        "model_load_ms",
        "decode_tps",
        "prefix_cache_hit_rate",
        "prefix_cache_reused_tokens",
        "resident_hit_count",
        "peak_rss_mb",
        "parity_overall",
        "parity_decode_ratio",
        "parity_first_token_ratio",
        "scenario_file",
    ]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for summary in summaries:
            writer.writerow({key: summary.get(key) for key in fieldnames})


def write_markdown(path, summaries, scenario, pocketpal_enabled):
    lines = [
        f"# Stage-2 CPU Sweep Summary ({scenario})",
        "",
    ]
    if summaries:
        best = summaries[0]
        lines.extend(
            [
                f"Best variant: `{best['variant']}`",
                "",
                "| Variant | First token ms | Prefill ms | Decode tps | Prefix hit rate | Peak RSS MB | Parity |",
                "| --- | ---: | ---: | ---: | ---: | ---: | --- |",
            ],
        )
        for summary in summaries:
            lines.append(
                "| {variant} | {first_token_ms} | {prefill_ms} | {decode_tps} | {prefix_cache_hit_rate} | {peak_rss_mb} | {parity_overall} |".format(
                    variant=summary["variant"],
                    first_token_ms=format_metric(summary.get("first_token_ms"), 1),
                    prefill_ms=format_metric(summary.get("prefill_ms"), 1),
                    decode_tps=format_metric(summary.get("decode_tps"), 3),
                    prefix_cache_hit_rate=format_metric(summary.get("prefix_cache_hit_rate"), 3),
                    peak_rss_mb=format_metric(summary.get("peak_rss_mb"), 1),
                    parity_overall=summary.get("parity_overall") or "n/a",
                ),
            )
    else:
        lines.append("No variant summaries were found.")

    if pocketpal_enabled:
        lines.extend(["", "PocketPal parity data was included in this ranking."])

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def format_metric(value, digits):
    if value is None:
        return ""
    return f"{value:.{digits}f}"


def parse_args():
    parser = argparse.ArgumentParser(description="Rank stage-2 sweep variants by latency and throughput.")
    parser.add_argument("--runs-root", required=True, help="Directory containing per-variant run subdirectories.")
    parser.add_argument("--scenario", default="A", help="Scenario label to summarize (A or B).")
    parser.add_argument("--pocketpal", default="", help="Optional PocketPal raw benchmark JSON/CSV export.")
    parser.add_argument("--min-tps-ratio", type=float, default=0.80, help="Minimum Pocket-GPT/PocketPal decode ratio.")
    parser.add_argument("--max-first-token-ratio", type=float, default=1.25, help="Maximum Pocket-GPT/PocketPal TTFT ratio.")
    parser.add_argument("--out-json", default="", help="Optional JSON output path.")
    parser.add_argument("--out-csv", default="", help="Optional CSV output path.")
    parser.add_argument("--out-md", default="", help="Optional Markdown output path.")
    return parser.parse_args()


def main():
    args = parse_args()
    scenario = args.scenario.strip().upper()
    if scenario not in SCENARIO_FILE_MAP:
        raise SystemExit("--scenario must be A or B")

    runs_root = Path(args.runs_root)
    if not runs_root.exists():
        raise SystemExit(f"Runs root does not exist: {runs_root}")

    pocketpal_rows = load_pocketpal_rows(args.pocketpal, scenario) if args.pocketpal else []

    summaries = []
    for variant_dir in sorted(path for path in runs_root.iterdir() if path.is_dir()):
        summary = summarize_variant(
            variant_dir=variant_dir,
            scenario=scenario,
            pocketpal_rows=pocketpal_rows,
            min_tps_ratio=args.min_tps_ratio,
            max_first_token_ratio=args.max_first_token_ratio,
        )
        if summary:
            summaries.append(summary)

    summaries.sort(key=sort_key)

    payload = {
        "scenario": scenario,
        "variant_count": len(summaries),
        "best_variant": summaries[0]["variant"] if summaries else None,
        "summaries": summaries,
    }

    if args.out_json:
        out_path = Path(args.out_json)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    if args.out_csv:
        out_path = Path(args.out_csv)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        write_csv(out_path, summaries)

    if args.out_md:
        out_path = Path(args.out_md)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        write_markdown(out_path, summaries, scenario, bool(pocketpal_rows))

    if summaries:
        best = summaries[0]
        print(f"Best variant: {best['variant']}")
        print(f"  first_token_ms={format_metric(best.get('first_token_ms'), 1)}")
        print(f"  decode_tps={format_metric(best.get('decode_tps'), 3)}")
        print(f"  prefix_cache_hit_rate={format_metric(best.get('prefix_cache_hit_rate'), 3)}")
        if best.get("parity_overall"):
            print(f"  parity={best['parity_overall']}")
    else:
        print("No stage-2 scenario files found.")

    return 0 if summaries else 1


if __name__ == "__main__":
    raise SystemExit(main())
