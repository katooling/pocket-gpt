#!/usr/bin/env python3
import csv
import os
import statistics
import sys


REQUIRED_COLUMNS = {"scenario", "first_token_ms", "decode_tps"}


def median(values):
    if not values:
        return 0.0
    return statistics.median(values)


def parse_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def main(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = set(reader.fieldnames or [])
        missing_columns = sorted(REQUIRED_COLUMNS - fieldnames)
        if missing_columns:
            print("Schema Error")
            print("============")
            print(f"Missing required columns: {', '.join(missing_columns)}")
            return 1
        for row in reader:
            rows.append(row)

    scenario_a = [r for r in rows if r.get("scenario") == "A"]
    scenario_b = [r for r in rows if r.get("scenario") == "B"]
    scenario_c = [r for r in rows if r.get("scenario") == "C"]

    a_first = [parse_float(r.get("first_token_ms")) for r in scenario_a]
    a_tps = [parse_float(r.get("decode_tps")) for r in scenario_a]
    b_first = [parse_float(r.get("first_token_ms")) for r in scenario_b]
    b_tps = [parse_float(r.get("decode_tps")) for r in scenario_b]
    c_tps = [parse_float(r.get("decode_tps")) for r in scenario_c]

    a_first = [x for x in a_first if x is not None]
    a_tps = [x for x in a_tps if x is not None]
    b_first = [x for x in b_first if x is not None]
    b_tps = [x for x in b_tps if x is not None]
    c_tps = [x for x in c_tps if x is not None]

    if not scenario_a or not scenario_b:
        print("Data Error")
        print("==========")
        print("Benchmark CSV must include at least one row for Scenario A and Scenario B.")
        return 1

    if not a_first or not a_tps or not b_first or not b_tps:
        print("Data Error")
        print("==========")
        print("Scenario A/B rows must include numeric first_token_ms and decode_tps values.")
        return 1

    checks = []
    checks.append(("Scenario A first-token <= 2500ms", median(a_first) <= 2500))
    checks.append(("Scenario A decode >= 8 tok/s", median(a_tps) >= 8))
    checks.append(("Scenario B first-token <= 2500ms", median(b_first) <= 2500))
    checks.append(("Scenario B decode >= 4 tok/s", median(b_tps) >= 4))
    if c_tps:
        checks.append(("Scenario C decode >= 4 tok/s", median(c_tps) >= 4))

    print("Threshold Evaluation")
    print("====================")
    print(f"Scenario A median first-token: {median(a_first):.2f} ms")
    print(f"Scenario A median decode: {median(a_tps):.2f} tok/s")
    print(f"Scenario B median first-token: {median(b_first):.2f} ms")
    print(f"Scenario B median decode: {median(b_tps):.2f} tok/s")
    if c_tps:
        print(f"Scenario C median decode: {median(c_tps):.2f} tok/s")

    print("\nChecks")
    all_pass = True
    for label, ok in checks:
        status = "PASS" if ok else "FAIL"
        print(f"- {label}: {status}")
        all_pass = all_pass and ok

    print("\nOverall:", "PASS" if all_pass else "FAIL")
    strict_thresholds = os.getenv("POCKETGPT_STAGE2_STRICT_THRESHOLDS", "").strip().lower() in {"1", "true", "yes"}
    if strict_thresholds:
        return 0 if all_pass else 2
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: evaluate_thresholds.py <benchmark_csv>")
        sys.exit(1)
    sys.exit(main(sys.argv[1]))
