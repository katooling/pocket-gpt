#!/usr/bin/env python3
import argparse
import csv
import json
import statistics
import sys
from pathlib import Path


def parse_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def median(values):
    numeric = [v for v in values if v is not None]
    if not numeric:
        return None
    return statistics.median(numeric)


def load_csv_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        return list(reader)


def normalize_pocket_gpt_rows(rows, scenario):
    normalized = []
    for row in rows:
        row_scenario = (row.get("scenario") or "").strip().upper()
        if row_scenario and scenario and row_scenario != scenario:
            continue
        normalized.append(
            {
                "decode_tps": parse_float(row.get("decode_tps")),
                "first_token_ms": parse_float(row.get("first_token_ms")),
                "scenario": row_scenario or None,
                "source": "pocket-gpt",
            },
        )
    return normalized


def _candidate_value(record, keys):
    for key in keys:
        if key in record:
            return record.get(key)
    return None


def normalize_pocketpal_records(records, scenario):
    normalized = []
    for record in records:
        if not isinstance(record, dict):
            continue
        benchmark = record.get("benchmark") if isinstance(record.get("benchmark"), dict) else record
        row_scenario = str(
            _candidate_value(benchmark, ("scenario", "scenario_id", "scenarioId")) or "",
        ).strip().upper()
        if row_scenario and scenario and row_scenario != scenario:
            continue
        normalized.append(
            {
                "decode_tps": parse_float(
                    _candidate_value(
                        benchmark,
                        (
                            "decode_tps",
                            "tgAvg",
                            "token_generation_tps",
                            "tokenGenerationTps",
                            "tokensPerSecond",
                        ),
                    ),
                ),
                "first_token_ms": parse_float(
                    _candidate_value(
                        benchmark,
                        (
                            "first_token_ms",
                            "firstTokenMs",
                            "ttft_ms",
                            "timeToFirstTokenMs",
                        ),
                    ),
                ),
                "scenario": row_scenario or None,
                "source": "pocketpal",
            },
        )
    return normalized


def load_pocketpal_rows(path, scenario):
    source = Path(path)
    suffix = source.suffix.lower()
    if suffix == ".csv":
        return normalize_pocketpal_records(load_csv_rows(path), scenario)
    if suffix != ".json":
        raise ValueError(f"Unsupported PocketPal input format: {path} (use .json or .csv)")
    with open(path, encoding="utf-8") as handle:
        payload = json.load(handle)
    if isinstance(payload, list):
        records = payload
    elif isinstance(payload, dict):
        records = [payload]
    else:
        raise ValueError("PocketPal JSON must be an object or array.")
    return normalize_pocketpal_records(records, scenario)


def build_report(gpt_rows, pal_rows, min_tps_ratio, max_first_token_ratio):
    gpt_decode = median([r["decode_tps"] for r in gpt_rows])
    pal_decode = median([r["decode_tps"] for r in pal_rows])
    gpt_first = median([r["first_token_ms"] for r in gpt_rows])
    pal_first = median([r["first_token_ms"] for r in pal_rows])

    decode_ratio = None
    if gpt_decode is not None and pal_decode not in (None, 0):
        decode_ratio = gpt_decode / pal_decode

    first_token_ratio = None
    if gpt_first is not None and pal_first not in (None, 0):
        first_token_ratio = gpt_first / pal_first

    checks = []
    if decode_ratio is None:
        checks.append(
            {
                "name": "decode_tps_ratio",
                "status": "FAIL",
                "detail": "Missing decode_tps for one or both datasets.",
            },
        )
    else:
        checks.append(
            {
                "name": "decode_tps_ratio",
                "status": "PASS" if decode_ratio >= min_tps_ratio else "FAIL",
                "detail": f"ratio={decode_ratio:.3f}, required>={min_tps_ratio:.3f}",
            },
        )

    if first_token_ratio is None:
        checks.append(
            {
                "name": "first_token_ratio",
                "status": "SKIP",
                "detail": "PocketPal export did not include first-token latency; check skipped.",
            },
        )
    else:
        checks.append(
            {
                "name": "first_token_ratio",
                "status": "PASS" if first_token_ratio <= max_first_token_ratio else "FAIL",
                "detail": f"ratio={first_token_ratio:.3f}, required<={max_first_token_ratio:.3f}",
            },
        )

    overall = "PASS"
    for check in checks:
        if check["status"] == "FAIL":
            overall = "FAIL"
            break

    return {
        "overall": overall,
        "pocket_gpt": {
            "rows": len(gpt_rows),
            "median_decode_tps": gpt_decode,
            "median_first_token_ms": gpt_first,
        },
        "pocketpal": {
            "rows": len(pal_rows),
            "median_decode_tps": pal_decode,
            "median_first_token_ms": pal_first,
        },
        "ratios": {
            "decode_tps_ratio_gpt_over_pal": decode_ratio,
            "first_token_ratio_gpt_over_pal": first_token_ratio,
        },
        "checks": checks,
    }


