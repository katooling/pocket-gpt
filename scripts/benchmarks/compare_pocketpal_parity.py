#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path

from benchmark_parity import build_parity_report, load_csv_rows, load_pocketpal_rows, normalize_pocket_gpt_rows


def print_report(report):
    print("Pocket-GPT vs PocketPal Parity")
    print("==============================")
    print(f"Overall: {report['overall']}")
    print("")
    print("Pocket-GPT")
    print(f"- rows: {report['pocket_gpt']['rows']}")
    print(f"- median decode_tps: {report['pocket_gpt']['median_decode_tps']}")
    print(f"- median first_token_ms: {report['pocket_gpt']['median_first_token_ms']}")
    print(f"- median prefill_ms: {report['pocket_gpt']['median_prefill_ms']}")
    print(f"- median model_load_ms: {report['pocket_gpt']['median_model_load_ms']}")
    print("")
    print("PocketPal")
    print(f"- rows: {report['pocketpal']['rows']}")
    print(f"- median decode_tps: {report['pocketpal']['median_decode_tps']}")
    print(f"- median first_token_ms: {report['pocketpal']['median_first_token_ms']}")
    print(f"- median prefill_ms: {report['pocketpal']['median_prefill_ms']}")
    print(f"- median model_load_ms: {report['pocketpal']['median_model_load_ms']}")
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


def build_report(gpt_rows, pal_rows, min_tps_ratio, max_first_token_ratio):
    return build_parity_report(
        gpt_rows=gpt_rows,
        pal_rows=pal_rows,
        min_tps_ratio=min_tps_ratio,
        max_first_token_ratio=max_first_token_ratio,
    )


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