def print_report(report):
    print("Pocket-GPT vs PocketPal Parity")
    print("==============================")
    print(f"Overall: {report['overall']}")
    print("")
    print("Pocket-GPT")
    print(f"- rows: {report['pocket_gpt']['rows']}")
    print(f"- median decode_tps: {report['pocket_gpt']['median_decode_tps']}")
    print(f"- median first_token_ms: {report['pocket_gpt']['median_first_token_ms']}")
    print("")
    print("PocketPal")
    print(f"- rows: {report['pocketpal']['rows']}")
    print(f"- median decode_tps: {report['pocketpal']['median_decode_tps']}")
    print(f"- median first_token_ms: {report['pocketpal']['median_first_token_ms']}")
    print("")
    print("Checks")
    for check in report["checks"]:
        print(f"- {check['name']}: {check['status']} ({check['detail']})")


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Compare Pocket-GPT benchmark output against PocketPal benchmark output.",
    )
    parser.add_argument("--pocket-gpt", required=True, help="Pocket-GPT metrics CSV (e.g., scenario-a.csv).")
    parser.add_argument(
        "--pocketpal",
        required=True,
        help="PocketPal benchmark JSON/CSV (share raw JSON or normalized CSV).",
    )
    parser.add_argument(
        "--scenario",
        default="A",
        help="Scenario label to filter by when scenario column exists (default: A).",
    )
    parser.add_argument(
        "--min-tps-ratio",
        type=float,
        default=0.80,
        help="Minimum required decode ratio: Pocket-GPT / PocketPal (default: 0.80).",
    )
    parser.add_argument(
        "--max-first-token-ratio",
        type=float,
        default=1.25,
        help="Maximum allowed first-token ratio: Pocket-GPT / PocketPal (default: 1.25).",
    )
    parser.add_argument(
        "--out-json",
        default="",
        help="Optional path to write full JSON report.",
    )
    return parser.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    scenario = args.scenario.strip().upper() if args.scenario else ""

    gpt_rows = normalize_pocket_gpt_rows(load_csv_rows(args.pocket_gpt), scenario)
    if not gpt_rows:
        print("No Pocket-GPT rows matched the selected scenario.", file=sys.stderr)
        return 1

    pal_rows = load_pocketpal_rows(args.pocketpal, scenario)
    if not pal_rows:
        print("No PocketPal rows matched the selected scenario.", file=sys.stderr)
        return 1

    report = build_report(
        gpt_rows=gpt_rows,
        pal_rows=pal_rows,
        min_tps_ratio=args.min_tps_ratio,
        max_first_token_ratio=args.max_first_token_ratio,
    )
    print_report(report)

    if args.out_json:
        out_path = Path(args.out_json)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"\nWrote report JSON to {out_path}")

    return 0 if report["overall"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
